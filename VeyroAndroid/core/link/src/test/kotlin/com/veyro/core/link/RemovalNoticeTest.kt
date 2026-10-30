package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.crypto.Hkdf
import com.veyro.core.crypto.X25519
import com.veyro.core.link.ConnectionManager.CodePairingProgress
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.model.Platform
import com.veyro.core.model.TrustedPeer
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ADR-057: a device removed while it was away is told, verified, the next time it dials in.
 *
 * Two real [ConnectionManager]s over an in-memory WebSocket, real X25519 identities. The "PC" removed
 * the "phone" while the phone was away; the phone still trusts the PC and keeps dialling it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemovalNoticeTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = testDispatcher
        override val io = testDispatcher
        override val main = testDispatcher
    }

    /** Inbound handlers run here, so a test never waits on a session it did not close. */
    private val wires = CoroutineScope(testDispatcher + Job())

    @After
    fun tearDown() {
        wires.cancel()
    }

    private class Device(val name: String) {
        val sk: ByteArray = X25519.generatePrivateKey()
        val pk: ByteArray = X25519.publicKeyOf(sk)
        val id: String = DeviceId.fromPublicKey(pk).value
        val trusted = mutableListOf<TrustedPeer>()

        /** Pairing secrets kept for an owed `unpair`, by device id (ADR-057). */
        val owed = mutableMapOf<String, ByteArray>()
        val forgotten = mutableListOf<String>()
        val told = mutableListOf<String>()

        fun record(): TrustedPeer = TrustedPeer(
            deviceId = DeviceId(id),
            staticPublicKey = pk,
            pairingSecret = ByteArray(32),
            name = name,
            platform = Platform.ANDROID,
            pairedAtEpochMillis = 1L,
        )
    }

    private val pc = Device("Office PC")
    private val phone = Device("Pixel")
    private val endpoints = listOf(Endpoint("192.168.1.20", 5150))
    private val secret = ByteArray(32) { 42 }

    /** Each dial opens a fresh pipe whose other end [dialTo] handles as an inbound connection. */
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
                self.owed.remove(peer.deviceId.value)
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
            forgetTrustedPeer = { id ->
                self.trusted.removeAll { it.deviceId.value == id }
                self.forgotten += id
            },
            removeTrustedPeer = { id ->
                self.trusted.firstOrNull { it.deviceId.value == id }?.let { self.owed[id] = it.pairingSecret.copyOf() }
                self.trusted.removeAll { it.deviceId.value == id }
            },
            pendingUnpairSecret = { id -> self.owed[id]?.copyOf() },
            unpairDelivered = { id ->
                self.owed.remove(id)
                self.told += id
            },
        )

    /** The PC removed the phone while it was away: no trust record, its secret kept. */
    private fun pcRemovedPhoneWhileAway() {
        pc.owed[phone.id] = secret.copyOf()
    }

    /** The phone still trusts the PC under the same secret. */
    private fun phoneStillTrustsPc() {
        phone.trusted += pc.record().copy(pairingSecret = secret.copyOf(), platform = Platform.WINDOWS)
    }

    private suspend fun <T : Any> awaitValue(what: String, read: () -> T?): T {
        repeat(500) {
            read()?.let { return it }
            delay(10)
        }
        fail("timed out waiting for $what")
        error("unreachable")
    }

    @Test
    fun `a phone removed while away is told when it dials in and forgets the PC`() = runTest(testDispatcher) {
        pcRemovedPhoneWhileAway()
        phoneStillTrustsPc()
        val pcManager = manager(pc)
        val phoneManager = manager(phone) { pcManager }

        phoneManager.connect(pc.id, pc.name, endpoints)

        awaitValue("the phone forgetting the PC") { phone.forgotten.firstOrNull() }
        assertEquals(listOf(pc.id), phone.forgotten)
        assertTrue("the phone holds no record of the PC", phone.trusted.isEmpty())
        awaitValue("the PC dropping its notice") { pc.told.firstOrNull() }
        assertTrue("the kept secret is gone once the phone was told", pc.owed.isEmpty())
        assertNull("the PC never opened a companion session for it", pcManager.connectionFor(phone.id))
    }

    @Test
    fun `a dialler with the wrong secret is not told, and its frames are not acted on`() = runTest(testDispatcher) {
        pcRemovedPhoneWhileAway()
        val pcManager = manager(pc)
        val (attacker, pcEnd) = PipeSession.pair()
        wires.launch { pcManager.handleInbound(pcEnd) }

        // Claims the phone's id, then sends a hello sealed under a key it does not have.
        attacker.outgoingChannel.send(
            Frame.Text(PeerMessageJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble(phone.id, "Pixel"))),
        )
        val ack = PeerMessageJson.codec.decodeFromString(
            LinkPreambleAck.serializer(),
            (attacker.incomingChannel.receive() as Frame.Text).readText(),
        )
        assertEquals(LinkPreambleAck.MODE_SESSION, ack.mode)
        val forger = PeerConnection(
            ws = attacker,
            initialKey = FrameCrypto.sessionKeyFromBytes(ByteArray(32) { 7 }),
            initialEncrypt = true,
            localDeviceId = phone.id,
            tag = "Forger",
            dispatcher = testDispatcher,
            peerDeviceId = pc.id,
            enableHeartbeat = false,
        ).also { it.start() }
        forger.send(PeerMessage.Hello(phone.id, "Pixel", "android", 0))
        delay(500)

        assertTrue("nothing reached the forger after the ack", attacker.incomingChannel.isEmpty)
        assertFalse("no unpair was sent", forger.unpaired.isCompleted)
        forger.close()
    }

    @Test
    fun `pairing again from a phone that removed the PC too goes through`() = runTest(testDispatcher) {
        // The PC removed the phone while it was away; the phone then removed the PC as well.
        pcRemovedPhoneWhileAway()
        val pcManager = manager(pc)
        val phoneManager = manager(phone) { pcManager }
        val progress = mutableListOf<CodePairingProgress>()
        val outbound = wires.launch { phoneManager.pairWithConfirm(pc.id, pc.name, endpoints).collect { progress += it } }

        // First dial: the PC answers "session" to tell it; the phone has no secret and closes;
        // the PC drops its notice; the phone dials again and reaches the normal prompt.
        val prompt = awaitValue("the PC's pairing prompt") { pcManager.pendingInbound.value?.takeIf { it.confirmCode != null } }
        assertEquals(phone.id, prompt.peerDeviceId)
        assertTrue("the notice was dropped", pc.owed.isEmpty())

        pcManager.respondToInbound(true)
        awaitValue("paired") { progress.lastOrNull() as? CodePairingProgress.Paired }
        assertNotNull(phone.trusted.firstOrNull { it.deviceId.value == pc.id })
        assertNotNull(pc.trusted.firstOrNull { it.deviceId.value == phone.id })
        outbound.cancel()
        phoneManager.disconnect()
        pcManager.disconnect()
    }

    @Test
    fun `removing a connected phone tells it at once and keeps no secret`() = runTest(testDispatcher) {
        pc.trusted += phone.record().copy(pairingSecret = secret.copyOf())
        phoneStillTrustsPc()
        val pcManager = manager(pc)
        val phoneManager = manager(phone) { pcManager }
        phoneManager.connect(pc.id, pc.name, endpoints)
        awaitValue("the PC's session") { pcManager.connectionFor(phone.id) }

        pcManager.unpair(phone.id)

        awaitValue("the phone forgetting the PC") { phone.forgotten.firstOrNull() }
        assertTrue("told now, so nothing is kept", pc.owed.isEmpty())
        assertTrue(pc.trusted.isEmpty())
    }
}
