package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.crypto.ConnectionKeySchedule
import com.veyro.core.crypto.EphemeralKey
import com.veyro.core.crypto.Hkdf
import com.veyro.core.crypto.LinkRole
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
            LinkPreambleAck(
                LinkPreambleAck.MODE_PAIRING_CONFIRM, pk = b64(pk), commit = b64(commit),
                cn = b64(PairConfirm.newNonce()), epk = b64(EphemeralKey.generate().publicKey),
            ),
        ),
    )

    private fun nonceFrame(n: ByteArray): Frame.Text =
        Frame.Text(PeerMessageJson.codec.encodeToString(LinkPairNonce.serializer(), LinkPairNonce(n = b64(n))))

    @Test
    fun `preamble carries pair confirm, the key, cn, epk and version 3`() = runTest(testDispatcher) {
        runAgainstScriptedPc { fromPhone, toPhone ->
            val preamble = readPreamble(fromPhone)
            assertEquals(LinkPreamble.PAIR_CONFIRM, preamble.pair)
            assertEquals(b64(phone.pk), preamble.pk)
            assertEquals(3, preamble.protocolVersion)
            assertEquals(32, FrameCrypto.decodeKey32(preamble.cn)?.size)
            assertEquals(32, FrameCrypto.decodeKey32(preamble.epk)?.size)
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
        initiatorEnd.outgoingChannel.send(V3TestPeer(phone.id).preamble("Phone", "android", LinkPreamble.PAIR_CONFIRM, other.pk))
        pcManager.handleInbound(pcEnd)

        val ack = PeerMessageJson.codec.decodeFromString(LinkPreambleAck.serializer(), text(initiatorEnd.incomingChannel.receive()))
        assertEquals(LinkPreambleAck.MODE_REFUSED, ack.mode)
        assertEquals("identity-mismatch", ack.reason)
        assertNull(ack.commit)
        assertNull(pcManager.pendingInbound.value)
        assertTrue(pc.saved.isEmpty())
    }

    @Test
    fun `receiver refuses protocol version 2 (no v2 support)`() = runTest(testDispatcher) {
        val (initiatorEnd, pcEnd) = PipeSession.pair()
        val pcManager = manager(pc, "Office PC")
        initiatorEnd.outgoingChannel.send(
            Frame.Text(
                PeerMessageJson.codec.encodeToString(
                    LinkPreamble.serializer(),
                    LinkPreamble(phone.id, "Phone", protocolVersion = 2, pair = LinkPreamble.PAIR_CONFIRM, pk = b64(phone.pk)),
                ),
            ),
        )
        pcManager.handleInbound(pcEnd)

        val ack = PeerMessageJson.codec.decodeFromString(LinkPreambleAck.serializer(), text(initiatorEnd.incomingChannel.receive()))
        assertEquals(LinkPreambleAck.MODE_REFUSED, ack.mode)
        assertEquals("unsupported-protocol-version", ack.reason)
    }

    // ── QR pairing, phone side (04-PROTOCOL.md 4.2, ADR-058) ─────────────────────────────

    private val qrSecret = ByteArray(16) { (it * 7).toByte() }

    /** A scripted PC with an open QR window: answers `pairing`, checks the proof, sends [pairOk]. */
    private suspend fun scriptedQrPc(
        fromPhone: Channel<Frame>,
        toPhone: Channel<Frame>,
        answerPk: ByteArray = pc.pk,
        pairOk: (LinkPreamble) -> PeerMessage = { PeerMessage.PairOk(pc.id, "Office PC", "windows", b64(ByteArray(32) { 5 }), b64(pc.pk)) },
    ): Pair<PeerMessage.PairHello?, Frame?> {
        val pFrame = fromPhone.receive() as Frame.Text
        val p = pFrame.data.copyOf()
        val preamble = PeerMessageJson.codec.decodeFromString(LinkPreamble.serializer(), String(p))
        val eph = EphemeralKey.generate()
        val ackBytes = PeerMessageJson.codec.encodeToString(
            LinkPreambleAck.serializer(),
            LinkPreambleAck(LinkPreambleAck.MODE_PAIRING, pk = b64(answerPk), cn = b64(PairConfirm.newNonce()), epk = b64(eph.publicKey)),
        ).toByteArray()
        toPhone.send(Frame.Text(true, ackBytes))
        val transcript = ConnectionKeySchedule.transcript(p, ackBytes)
        if (!answerPk.contentEquals(pc.pk)) return null to fromPhone.receive()
        val pkPhone = FrameCrypto.decodeKey32(preamble.pk)!!
        val staticKey = Hkdf.deriveKey(X25519.agree(pc.sk, pkPhone), transcript, ConnectionKeySchedule.staticInfo(preamble.id, pc.id), 32)
        val keys = ConnectionKeySchedule.pairQr(
            LinkRole.RESPONDER, preamble.id, pc.id, transcript,
            eph.agree(FrameCrypto.decodeKey32(preamble.epk)!!), staticKey, ConnectionKeySchedule.qrMix(qrSecret),
        )
        val side = V3TestPeer(pc.id).apply { install(keys) }
        val hello = side.open(fromPhone.receive()).payload as PeerMessage.PairHello
        val expected = ConnectionKeySchedule.qrProof(ConnectionKeySchedule.qrProofKey(qrSecret), preamble.id, pc.id, transcript)
        assertArrayEquals("the phone proves it read the QR secret", expected, FrameCrypto.decodeKey32(hello.qrProof))
        val ok = pairOk(preamble)
        toPhone.send(side.seal(ok))
        if (ok is PeerMessage.PairOk && ok.deviceId == pc.id && ok.publicKeyB64 == b64(pc.pk)) {
            // Switch to session keys and read the phone's first session frame.
            val session = ConnectionKeySchedule.afterPairing(keys, FrameCrypto.decodeStrictBase64(ok.sessionSecretB64)!!)
            val sessionSide = V3TestPeer(pc.id).apply { install(session) }
            assertTrue("the phone's first session frame is its hello", sessionSide.open(fromPhone.receive()).payload is PeerMessage.Hello)
            toPhone.send(sessionSide.seal(PeerMessage.HelloAck(pc.id, "Office PC", "windows")))
        }
        return hello to null
    }

    private suspend fun runQr(script: suspend (Channel<Frame>, Channel<Frame>) -> Unit): Pair<List<CodePairingProgress>, ConnectionManager> {
        val (phoneEnd, pcEnd) = PipeSession.pair()
        val phoneManager = manager(phone, "Phone", phoneEnd)
        val progress = mutableListOf<CodePairingProgress>()
        kotlinx.coroutines.coroutineScope {
            val outbound = launch { phoneManager.pairWithQr(pc.id, "Office PC", endpoints, qrSecret.copyOf()).collect { progress += it } }
            script(pcEnd.incomingChannel, pcEnd.outgoingChannel)
            outbound.join()
        }
        return progress to phoneManager
    }

    @Test
    fun `QR pairing sends pair qr with the key and proves the QR secret`() = runTest(testDispatcher) {
        val (progress, phoneManager) = runQr { fromPhone, toPhone -> scriptedQrPc(fromPhone, toPhone) }
        assertTrue("expected Paired, got ${progress.last()}", progress.last() is CodePairingProgress.Paired)
        val record = phone.saved.single()
        assertEquals(pc.id, record.deviceId.value)
        assertArrayEquals("the key that took part in the DH is stored", pc.pk, record.staticPublicKey)
        assertArrayEquals(ByteArray(32) { 5 }, record.pairingSecret)
        awaitValue("the claimed session") { phoneManager.activeConnection.value }
        phoneManager.disconnect()
    }

    @Test
    fun `QR pairing stops before anything sealed when the answering key is not the scanned PC`() = runTest(testDispatcher) {
        val impostor = Device()
        var next: Frame? = null
        val (progress, _) = runQr { fromPhone, toPhone -> next = scriptedQrPc(fromPhone, toPhone, answerPk = impostor.pk).second }
        assertEquals(CodePairingFailure.IDENTITY_MISMATCH, (progress.last() as CodePairingProgress.Failed).failure)
        assertTrue("no pairHello may go to an impostor, got $next", next is Frame.Close)
        assertTrue(phone.saved.isEmpty())
    }

    @Test
    fun `QR pairing refuses a pairOk naming another identity and stores nothing`() = runTest(testDispatcher) {
        val other = Device()
        val (progress, _) = runQr { fromPhone, toPhone ->
            scriptedQrPc(fromPhone, toPhone) { PeerMessage.PairOk(other.id, "Office PC", "windows", b64(ByteArray(32) { 5 }), b64(other.pk)) }
        }
        assertEquals(CodePairingFailure.IDENTITY_MISMATCH, (progress.last() as CodePairingProgress.Failed).failure)
        assertTrue(phone.saved.isEmpty())
    }

    @Test
    fun `QR pairing with a malformed secret fails before dialling`() = runTest(testDispatcher) {
        val (phoneEnd, _) = PipeSession.pair()
        val phoneManager = manager(phone, "Phone", phoneEnd)
        val progress = mutableListOf<CodePairingProgress>()
        phoneManager.pairWithQr(pc.id, "Office PC", endpoints, ByteArray(6)).collect { progress += it }
        assertEquals(CodePairingFailure.CODE_REJECTED, (progress.last() as CodePairingProgress.Failed).failure)
    }

    @Test
    fun `an unknown device without a pairing mode is refused not-paired (legacy PIN removed)`() = runTest(testDispatcher) {
        val (initiatorEnd, pcEnd) = PipeSession.pair()
        val pcManager = manager(pc, "Office PC")
        initiatorEnd.outgoingChannel.send(V3TestPeer(phone.id).preamble("Phone", "android"))
        pcManager.handleInbound(pcEnd)
        val ack = PeerMessageJson.codec.decodeFromString(LinkPreambleAck.serializer(), text(initiatorEnd.incomingChannel.receive()))
        assertEquals(LinkPreambleAck.MODE_REFUSED, ack.mode)
        assertEquals("not-paired", ack.reason)
        assertNull("no prompt", pcManager.pendingInbound.value)
    }

    @Test
    fun `a non-canonical preamble id is refused invalid-identity`() = runTest(testDispatcher) {
        val (initiatorEnd, pcEnd) = PipeSession.pair()
        val pcManager = manager(pc, "Office PC")
        initiatorEnd.outgoingChannel.send(V3TestPeer(phone.id.lowercase()).preamble("Phone", "android", LinkPreamble.PAIR_CONFIRM, phone.pk))
        pcManager.handleInbound(pcEnd)
        val ack = PeerMessageJson.codec.decodeFromString(LinkPreambleAck.serializer(), text(initiatorEnd.incomingChannel.receive()))
        assertEquals("invalid-identity", ack.reason)
    }

    @Test
    fun `a preamble without cn and epk is closed with no ack detail`() = runTest(testDispatcher) {
        val (initiatorEnd, pcEnd) = PipeSession.pair()
        val pcManager = manager(pc, "Office PC")
        initiatorEnd.outgoingChannel.send(
            Frame.Text(PeerMessageJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble(phone.id, "Phone", pair = LinkPreamble.PAIR_CONFIRM, pk = b64(phone.pk)))),
        )
        pcManager.handleInbound(pcEnd)
        assertTrue("no ack, only a close", initiatorEnd.incomingChannel.tryReceive().getOrNull().let { it == null || it is Frame.Close })
    }

    @Test
    fun `the confirm responder sends no ping while its user decides (35 s)`() = runTest(testDispatcher) {
        val phoneIn = Channel<Frame>(Channel.UNLIMITED)
        val pcIn = Channel<Frame>(Channel.UNLIMITED)
        val pcOut = Channel<Frame>(Channel.UNLIMITED)
        val phoneEnd = PipeSession(incomingChannel = phoneIn, outgoingChannel = pcIn)
        val pcEnd = PipeSession(incomingChannel = pcIn, outgoingChannel = pcOut)
        val seen = java.util.Collections.synchronizedList(mutableListOf<Frame>())
        val relay = launch {
            for (frame in pcOut) {
                seen += frame
                phoneIn.send(frame)
            }
        }
        val phoneManager = manager(phone, "Phone", phoneEnd)
        val pcManager = manager(pc, "Office PC")
        val inbound = launch { pcManager.handleInbound(pcEnd) }
        val outbound = launch { phoneManager.pairWithConfirm(pc.id, "Office PC", endpoints).collect {} }
        awaitValue("the PC prompt") { pcManager.pendingInbound.value?.confirmCode }
        val atPrompt = seen.size
        delay(35_000)
        assertEquals("the responder sends nothing while its user decides", atPrompt, seen.size)
        phoneManager.cancelOutboundPairing()
        outbound.join()
        inbound.join()
        relay.cancel()
    }
    @Test
    fun `pairing that never establishes still reports Paired and claims nothing (finding 29)`() = runTest(testDispatcher) {
        val (phoneEnd, pcEnd) = PipeSession.pair()
        // The PC side drops every phone frame after pairOk: the phone's session hello never arrives.
        val pcManager = manager(pc, "Office PC")
        val filtered = PipeSession(incomingChannel = Channel(Channel.UNLIMITED), outgoingChannel = pcEnd.outgoingChannel)
        var drop = false
        val relay = launch {
            for (frame in pcEnd.incomingChannel) {
                if (!drop) filtered.incomingChannel.send(frame)
            }
        }
        val inbound = launch { pcManager.handleInbound(filtered) }
        val phoneManager = manager(phone, "Phone", phoneEnd)
        val progress = mutableListOf<CodePairingProgress>()
        val outbound = launch { phoneManager.pairWithConfirm(pc.id, "Office PC", endpoints).collect { progress += it } }
        awaitValue("the PC prompt") { pcManager.pendingInbound.value?.confirmCode }
        drop = true
        pcManager.respondToInbound(true)
        outbound.join()
        assertTrue("the initiator still reports Paired: ${progress.last()}", progress.last() is CodePairingProgress.Paired)
        assertEquals(phone.id, pc.saved.single().deviceId.value)
        assertTrue(pcManager.events.value is ConnectionManager.ConnectionEvent.Paired)
        delay(15_000)
        assertNull("the PC never claimed the session", pcManager.connectionFor(phone.id))
        relay.cancel()
        inbound.cancel()
        phoneManager.disconnect()
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
