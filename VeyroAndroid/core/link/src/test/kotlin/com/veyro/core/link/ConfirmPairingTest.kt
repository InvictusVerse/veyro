package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.crypto.Hkdf
import com.veyro.core.crypto.PairConfirm
import com.veyro.core.crypto.X25519
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.model.TrustedPeer
import com.veyro.core.link.ConnectionManager.CodePairingFailure
import com.veyro.core.link.ConnectionManager.CodePairingProgress
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * Confirm-code pairing end to end (04-PROTOCOL.md 4.3, ADR-031): two real [ConnectionManager]s
 * joined by an in-memory WebSocket pipe, with real X25519 identities, plus a scripted fake PC for
 * the attacks the initiator must catch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmPairingTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = testDispatcher
        override val io = testDispatcher
        override val main = testDispatcher
    }

    private class Device {
        val sk: ByteArray = X25519.generatePrivateKey()
        val pk: ByteArray = X25519.publicKeyOf(sk)
        val id: String = DeviceId.fromPublicKey(pk).value
        val saved = mutableListOf<TrustedPeer>()
    }

    private val phone = Device()
    private val pc = Device()
    private val endpoints = listOf(Endpoint("192.168.1.20", 5150))

    private fun manager(self: Device, name: String, session: DefaultWebSocketSession? = null): ConnectionManager =
        ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { self.id },
            localDeviceName = { name },
            trustedPeer = { id -> self.saved.firstOrNull { it.deviceId.value == id } },
            saveTrustedPeer = { self.saved += it },
            client = object : ConnectionClient() {
                override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession =
                    session ?: error("this manager does not dial")
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

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun text(frame: Frame): String = (frame as Frame.Text).readText()

    // ── Loopback: phone (initiator) and PC (receiver) ─────────────────────────────────────

    @Test
    fun `both sides show the same code and pair after Accept`() = runTest(testDispatcher) {
        val (phoneEnd, pcEnd) = PipeSession.pair()
        val phoneManager = manager(phone, "Phone", phoneEnd)
        val pcManager = manager(pc, "Office PC")
        val inbound = launch { pcManager.handleInbound(pcEnd) }
        val progress = mutableListOf<CodePairingProgress>()
        val outbound = launch { phoneManager.pairWithConfirm(pc.id, "Office PC", endpoints).collect { progress += it } }

        val pending = awaitValue("the PC prompt") { pcManager.pendingInbound.value?.takeIf { it.confirmCode != null } }
        val shown = progress.filterIsInstance<CodePairingProgress.AwaitingConfirmation>().single()
        assertEquals(pending.confirmCode, shown.code)
        assertEquals(6, shown.code.length)
        assertEquals(phone.id, pending.peerDeviceId)
        assertEquals("Phone", pending.peerDeviceName)
        assertEquals(shown.code, phoneManager.pendingOutbound.value?.confirmCode)
        assertTrue("nothing is stored before Accept", phone.saved.isEmpty() && pc.saved.isEmpty())

        pcManager.respondToInbound(true)
        outbound.join()

        assertEquals(
            listOf(CodePairingProgress.Connecting, CodePairingProgress.Securing),
            progress.take(2),
        )
        assertTrue("expected Paired, got ${progress.last()}", progress.last() is CodePairingProgress.Paired)
        val phoneRecord = phone.saved.single()
        val pcRecord = pc.saved.single()
        assertEquals(pc.id, phoneRecord.deviceId.value)
        assertArrayEquals(pc.pk, phoneRecord.staticPublicKey)
        assertEquals(phone.id, pcRecord.deviceId.value)
        assertArrayEquals(phone.pk, pcRecord.staticPublicKey)
        assertArrayEquals("both sides hold the same session secret", phoneRecord.pairingSecret, pcRecord.pairingSecret)
        assertNotNull(phoneManager.activeConnection.value)
        assertNotNull(pcManager.activeConnection.value)
        assertNull(pcManager.pendingInbound.value)
        assertNull(phoneManager.pendingOutbound.value)

        // The rekeyed session carries traffic in both directions.
        val pcSeen = pcManager.activeConnection.value!!
        phoneManager.activeConnection.value!!.send(
            PeerMessage.Hello(deviceId = phone.id, deviceName = "Phone", platform = "android", capabilities = 1),
        )
        val bits = awaitValue("hello on the PC") { pcSeen.peerCapabilities.value.bits.takeIf { it != 0 } }
        assertEquals(1, bits)

        phoneManager.disconnect()
        pcManager.disconnect()
        inbound.cancel()
    }

    @Test
    fun `Decline on the PC fails with DECLINED and stores nothing`() = runTest(testDispatcher) {
        val (phoneEnd, pcEnd) = PipeSession.pair()
        val phoneManager = manager(phone, "Phone", phoneEnd)
        val pcManager = manager(pc, "Office PC")
        val inbound = launch { pcManager.handleInbound(pcEnd) }
        val progress = mutableListOf<CodePairingProgress>()
        val outbound = launch { phoneManager.pairWithConfirm(pc.id, "Office PC", endpoints).collect { progress += it } }

        awaitValue("the PC prompt") { pcManager.pendingInbound.value?.confirmCode }
        pcManager.respondToInbound(false)
        outbound.join()
        inbound.join()

        val last = progress.last() as CodePairingProgress.Failed
        assertEquals(CodePairingFailure.DECLINED, last.failure)
        assertTrue(phone.saved.isEmpty())
        assertTrue(pc.saved.isEmpty())
        assertNull(pcManager.pendingInbound.value)
        assertNull(phoneManager.activeConnection.value)
    }

    @Test
    fun `no answer within the window fails with TIMEOUT on both sides`() = runTest(testDispatcher) {
        val (phoneEnd, pcEnd) = PipeSession.pair()
        val phoneManager = manager(phone, "Phone", phoneEnd).apply { confirmTimeoutMs = 2_000 }
        val pcManager = manager(pc, "Office PC").apply { confirmTimeoutMs = 2_000 }
        val inbound = launch { pcManager.handleInbound(pcEnd) }
        val progress = mutableListOf<CodePairingProgress>()
        val outbound = launch { phoneManager.pairWithConfirm(pc.id, "Office PC", endpoints).collect { progress += it } }

        awaitValue("the PC prompt") { pcManager.pendingInbound.value?.confirmCode }
        outbound.join() // nobody answers; virtual time runs out
        inbound.join()

        val last = progress.last() as CodePairingProgress.Failed
        assertEquals(CodePairingFailure.TIMEOUT, last.failure)
        assertNull(pcManager.pendingInbound.value)
        assertTrue(phone.saved.isEmpty() && pc.saved.isEmpty())
        assertTrue(pcManager.events.value is ConnectionManager.ConnectionEvent.Failed)
    }

    @Test
    fun `Cancel on the phone closes the PC prompt`() = runTest(testDispatcher) {
        val (phoneEnd, pcEnd) = PipeSession.pair()
        val phoneManager = manager(phone, "Phone", phoneEnd)
        val pcManager = manager(pc, "Office PC")
        val inbound = launch { pcManager.handleInbound(pcEnd) }
        val progress = mutableListOf<CodePairingProgress>()
        val outbound = launch { phoneManager.pairWithConfirm(pc.id, "Office PC", endpoints).collect { progress += it } }

        awaitValue("the PC prompt") { pcManager.pendingInbound.value?.confirmCode }
        phoneManager.cancelOutboundPairing()
        outbound.join()
        inbound.join()

        assertEquals(CodePairingFailure.CANCELLED, (progress.last() as CodePairingProgress.Failed).failure)
        assertNull("the PC prompt must disappear", pcManager.pendingInbound.value)
        assertTrue(phone.saved.isEmpty() && pc.saved.isEmpty())
    }

    // ── Scripted PC: what the initiator must refuse ───────────────────────────────────────

    private suspend fun runAgainstScriptedPc(
        script: suspend (fromPhone: Channel<Frame>, toPhone: Channel<Frame>) -> Unit,
    ): List<CodePairingProgress> {
        val (phoneEnd, pcEnd) = PipeSession.pair()
        val phoneManager = manager(phone, "Phone", phoneEnd)
        val progress = mutableListOf<CodePairingProgress>()
        kotlinx.coroutines.coroutineScope {
            val outbound = launch { phoneManager.pairWithConfirm(pc.id, "Office PC", endpoints).collect { progress += it } }
            script(pcEnd.incomingChannel, pcEnd.outgoingChannel)
            outbound.join()
        }
        return progress
    }

    private suspend fun readPreamble(fromPhone: Channel<Frame>): LinkPreamble =
        PeerMessageJson.codec.decodeFromString(LinkPreamble.serializer(), text(fromPhone.receive()))

    private fun ackFrame(pk: ByteArray, commit: ByteArray): Frame.Text = Frame.Text(
        PeerMessageJson.codec.encodeToString(
            LinkPreambleAck.serializer(),
            LinkPreambleAck(LinkPreambleAck.MODE_PAIRING_CONFIRM, pk = b64(pk), commit = b64(commit)),
        ),
    )

    private fun nonceFrame(n: ByteArray): Frame.Text =
        Frame.Text(PeerMessageJson.codec.encodeToString(LinkPairNonce.serializer(), LinkPairNonce(n = b64(n))))

    @Test
    fun `preamble carries pair confirm, the key and version 2`() = runTest(testDispatcher) {
        runAgainstScriptedPc { fromPhone, toPhone ->
            val preamble = readPreamble(fromPhone)
            assertEquals(LinkPreamble.PAIR_CONFIRM, preamble.pair)
            assertEquals(b64(phone.pk), preamble.pk)
            assertEquals(2, preamble.protocolVersion)
            assertEquals(phone.id, preamble.id)
            toPhone.send(Frame.Text("""{"mode":"refused","reason":"declined"}"""))
        }
    }

    @Test
    fun `a nonce that does not match the commitment aborts before any code or pairHello`() = runTest(testDispatcher) {
        var afterNonce: Frame? = null
        val progress = runAgainstScriptedPc { fromPhone, toPhone ->
            val preamble = readPreamble(fromPhone)
            val pkA = Base64.getDecoder().decode(preamble.pk)
            val committed = PairConfirm.newNonce()
            toPhone.send(ackFrame(pc.pk, PairConfirm.commitment(pc.pk, pkA, committed)))
            fromPhone.receive() // nA
            // A man in the middle that saw nA and now picks a different nB.
            toPhone.send(nonceFrame(PairConfirm.newNonce()))
            afterNonce = fromPhone.receive()
        }
        assertEquals(CodePairingFailure.IDENTITY_MISMATCH, (progress.last() as CodePairingProgress.Failed).failure)
        assertTrue("no code may be shown", progress.none { it is CodePairingProgress.AwaitingConfirmation })
        assertTrue("the phone closes instead of sending pairHello, got $afterNonce", afterNonce is Frame.Close)
        assertTrue(phone.saved.isEmpty())
    }

    @Test
    fun `a key that does not hash to the dialled id aborts before the nonce`() = runTest(testDispatcher) {
        val impostor = Device()
        var afterAck: Frame? = null
        val progress = runAgainstScriptedPc { fromPhone, toPhone ->
            val preamble = readPreamble(fromPhone)
            val pkA = Base64.getDecoder().decode(preamble.pk)
            toPhone.send(ackFrame(impostor.pk, PairConfirm.commitment(impostor.pk, pkA, PairConfirm.newNonce())))
            afterAck = fromPhone.receive()
        }
        assertEquals(CodePairingFailure.IDENTITY_MISMATCH, (progress.last() as CodePairingProgress.Failed).failure)
        assertTrue("nA must not be revealed to an impostor, got $afterAck", afterAck is Frame.Close)
        assertTrue(phone.saved.isEmpty())
    }

    // ── Receiver checks ───────────────────────────────────────────────────────────────────

    @Test
    fun `receiver refuses a preamble whose key does not match its id`() = runTest(testDispatcher) {
        val (initiatorEnd, pcEnd) = PipeSession.pair()
        val pcManager = manager(pc, "Office PC")
        val other = Device()
        initiatorEnd.outgoingChannel.send(
            Frame.Text(
                PeerMessageJson.codec.encodeToString(
                    LinkPreamble.serializer(),
                    LinkPreamble(phone.id, "Phone", pair = LinkPreamble.PAIR_CONFIRM, pk = b64(other.pk)),
                ),
            ),
        )
        pcManager.handleInbound(pcEnd)

        val ack = PeerMessageJson.codec.decodeFromString(LinkPreambleAck.serializer(), text(initiatorEnd.incomingChannel.receive()))
        assertEquals(LinkPreambleAck.MODE_REFUSED, ack.mode)
        assertEquals("identity-mismatch", ack.reason)
        assertNull(ack.commit)
        assertNull(pcManager.pendingInbound.value)
        assertTrue(pc.saved.isEmpty())
    }

    @Test
    fun `receiver refuses protocol version 1`() = runTest(testDispatcher) {
        val (initiatorEnd, pcEnd) = PipeSession.pair()
        val pcManager = manager(pc, "Office PC")
        initiatorEnd.outgoingChannel.send(
            Frame.Text(
                PeerMessageJson.codec.encodeToString(
                    LinkPreamble.serializer(),
                    LinkPreamble(phone.id, "Phone", protocolVersion = 1, pair = LinkPreamble.PAIR_CONFIRM, pk = b64(phone.pk)),
                ),
            ),
        )
        pcManager.handleInbound(pcEnd)

        val ack = PeerMessageJson.codec.decodeFromString(LinkPreambleAck.serializer(), text(initiatorEnd.incomingChannel.receive()))
        assertEquals(LinkPreambleAck.MODE_REFUSED, ack.mode)
        assertEquals("unsupported-protocol-version", ack.reason)
    }

    @Test
    fun `QR pairing sends pair qr and no key`() = runTest(testDispatcher) {
        val (phoneEnd, pcEnd) = PipeSession.pair()
        val phoneManager = manager(phone, "Phone", phoneEnd)
        val job = launch { phoneManager.pairWithCode(pc.id, "Office PC", endpoints, "123456").collect {} }
        val preamble = readPreamble(pcEnd.incomingChannel)
        assertEquals(LinkPreamble.PAIR_QR, preamble.pair)
        assertNull(preamble.pk)
        pcEnd.outgoingChannel.send(Frame.Text("""{"mode":"refused","reason":"no-pairing-window"}"""))
        job.join()
    }
}

/** One end of an in-memory WebSocket: what one side sends, the other receives. */
@OptIn(io.ktor.utils.io.InternalAPI::class)
internal class PipeSession(
    val incomingChannel: Channel<Frame>,
    val outgoingChannel: Channel<Frame>,
    override val coroutineContext: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Job(),
) : DefaultWebSocketSession {
    private val closeDeferred = kotlinx.coroutines.CompletableDeferred<CloseReason?>()

    override val incoming: kotlinx.coroutines.channels.ReceiveChannel<Frame> get() = incomingChannel
    override val outgoing: kotlinx.coroutines.channels.SendChannel<Frame> get() = outgoingChannel
    override val closeReason: kotlinx.coroutines.Deferred<CloseReason?> get() = closeDeferred

    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override var pingIntervalMillis: Long = 0
    override var timeoutMillis: Long = 0
    override val extensions: List<io.ktor.websocket.WebSocketExtension<*>> = emptyList()

    override suspend fun flush() {}

    @Suppress("DEPRECATION")
    override fun start(negotiatedExtensions: List<io.ktor.websocket.WebSocketExtension<*>>) {}

    override fun terminate() {
        incomingChannel.close()
        outgoingChannel.close()
        closeDeferred.complete(CloseReason(CloseReason.Codes.NORMAL, "terminated"))
    }

    companion object {
        fun pair(): Pair<PipeSession, PipeSession> {
            val aToB = Channel<Frame>(Channel.UNLIMITED)
            val bToA = Channel<Frame>(Channel.UNLIMITED)
            return PipeSession(incomingChannel = bToA, outgoingChannel = aToB) to
                PipeSession(incomingChannel = aToB, outgoingChannel = bToA)
        }
    }
}
