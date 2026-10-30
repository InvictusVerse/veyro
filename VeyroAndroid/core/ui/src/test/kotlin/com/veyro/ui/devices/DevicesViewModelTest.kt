package com.veyro.ui.devices

import com.veyro.core.data.TrustStore
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Platform
import com.veyro.core.model.TrustedPeer
import com.veyro.ui.home.HomeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** ADR-059: Rename on another device is this phone's nickname for it. */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Store(initial: List<TrustedPeer>) : TrustStore {
        val state = MutableStateFlow(initial)
        override val peers: Flow<List<TrustedPeer>> = state
        override suspend fun snapshot(): List<TrustedPeer> = state.value
        override suspend fun find(deviceId: DeviceId): TrustedPeer? = state.value.firstOrNull { it.deviceId == deviceId }
        override suspend fun put(peer: TrustedPeer) {
            state.value = state.value.filterNot { it.deviceId == peer.deviceId } + peer
        }
        override suspend fun remove(deviceId: DeviceId) {
            state.value = state.value.filterNot { it.deviceId == deviceId }
        }
        override suspend fun setAutoAccept(deviceId: DeviceId, autoAccept: Boolean) = Unit
        override suspend fun markConnected(deviceId: DeviceId, atMillis: Long) = Unit
    }

    private val pc = TrustedPeer(
        deviceId = DeviceId.fromPublicKey(ByteArray(32) { 3 }),
        staticPublicKey = ByteArray(32) { 3 },
        pairingSecret = ByteArray(32),
        name = "DESKTOP-7Q2",
        platform = Platform.WINDOWS,
        pairedAtEpochMillis = 1L,
    )

    private fun stored(store: Store) = runBlocking { store.find(pc.deviceId)!! }

    @Test
    fun `a rename is a nickname, and the device keeps its own name`() {
        val store = Store(listOf(pc))
        val vm = DevicesViewModel(store)
        vm.rename(pc.deviceId, "  Study PC ")
        assertEquals("DESKTOP-7Q2", stored(store).name)
        assertEquals("Study PC", stored(store).nickname)

        // Every card shows the nickname.
        val home = HomeUiState(pairedPeers = listOf(stored(store)))
        assertEquals("Study PC", home.pairedDevices.single().name)
    }

    @Test
    fun `typing the device's own name, or Use the device's own name, clears the nickname`() {
        val store = Store(listOf(pc.copy(nickname = "Study PC")))
        val vm = DevicesViewModel(store)
        vm.rename(pc.deviceId, "DESKTOP-7Q2")
        assertNull(stored(store).nickname)

        vm.rename(pc.deviceId, "Office")
        vm.useOwnName(pc.deviceId)
        assertNull(stored(store).nickname)
        assertEquals("DESKTOP-7Q2", stored(store).displayName)
    }

    @Test
    fun `an invalid nickname is not stored`() {
        val store = Store(listOf(pc))
        val vm = DevicesViewModel(store)
        vm.rename(pc.deviceId, "   ")
        vm.rename(pc.deviceId, "Line\nbreak")
        vm.rename(pc.deviceId, "A".repeat(65))
        assertNull(stored(store).nickname)
    }

    @Test
    fun `the device's new own name shows unless a nickname is set`() {
        val store = Store(listOf(pc))
        runBlocking { store.setAnnouncedName(pc.deviceId, "Living room PC") }
        assertEquals("Living room PC", HomeUiState(pairedPeers = listOf(stored(store))).pairedDevices.single().name)

        runBlocking {
            store.setNickname(pc.deviceId, "Mine")
            store.setAnnouncedName(pc.deviceId, "Renamed again")
        }
        assertEquals("Mine", HomeUiState(pairedPeers = listOf(stored(store))).pairedDevices.single().name)
        assertEquals("Renamed again", stored(store).name)
    }
}
