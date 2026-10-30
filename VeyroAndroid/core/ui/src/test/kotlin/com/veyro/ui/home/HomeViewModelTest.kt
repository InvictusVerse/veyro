package com.veyro.ui.home

import com.veyro.core.data.DeviceRepository
import com.veyro.core.data.DiscoverySubscription
import com.veyro.core.link.PeerMessage
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Transfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferState
import com.veyro.ui.format.FileKind
import org.junit.Assert.assertFalse
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
            pcStatus = MutableStateFlow(mapOf(laptop.deviceId.value to PeerMessage.DeviceStatus(battery = 80, charging = true))),
            connectedPeerIds = MutableStateFlow(setOf(laptop.deviceId.value)),
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
        val connected = MutableStateFlow<Set<String>>(emptySet())
        val answer = CompletableDeferred<Boolean>()
        var dialled: List<Endpoint>? = null
        var disconnects = 0
        val vm = HomeViewModel(
            deviceRepository = repo,
            networkMonitor = QuietNetwork,
            connectedPeerIds = connected,
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
        connected.value = setOf(office.deviceId.value)
        assertEquals(ConnectionStatus.CONNECTED, vm.state.value.pairedDevices.single().status)

        vm.toggleConnection(office.deviceId)
        assertEquals(1, disconnects)
    }

    @Test
    fun `a blocked local network shows its notice, and the background notice follows a refresh`() {
        val network = MutableStateFlow(com.veyro.core.network.LocalNetworkState.GRANTED)
        var restricted = false
        val vm = HomeViewModel(
            deviceRepository = FakeRepository(),
            networkMonitor = QuietNetwork,
            localNetwork = network,
            keepAliveNotice = { restricted },
        )
        assertFalse(vm.state.value.showLocalNetworkNotice)
        network.value = com.veyro.core.network.LocalNetworkState.DENIED_SETTINGS
        assertTrue(vm.state.value.showLocalNetworkNotice)
        network.value = com.veyro.core.network.LocalNetworkState.NOT_NEEDED
        assertFalse(vm.state.value.showLocalNetworkNotice)

        vm.refreshHealth()
        assertFalse(vm.state.value.keepAliveNotice)
        restricted = true
        vm.refreshHealth()
        assertTrue(vm.state.value.keepAliveNotice)
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

    // ── Phone Link-style Home (ADR-038) ─────────────────────────────────────────────────────

    private fun history(vararg transfers: Transfer) = MutableStateFlow(transfers.toList())

    private fun transfer(
        id: String,
        direction: TransferDirection,
        names: List<String>,
        state: TransferState = TransferState.COMPLETED,
        uri: String? = null,
    ) = Transfer(
        id = id,
        peerDeviceId = DeviceId.fromPublicKey(ByteArray(32) { 1 }),
        peerDisplayName = "Office PC",
        direction = direction,
        items = names.mapIndexed { i, n -> TransferItem(i, n, 10L, 0L) },
        totalBytes = 10L * names.size,
        state = state,
        startedAtEpochMillis = 1_000L,
        finishedAtEpochMillis = 2_000L,
        destinationUri = uri,
    )

    @Test
    fun `the connected PC shows its battery, charging and Wi-Fi, and can be locked`() {
        val office = trusted(1, "Office PC")
        val vm = HomeViewModel(
            deviceRepository = FakeRepository(),
            networkMonitor = QuietNetwork,
            pcStatus = MutableStateFlow(mapOf(office.deviceId.value to PeerMessage.DeviceStatus(battery = 64, charging = true, wifi = 3))),
            connectedPeerIds = MutableStateFlow(setOf(office.deviceId.value)),
            trustedPeers = MutableStateFlow(listOf(office)),
            peerCapabilities = MutableStateFlow(mapOf(office.deviceId.value to Capabilities.of(Capability.FILE_TRANSFER, Capability.DEVICE_STATUS))),
        )

        val pc = vm.state.value.selectedPc!!
        assertEquals("Office PC", pc.name)
        assertEquals(64, pc.battery)
        assertEquals(true, pc.charging)
        assertEquals(3, pc.wifi)
        assertEquals("Just now", lastSeenLabel(pc, now = 10_000L))
        assertTrue(vm.state.value.canLockPc)
        assertTrue(vm.state.value.canSendFiles)
    }

    @Test
    fun `a disconnected PC shows no live status, cannot be locked, but can still be sent to`() {
        val office = trusted(1, "Office PC").copy(lastConnectedEpochMillis = 1_000L)
        val vm = HomeViewModel(
            deviceRepository = FakeRepository(),
            networkMonitor = QuietNetwork,
            // A stale status from before the disconnect must not be shown for it.
            pcStatus = MutableStateFlow(mapOf(office.deviceId.value to PeerMessage.DeviceStatus(battery = 64, wifi = 3))),
            trustedPeers = MutableStateFlow(listOf(office)),
            peerCapabilities = MutableStateFlow(mapOf(office.deviceId.value to Capabilities.of(Capability.DEVICE_STATUS))),
        )

        val pc = vm.state.value.selectedPc!!
        assertEquals(ConnectionStatus.OFFLINE, pc.status)
        assertNull(pc.battery)
        assertNull(pc.wifi)
        assertEquals("Last connected 5 min ago", lastSeenLabel(pc, now = 1_000L + 5 * 60_000L))
        assertFalse(vm.state.value.canLockPc)
        assertTrue(vm.state.value.canSendFiles)
    }

    @Test
    fun `Lock PC needs the DEVICE_STATUS capability`() {
        val office = trusted(1, "Office PC")
        val vm = HomeViewModel(
            deviceRepository = FakeRepository(),
            networkMonitor = QuietNetwork,
            connectedPeerIds = MutableStateFlow(setOf(office.deviceId.value)),
            trustedPeers = MutableStateFlow(listOf(office)),
            peerCapabilities = MutableStateFlow(mapOf(office.deviceId.value to Capabilities.of(Capability.FILE_TRANSFER))),
            lockPc = { error("must not be called") },
        )
        assertFalse(vm.state.value.canLockPc)
        vm.lockSelectedPc()
        assertNull(vm.state.value.message)
    }

    @Test
    fun `Lock PC sends once and confirms in the snackbar`() {
        val office = trusted(1, "Office PC")
        var locks = 0
        val vm = HomeViewModel(
            deviceRepository = FakeRepository(),
            networkMonitor = QuietNetwork,
            connectedPeerIds = MutableStateFlow(setOf(office.deviceId.value)),
            trustedPeers = MutableStateFlow(listOf(office)),
            peerCapabilities = MutableStateFlow(mapOf(office.deviceId.value to Capabilities.of(Capability.DEVICE_STATUS))),
            lockPc = {
                locks++
                true
            },
        )
        vm.lockSelectedPc()
        assertEquals(1, locks)
        assertEquals("Locking Office PC", vm.state.value.message)
        vm.messageShown()
        assertNull(vm.state.value.message)
    }

    @Test
    fun `the selected PC's wallpaper is drawn, and the switcher changes PC`() {
        val office = trusted(1, "Office PC")
        val laptop = trusted(2, "Laptop")
        val officeJpeg = byteArrayOf(1)
        val vm = HomeViewModel(
            deviceRepository = FakeRepository(),
            networkMonitor = QuietNetwork,
            connectedPeerIds = MutableStateFlow(setOf(office.deviceId.value)),
            trustedPeers = MutableStateFlow(listOf(office, laptop)),
            pcWallpapers = MutableStateFlow(mapOf(office.deviceId.value to officeJpeg)),
        )

        assertEquals("connected PC first", "Office PC", vm.state.value.selectedPc?.name)
        assertTrue(vm.state.value.selectedWallpaper === officeJpeg)

        vm.selectPc(laptop.deviceId)
        assertEquals("Laptop", vm.state.value.selectedPc?.name)
        assertNull("no wallpaper: the gradient fallback", vm.state.value.selectedWallpaper)
    }

    // ── Multiple PCs ────────────────────────────────────────────────────────────────────────

    @Test
    fun `two connected PCs each show their own live state and the switcher drives the selected one`() {
        val office = trusted(1, "Office PC")
        val laptop = trusted(2, "Laptop")
        val laptopJpeg = byteArrayOf(2)
        val selected = mutableListOf<String>()
        val commands = mutableListOf<Pair<String, PeerMessage.MediaCommand>>()
        val disconnected = mutableListOf<String>()
        val locked = mutableListOf<String>()
        val vm = HomeViewModel(
            deviceRepository = FakeRepository(),
            networkMonitor = QuietNetwork,
            connectedPeerIds = MutableStateFlow(setOf(office.deviceId.value, laptop.deviceId.value)),
            pcStatus = MutableStateFlow(
                mapOf(
                    office.deviceId.value to PeerMessage.DeviceStatus(battery = 90),
                    laptop.deviceId.value to PeerMessage.DeviceStatus(battery = 15, wifi = 2),
                ),
            ),
            pcMedia = MutableStateFlow(mapOf(laptop.deviceId.value to PeerMessage.MediaState(sessions = emptyList(), volume = 33))),
            peerCapabilities = MutableStateFlow(
                mapOf(
                    office.deviceId.value to Capabilities.of(Capability.FILE_TRANSFER),
                    laptop.deviceId.value to Capabilities.of(Capability.DEVICE_STATUS),
                ),
            ),
            pcWallpapers = MutableStateFlow(mapOf(laptop.deviceId.value to laptopJpeg)),
            trustedPeers = MutableStateFlow(listOf(office, laptop)),
            sendMediaCommand = { id, command -> commands += id to command; true },
            disconnectPeer = { disconnected += it },
            lockPc = { locked += it; true },
            onPcSelected = { selected += it },
        )

        val cards = vm.state.value.pairedDevices
        assertEquals(listOf(ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTED), cards.map { it.status })
        assertEquals(90, cards.first { it.name == "Office PC" }.battery)
        assertEquals(15, cards.first { it.name == "Laptop" }.battery)
        assertTrue("two connected PCs: Send files asks where", vm.state.value.sendNeedsChoice)
        assertEquals(2, vm.state.value.connectedDevices.size)

        // Office is selected first and cannot be locked (no DEVICE_STATUS) nor shows media.
        assertEquals("Office PC", vm.state.value.selectedPc?.name)
        assertFalse(vm.state.value.canLockPc)
        assertNull(vm.state.value.pcMedia)

        vm.selectPc(laptop.deviceId)
        assertEquals(listOf(laptop.deviceId.value), selected)
        assertEquals(2, vm.state.value.selectedPc?.wifi)
        assertTrue(vm.state.value.selectedWallpaper === laptopJpeg)
        assertTrue(vm.state.value.canLockPc)
        assertEquals(33, vm.state.value.pcMedia?.volume)

        vm.lockSelectedPc()
        assertEquals(listOf(laptop.deviceId.value), locked)
        vm.mediaCommand(PeerMessage.MediaCommand.VOLUME, value = 50)
        assertEquals(laptop.deviceId.value, commands.single().first)

        // Disconnecting one PC from its card names that PC only.
        vm.toggleConnection(office.deviceId)
        assertEquals(listOf(office.deviceId.value), disconnected)
    }

    @Test
    fun `a paired phone gets the phone layout - no Lock PC, its media, and a hint until it shares`() {
        val office = trusted(1, "Office PC")
        val pixel = trusted(3, "Pixel").copy(platform = Platform.ANDROID)
        val media = MutableStateFlow<Map<String, PeerMessage.MediaState>>(emptyMap())
        val commands = mutableListOf<Pair<String, PeerMessage.MediaCommand>>()
        val vm = HomeViewModel(
            deviceRepository = FakeRepository(),
            networkMonitor = QuietNetwork,
            connectedPeerIds = MutableStateFlow(setOf(pixel.deviceId.value)),
            pcStatus = MutableStateFlow(mapOf(pixel.deviceId.value to PeerMessage.DeviceStatus(battery = 64))),
            pcMedia = media,
            // DEVICE_STATUS is what a phone uses for its battery; it must not unlock "Lock PC".
            peerCapabilities = MutableStateFlow(mapOf(pixel.deviceId.value to Capabilities.PHONE_TO_PHONE)),
            trustedPeers = MutableStateFlow(listOf(office, pixel)),
            sendMediaCommand = { id, command -> commands += id to command; true },
        )

        assertEquals("the connected phone is selected", "Pixel", vm.state.value.selectedPc?.name)
        assertTrue(vm.state.value.selectedIsPhone)
        assertFalse(vm.state.value.canLockPc)
        assertTrue(vm.state.value.canSendFiles)
        assertEquals(64, vm.state.value.selectedPc?.battery)
        assertEquals("Pixel", vm.state.value.mediaHintFor?.name)

        val song = PeerMessage.MediaSessionInfo(id = "s1", app = "Music", title = "Song", playing = true)
        media.value = mapOf(pixel.deviceId.value to PeerMessage.MediaState(sessions = listOf(song), volume = 40))
        assertNull("sharing: no hint", vm.state.value.mediaHintFor)
        assertEquals("s1", vm.state.value.pcMediaSession?.id)
        vm.mediaCommand(PeerMessage.MediaCommand.TOGGLE, id = "s1")
        assertEquals(pixel.deviceId.value, commands.single().first)

        vm.selectPc(office.deviceId)
        assertFalse(vm.state.value.selectedIsPhone)
        assertNull("a PC never gets the phone hint", vm.state.value.mediaHintFor)
    }

    @Test
    fun `a device the user disconnected says so, and Connect lifts it even when it is not visible`() {
        val pixel = trusted(3, "Pixel").copy(platform = Platform.ANDROID)
        val resumed = mutableListOf<String>()
        val vm = HomeViewModel(
            deviceRepository = FakeRepository(),
            networkMonitor = QuietNetwork,
            trustedPeers = MutableStateFlow(listOf(pixel)),
            userDisconnectedIds = MutableStateFlow(setOf(pixel.deviceId.value)),
            resumePeer = { resumed += it },
        )

        val card = vm.state.value.selectedPc!!
        assertTrue(card.disconnectedByUser)
        assertTrue("Connect stays available for a device you disconnected", card.canToggleConnection)
        assertEquals("Disconnected. It stays off until you connect it.", lastSeenLabel(card, now = 0L))

        vm.toggleConnection(pixel.deviceId)
        assertEquals(listOf(pixel.deviceId.value), resumed)
        assertNull("not a failure: it reconnects when it shows up", vm.state.value.failedId)
        assertEquals("Pixel will connect when it is nearby", vm.state.value.message)
    }

    @Test
    fun `with one PC connected Send files goes straight to it even if an offline PC is selected`() {
        val office = trusted(1, "Office PC")
        val laptop = trusted(2, "Laptop")
        val vm = HomeViewModel(
            deviceRepository = FakeRepository(),
            networkMonitor = QuietNetwork,
            connectedPeerIds = MutableStateFlow(setOf(office.deviceId.value)),
            trustedPeers = MutableStateFlow(listOf(office, laptop)),
        )
        assertFalse(vm.state.value.sendNeedsChoice)
        assertEquals(office.deviceId, vm.defaultSendTarget())

        vm.selectPc(laptop.deviceId)
        assertEquals("the one connected PC, not the offline selection", office.deviceId, vm.defaultSendTarget())
    }

    @Test
    fun `no paired PC means no selection and no tiles`() {
        val vm = HomeViewModel(deviceRepository = FakeRepository(), networkMonitor = QuietNetwork)
        assertNull(vm.state.value.selectedPc)
        assertFalse(vm.state.value.canSendFiles)
        assertFalse(vm.state.value.canLockPc)
        assertNull(vm.state.value.recent)
    }

    @Test
    fun `Recent shows the newest finished transfer with its direction`() {
        val vm = HomeViewModel(
            deviceRepository = FakeRepository(),
            networkMonitor = QuietNetwork,
            history = history(
                transfer("run", TransferDirection.SEND, listOf("live.bin"), state = TransferState.TRANSFERRING),
                transfer("r1", TransferDirection.RECEIVE, listOf("IMG_2041.jpg", "IMG_2042.jpg"), uri = "content://media/1"),
                transfer("s1", TransferDirection.SEND, listOf("notes.pdf")),
            ),
        )

        val recent = vm.state.value.recent!!
        assertEquals("r1", recent.id)
        assertEquals("IMG_2041.jpg", recent.fileName)
        assertEquals(1, recent.moreCount)
        assertEquals("Received from Office PC", recent.subtitle)
        assertEquals(FileKind.IMAGE, recent.kind)
        assertTrue(recent.canOpen)
    }

    @Test
    fun `a sent file can only be removed from history, not opened`() {
        val removed = mutableListOf<String>()
        val vm = HomeViewModel(
            deviceRepository = FakeRepository(),
            networkMonitor = QuietNetwork,
            history = history(transfer("s1", TransferDirection.SEND, listOf("notes.pdf"))),
            removeHistory = { removed += it },
        )
        val recent = vm.state.value.recent!!
        assertEquals("Sent to Office PC", recent.subtitle)
        assertEquals(FileKind.DOCUMENT, recent.kind)
        assertFalse(recent.canOpen)

        vm.removeFromHistory(recent.id)
        assertEquals(listOf("s1"), removed)
    }

    @Test
    fun `last seen reads like Phone Link`() {
        val base = PairedDeviceUi(trusted(1, "PC").deviceId, "PC", Platform.WINDOWS, ConnectionStatus.OFFLINE)
        assertEquals("Not connected yet", lastSeenLabel(base, now = 0L))
        assertEquals("Nearby, not connected yet", lastSeenLabel(base.copy(status = ConnectionStatus.NEARBY), now = 0L))
        val seen = base.copy(lastConnectedEpochMillis = 0L + 1)
        assertEquals("Last connected just now", lastSeenLabel(seen, now = 30_000L))
        assertEquals("Last connected 3 h ago", lastSeenLabel(seen, now = 3 * 3_600_000L + 1))
        assertEquals("Last connected yesterday", lastSeenLabel(seen, now = 30 * 3_600_000L))
        assertEquals("Connecting...", lastSeenLabel(base.copy(status = ConnectionStatus.CONNECTING), now = 0L))
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
