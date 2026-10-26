package com.veyro.ui.home

import com.veyro.core.data.DeviceRepository
import com.veyro.core.data.DiscoverySubscription
import com.veyro.core.link.LinkMessage
import com.veyro.core.model.Capabilities
import com.veyro.core.model.DeviceId
import com.veyro.core.model.DiscoverySource
import com.veyro.core.model.Endpoint
import com.veyro.core.model.NetworkStatus
import com.veyro.core.model.PeerDevice
import com.veyro.core.model.Platform
import com.veyro.core.model.Reachability
import com.veyro.core.model.TrustState
import com.veyro.core.model.TrustedPeer
import com.veyro.core.network.NetworkMonitor
import com.veyro.core.network.ProbeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The device-card state and actions added for the Sefirah-style Home and Devices screens. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository : DeviceRepository {
        val peers = MutableStateFlow<List<PeerDevice>>(emptyList())
        var opened = 0
        var closed = 0
        override val devices: Flow<List<PeerDevice>> = peers
        override fun observeNearby(forPairing: Boolean): DiscoverySubscription {
            opened++
            return object : DiscoverySubscription {
                override fun close() {
                    closed++
                }
            }
        }
    }

    private object QuietNetwork : NetworkMonitor {
        override val status: Flow<NetworkStatus> = emptyFlow()
        override suspend fun currentStatus(): NetworkStatus = error("not used")
        override suspend fun probe(endpoints: List<Endpoint>, timeoutMillis: Long): ProbeResult = error("not used")
    }

    private fun trusted(seed: Int, name: String): TrustedPeer {
        val pk = ByteArray(32) { seed.toByte() }
        return TrustedPeer(
            deviceId = DeviceId.fromPublicKey(pk),
            staticPublicKey = pk,
            pairingSecret = ByteArray(32),
            name = name,
            platform = Platform.WINDOWS,
            pairedAtEpochMillis = 1L,
        )
    }

    private fun seen(peer: TrustedPeer) = PeerDevice(
        deviceId = peer.deviceId,
        advertisedName = peer.name,
        localName = null,
        platform = Platform.WINDOWS,
        capabilities = Capabilities(0),
        endpoints = listOf(Endpoint("192.168.1.20", 5150)),
        trust = TrustState.TRUSTED,
        reachability = Reachability.UNKNOWN,
        lastSeenEpochMillis = 1L,
        discoveredVia = DiscoverySource.UDP_BEACON,
    )

    @Test
    fun `paired devices are listed with connected first and battery only for the connected PC`() {
        val office = trusted(1, "Office PC")
        val laptop = trusted(2, "Laptop")
        val repo = FakeRepository().apply { peers.value = listOf(seen(office)) }
        val vm = HomeViewModel(
            deviceRepository = repo,
            networkMonitor = QuietNetwork,
            pcStatus = MutableStateFlow(LinkMessage.DeviceStatus(battery = 80, charging = true)),
            connectedPeerId = MutableStateFlow(laptop.deviceId.value),
            trustedPeers = MutableStateFlow(listOf(office, laptop, trusted(3, "Old").copy(revoked = true))),
        )

        val cards = vm.state.value.pairedDevices
        assertEquals(listOf("Laptop", "Office PC"), cards.map { it.name })
        assertEquals(ConnectionStatus.CONNECTED, cards[0].status)
        assertEquals(80, cards[0].battery)
        assertEquals(true, cards[0].charging)
        assertEquals(ConnectionStatus.NEARBY, cards[1].status)
        assertNull(cards[1].battery)
    }

    @Test
    fun `toggle on a nearby device dials its discovered endpoints, and on the connected one disconnects`() {
        val office = trusted(1, "Office PC")
        val repo = FakeRepository().apply { peers.value = listOf(seen(office)) }
        val connected = MutableStateFlow<String?>(null)
        val answer = CompletableDeferred<Boolean>()
        var dialled: List<Endpoint>? = null
        var disconnects = 0
        val vm = HomeViewModel(
            deviceRepository = repo,
            networkMonitor = QuietNetwork,
            connectedPeerId = connected,
            trustedPeers = MutableStateFlow(listOf(office)),
            connectPeer = { _, _, endpoints ->
                dialled = endpoints
                answer.await()
            },
            disconnectPeer = { disconnects++ },
        )

        vm.toggleConnection(office.deviceId)
        assertEquals(listOf(Endpoint("192.168.1.20", 5150)), dialled)
        assertEquals(ConnectionStatus.CONNECTING, vm.state.value.pairedDevices.single().status)

        answer.complete(true)
        connected.value = office.deviceId.value
        assertEquals(ConnectionStatus.CONNECTED, vm.state.value.pairedDevices.single().status)

        vm.toggleConnection(office.deviceId)
        assertEquals(1, disconnects)
    }

    @Test
    fun `a failed connect is shown on the card`() {
        val office = trusted(1, "Office PC")
        val repo = FakeRepository().apply { peers.value = listOf(seen(office)) }
        val vm = HomeViewModel(
            deviceRepository = repo,
            networkMonitor = QuietNetwork,
            trustedPeers = MutableStateFlow(listOf(office)),
            connectPeer = { _, _, _ -> false },
        )

        vm.toggleConnection(office.deviceId)
        val card = vm.state.value.pairedDevices.single()
        assertEquals(ConnectionStatus.NEARBY, card.status)
        assertTrue(card.connectFailed)
    }

    @Test
    fun `discovery stays open until the last screen releases it`() {
        val repo = FakeRepository()
        val vm = HomeViewModel(deviceRepository = repo, networkMonitor = QuietNetwork)

        vm.acquireDiscovery() // Home
        vm.acquireDiscovery() // device settings starts before Home stops
        vm.releaseDiscovery() // Home stops
        assertEquals(1, repo.opened)
        assertEquals(0, repo.closed)

        vm.releaseDiscovery()
        assertEquals(1, repo.closed)

        vm.releaseDiscovery() // extra release is ignored
        assertEquals(1, repo.closed)
    }
}
