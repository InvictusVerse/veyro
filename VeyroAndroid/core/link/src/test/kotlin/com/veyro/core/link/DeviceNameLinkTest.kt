package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.crypto.Hkdf
import com.veyro.core.crypto.X25519
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.model.Platform
import com.veyro.core.model.TrustedPeer
import io.ktor.websocket.DefaultWebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * ADR-059 on the wire: each side of a real session learns the other's own name from its hello,
 * follows a `deviceInfo` rename at once, ignores an invalid name, and a device that was away
 * catches up from the next hello. Two real [ConnectionManager]s over an in-memory WebSocket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceNameLinkTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = testDispatcher
        override val io = testDispatcher
        override val main = testDispatcher
    }
    private val wires = CoroutineScope(testDispatcher + Job())

    @After
    fun tearDown() {
        wires.cancel()
    }

    private class Device(var name: String) {
        val sk: ByteArray = X25519.generatePrivateKey()
        val pk: ByteArray = X25519.publicKeyOf(sk)
        val id: String = DeviceId.fromPublicKey(pk).value
        val trusted = mutableListOf<TrustedPeer>()

        fun record(secret: ByteArray): TrustedPeer = TrustedPeer(
            deviceId = DeviceId(id),
            staticPublicKey = pk,
            pairingSecret = secret.copyOf(),
            name = "stored name",
            platform = Platform.ANDROID,
            pairedAtEpochMillis = 1L,
        )
    }

    private val pc = Device("Office PC")
    private val phone = Device("Pixel")
    private val endpoints = listOf(Endpoint("192.168.1.20", 5150))
    private val secret = ByteArray(32) { 42 }

    private fun manager(self: Device, dialTo: (() -> ConnectionManager)? = null): ConnectionManager =
        ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { self.id },
            localDeviceName = { self.name },
            trustedPeer = { id -> self.trusted.firstOrNull { it.deviceId.value == id } },
            saveTrustedPeer = { peer ->
                self.trusted.removeAll { it.deviceId == peer.deviceId }
                self.trusted += peer
            },
            client = object : ConnectionClient() {
                override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                    val target = dialTo?.invoke() ?: error("this manager does not dial")
                    val (mine, theirs) = PipeSession.pair()
                    wires.launch { target.handleInbound(theirs) }
                    return mine
                }
            },
            localPublicKey = { self.pk },
            deriveSharedKey = { peerPk, salt, info -> Hkdf.deriveKey(X25519.agree(self.sk, peerPk), salt, info, 32) },
        )

    private suspend fun <T : Any> awaitValue(what: String, read: () -> T?): T {
        repeat(500) {
            read()?.let { return it }
            delay(10)
        }
        fail("timed out waiting for $what")
        error("unreachable")
    }

    private fun trustEachOther() {
        pc.trusted += phone.record(secret)
        phone.trusted += pc.record(secret)
    }

    @Test
    fun `each side learns the other's own name from its hello`() = runTest(testDispatcher) {
        trustEachOther()
        val pcManager = manager(pc)
        val phoneManager = manager(phone) { pcManager }
        phoneManager.connect(pc.id, pc.name, endpoints)

        val atPc = awaitValue("the PC's session") { pcManager.connectionFor(phone.id) }
        val atPhone = awaitValue("the phone's session") { phoneManager.connectionFor(pc.id) }
        assertEquals("Pixel", awaitValue("the phone's name at the PC") { atPc.peerAnnouncedName.value })
        assertEquals("Office PC", awaitValue("the PC's name at the phone") { atPhone.peerAnnouncedName.value })
        phoneManager.disconnect()
        pcManager.disconnect()
    }

    @Test
    fun `a deviceInfo rename shows at once, an invalid one is ignored`() = runTest(testDispatcher) {
        trustEachOther()
        val pcManager = manager(pc)
        val phoneManager = manager(phone) { pcManager }
        phoneManager.connect(pc.id, pc.name, endpoints)
        val atPc = awaitValue("the PC's session") { pcManager.connectionFor(phone.id) }
        val atPhone = awaitValue("the phone's session") { phoneManager.connectionFor(pc.id) }
        awaitValue("the first name") { atPhone.peerAnnouncedName.value }

        atPc.send(PeerMessage.DeviceInfo(name = "  Study PC "))
        awaitValue("the new name") { atPhone.peerAnnouncedName.value?.takeIf { it == "Study PC" } }

        atPc.send(PeerMessage.DeviceInfo(name = "Bell\u0007"))
        atPc.send(PeerMessage.DeviceInfo(name = "A".repeat(65)))
        atPc.send(PeerMessage.DeviceInfo(clipReceive = false))
        atPc.send(PeerMessage.Ping("after"))
        delay(200)
        assertEquals("invalid or absent names change nothing", "Study PC", atPhone.peerAnnouncedName.value)
        phoneManager.disconnect()
        pcManager.disconnect()
    }

    @Test
    fun `a device that was away catches up from the next hello`() = runTest(testDispatcher) {
        trustEachOther()
        val pcManager = manager(pc)
        val phoneManager = manager(phone) { pcManager }

        // Renamed while the phone was not connected: nobody could be told.
        pc.name = "Living room PC"
        phoneManager.connect(pc.id, "stored name", endpoints)

        val atPhone = awaitValue("the phone's session") { phoneManager.connectionFor(pc.id) }
        assertEquals("Living room PC", awaitValue("the PC's current name") { atPhone.peerAnnouncedName.value })
        phoneManager.disconnect()
        pcManager.disconnect()
    }
}
