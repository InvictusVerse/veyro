package com.veyro.core.link

import com.veyro.core.crypto.ConnectionKeySchedule
import com.veyro.core.crypto.EphemeralKey
import com.veyro.core.crypto.LinkKeys
import com.veyro.core.crypto.LinkRole
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Link protocol v3 at the connection level (ADR-058, AC 1-3, 13, 19, 20): fail closed,
 * establishment, the admission gate, the exporter, the pairing switch and replay across
 * connections.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerConnectionV3Test {

    private val phone = "PHONE"
    private val pc = "PC"

    /** One end's view of a wire: it reads [inbox] and writes [outbox]. */
    private class End(val inbox: Channel<Frame>, val outbox: Channel<Frame>) {
        val ws = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = inbox
            override val outgoing: SendChannel<Frame> = outbox
        }
    }

    private fun wire(): Pair<End, End> {
        val aToB = Channel<Frame>(Channel.UNLIMITED)
        val bToA = Channel<Frame>(Channel.UNLIMITED)
        return End(bToA, aToB) to End(aToB, bToA)
    }

    private fun connection(end: End, keys: LinkKeys, dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO, firstFrameMs: Long = 10_000) =
        PeerConnection(end.ws, keys, keys.localId, "Test-${keys.localId}", dispatcher, enableHeartbeat = false, firstFrameTimeoutMs = firstFrameMs)

    /** A real connected pair (phone dialled the PC), started, established and admitted. */
    private suspend fun establishedPair(): Pair<PeerConnection, PeerConnection> {
        val (phoneKeys, pcKeys) = TestLinkKeys.sessionPair(phone, pc)
        val (a, b) = wire()
        val p = connection(a, phoneKeys).also { it.start() }
        val q = connection(b, pcKeys).also { it.start() }
        establishPair(p, q)
        return p to q
    }

    @Test
    fun `a connection is established by the peer's first frame and admitted by its owner`() = runBlocking {
        val (p, q) = establishedPair()
        assertTrue(p.isEstablished && q.isEstablished)
        p.close(); q.close()
    }

    @Test
    fun `a recorded companion command replayed on a new connection is not acted on (AC 1-2)`() = runBlocking {
        // Connection 1: the PC sends smsSend. The test records the sealed wire frames.
        val (k1Phone, k1Pc) = TestLinkKeys.sessionPair(phone, pc)
        val pcSide = V3TestPeer(pc).apply { install(k1Pc) }
        val hello = pcSide.seal(PeerMessage.Hello(pc, "PC", "windows"))
        val recorded = pcSide.seal(PeerMessage.SmsSend(clientId = "c-1", addresses = listOf("+15550100"), body = "transfer 500"))

        // Connection 2: the same pairing secret, new ephemeral keys. The recording is replayed.
        val (k2Phone, _) = TestLinkKeys.sessionPair(phone, pc)
        val (a, b) = wire()
        val target = connection(a, k2Phone).also { it.start() }
        val acted = Collections.synchronizedList(mutableListOf<PeerMessage>())
        val watcher = launch(Dispatchers.IO) { target.incoming.collect { acted += it.payload } }
        b.outbox.send(Frame.Text(hello.readText()))
        b.outbox.send(Frame.Text(recorded.readText()))
        withTimeout(5_000) { target.closed.first() }
        assertEquals(PeerConnection.CloseCause.AUTH_FAILED, target.closeCause)
        assertFalse("never established", target.isEstablished)
        assertTrue("nothing from the recording was dispatched: $acted", acted.isEmpty())
        watcher.cancel()
        k1Phone.wipe()
    }

    @Test
    fun `a frame replayed within the connection closes it`() = runBlocking {
        val (k, kPc) = TestLinkKeys.sessionPair(phone, pc)
        val pcSide = V3TestPeer(pc).apply { install(kPc) }
        val (a, b) = wire()
        val target = connection(a, k).also { it.start(); it.admit() }
        val first = pcSide.seal(PeerMessage.Hello(pc, "PC", "windows"))
        b.outbox.send(first)
        assertTrue(target.awaitEstablished())
        b.outbox.send(Frame.Text(first.readText()))
        withTimeout(5_000) { target.closed.first() }
        assertEquals(PeerConnection.CloseCause.AUTH_FAILED, target.closeCause)
    }

    @Test
    fun `a binary frame, a pairing message in session mode and a bad envelope each close the connection`() = runBlocking {
        val cases: List<suspend (End, V3TestPeer) -> Unit> = listOf(
            { end, _ -> end.outbox.send(Frame.Binary(true, byteArrayOf(1, 2, 3))) },
            { end, peer -> end.outbox.send(peer.seal(PeerMessage.PairFail("x"))) },
            { end, peer -> end.outbox.send(rawSealed(peer, """{"v":2,"msgId":"m","from":"$pc","seq":1,"ts":1,"payload":{"t":"ping","nonce":"n"}}""")) },
            { end, peer -> end.outbox.send(rawSealed(peer, """{"v":3,"msgId":"m","from":"$pc","seq":7,"ts":1,"payload":{"t":"ping","nonce":"n"}}""")) },
            { end, peer -> end.outbox.send(rawSealed(peer, """{"v":3,"msgId":"m","from":"OTHER","seq":1,"ts":1,"payload":{"t":"ping","nonce":"n"}}""")) },
            { end, peer -> end.outbox.send(rawSealed(peer, """{"v":3,"msgId":"m","from":"$pc","seq":1,"ts":1,"payload":{"t":"hello"}}""")) },
        )
        for ((i, case) in cases.withIndex()) {
            val (k, kPc) = TestLinkKeys.sessionPair(phone, pc)
            val peer = V3TestPeer(pc).apply { install(kPc) }
            val (a, b) = wire()
            val target = connection(a, k).also { it.start(); it.admit() }
            val acted = Collections.synchronizedList(mutableListOf<PeerMessage>())
            val watcher = launch(Dispatchers.IO) { target.incoming.collect { acted += it.payload } }
            case(b, peer)
            withTimeout(5_000) { target.closed.first() }
            assertEquals("case $i", PeerConnection.CloseCause.PROTOCOL, target.closeCause)
            assertTrue("case $i dispatched nothing", acted.isEmpty())
            watcher.cancel()
        }
    }

    /** Seals arbitrary envelope JSON with the peer's next counter. */
    private fun rawSealed(peer: V3TestPeer, json: String): Frame.Text {
        val keys = peer.keys!!
        val sealer = SessionSealer(keys.sendKey, keys.localId, FrameCrypto.DIR_R2I)
        return Frame.Text(sealer.seal(json))
    }

    @Test
    fun `an authenticated frame of an unknown type is skipped and its counter counts`() = runBlocking {
        val (k, kPc) = TestLinkKeys.sessionPair(phone, pc)
        val (a, b) = wire()
        val target = connection(a, k).also { it.start(); it.admit() }
        val sealer = SessionSealer(kPc.sendKey, pc, FrameCrypto.DIR_R2I)
        b.outbox.send(Frame.Text(sealer.seal("""{"v":3,"msgId":"m","from":"$pc","seq":1,"ts":1,"payload":{"t":"futureThing","x":1}}""")))
        val hello = async(Dispatchers.IO) { target.incoming.first() }
        kotlinx.coroutines.delay(100)
        b.outbox.send(Frame.Text(sealer.seal(PeerMessageJson.codec.encodeToString(LinkEnvelope.serializer(),
            LinkEnvelope(msgId = "m2", from = pc, seq = 2, ts = 2, payload = PeerMessage.Hello(pc, "PC", "windows"))))))
        assertTrue(withTimeout(5_000) { hello.await() }.payload is PeerMessage.Hello)
        assertTrue(target.isConnected)
        target.close()
    }

    @Test
    fun `an oversized control frame fails only that message and the connection goes on (review issue 14)`() = runBlocking {
        val (p, q) = establishedPair()
        val mailbox = q.mailbox("t-1")
        val huge = "a".repeat(PeerConnection.MAX_FRAME_SIZE_BYTES.toInt())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { p.send(PeerMessage.Ping(huge)) } }
        p.send(PeerMessage.TransferProgressNotice("t-1", 1L, 1L))
        assertTrue(withTimeout(5_000) { mailbox.payloadChannel.receive() } is PeerMessage.TransferProgressNotice)
        assertTrue("the peer did not close the connection", q.isConnected && p.isConnected)
        p.close(); q.close()
    }

    @Test
    fun `the size check before sealing predicts the wire length exactly`() {
        val (k, _) = TestLinkKeys.sessionPair(phone, pc)
        val sealer = SessionSealer(k.sendKey, phone, FrameCrypto.DIR_I2R)
        for (n in listOf(0, 1, 2, 3, 1000, 65_537)) {
            val predicted = sealer.wireLength(n)
            assertEquals("plaintext $n", predicted, sealer.seal("x".repeat(n)).toByteArray().size.toLong())
        }
        val counter = sealer.nextCounter
        assertThrows(IllegalArgumentException::class.java) { sealer.seal("x".repeat(100), maxWireBytes = sealer.wireLength(100) - 1) }
        assertEquals("a refused frame spends no counter", counter, sealer.nextCounter)
    }

    @Test
    fun `a write that fails after sealing fails the connection (review issue 1)`() = runBlocking {
        val (phoneKeys, pcKeys) = TestLinkKeys.sessionPair(phone, pc)
        val (a, b) = wire()
        var failNext = false
        var writes = 0
        val flaky = object : SendChannel<Frame> by a.outbox {
            override suspend fun send(element: Frame) {
                if (failNext) {
                    failNext = false
                    throw java.io.IOException("simulated socket failure after sealing")
                }
                if (element is Frame.Text) writes++
                a.outbox.send(element)
            }
        }
        val flakyWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = a.inbox
            override val outgoing: SendChannel<Frame> = flaky
        }
        val p = PeerConnection(flakyWs, phoneKeys, phone, "Test-flaky", Dispatchers.IO, enableHeartbeat = false).also { it.start() }
        val q = connection(b, pcKeys).also { it.start() }
        establishPair(p, q)

        failNext = true
        assertThrows(java.io.IOException::class.java) { runBlocking { p.send(PeerMessage.Ping("lost")) } }
        val before = writes
        assertThrows(java.io.IOException::class.java) { runBlocking { p.send(PeerMessage.Ping("next")) } }
        assertEquals("nothing is written one counter ahead of the peer", before, writes)
        withTimeout(5_000) { p.closed.first() }
        assertFalse(p.isConnected)
        q.close()
    }

    @Test
    fun `the known type set comes from the serializer and includes main's additions`() {
        assertTrue(PeerConnection.KNOWN_PAYLOAD_TYPES.containsAll(setOf("hello", "helloAck", "pairHello", "pairOk", "pairFail", "unpair", "deviceInfo", "ping")))
        assertFalse("futureThing" in PeerConnection.KNOWN_PAYLOAD_TYPES)
    }

    @Test
    fun `the exporter refuses before establishment and after close, and agrees on both ends (AC 13)`() = runBlocking {
        val (k, kPc) = TestLinkKeys.sessionPair(phone, pc)
        val (a, b) = wire()
        val p = connection(a, k).also { it.start() }
        val q = connection(b, kPc).also { it.start() }
        assertThrows(IllegalStateException::class.java) { p.exportKey("veyro-vtc3", ByteArray(0), 32) }
        establishPair(p, q)
        val x = p.exportKey("veyro-vtc3", byteArrayOf(1), 64)
        assertArrayEquals(x, q.exportKey("veyro-vtc3", byteArrayOf(1), 64))
        assertFalse(x.contentEquals(p.exportKey("veyro-vtc3", byteArrayOf(2), 64)))
        assertFalse(x.copyOf(32).contentEquals(p.exportKey("veyro-screen-v3", byteArrayOf(1), 32)))
        assertThrows(IllegalArgumentException::class.java) { p.exportKey("", ByteArray(0), 32) }
        assertThrows(IllegalArgumentException::class.java) { p.exportKey("veyro-vtc3", ByteArray(0), 0) }
        p.close()
        assertThrows(IllegalStateException::class.java) { p.exportKey("veyro-vtc3", ByteArray(0), 32) }
        q.close()
    }

    @Test
    fun `a peer that never sends a first frame is closed with TIMEOUT (FR-C3)`() = runBlocking {
        val (k, _) = TestLinkKeys.sessionPair(phone, pc)
        val (a, _) = wire()
        val target = connection(a, k, firstFrameMs = 300).also { it.start() }
        assertFalse(withTimeout(5_000) { target.awaitEstablished() })
        assertEquals(PeerConnection.CloseCause.TIMEOUT, target.closeCause)
    }

    @Test
    fun `the admission gate fails closed when the owner never admits`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (k, kPc) = TestLinkKeys.sessionPair(phone, pc)
        val (a, b) = wire()
        val target = connection(a, k, dispatcher).also { it.start() }
        val peer = V3TestPeer(pc).apply { install(kPc) }
        b.outbox.send(peer.seal(PeerMessage.Hello(pc, "PC", "windows")))
        b.outbox.send(peer.seal(PeerMessage.DeviceInfo(name = "PC")))
        runCurrent()
        assertTrue(target.isEstablished)
        advanceTimeBy(PeerConnection.REKEY_WAIT_MS + 1_000)
        runCurrent()
        assertFalse(target.isConnected)
        assertEquals(PeerConnection.CloseCause.LOCAL, target.closeCause)
    }

    @Test
    fun `1000 frames from each of two concurrent senders all open in order (AC 20)`() = runBlocking {
        val (p, q) = establishedPair()
        // The mailbox applies back-pressure (the broadcast flow drops on overflow by design).
        val mailbox = q.mailbox("t-1")
        val reader = async(Dispatchers.IO) {
            var n = 0
            while (n < 2000) {
                if (mailbox.payloadChannel.receive() is PeerMessage.TransferProgressNotice) n++
            }
            n
        }
        (1..2).map { s ->
            async(Dispatchers.IO) { repeat(1000) { i -> p.send(PeerMessage.TransferProgressNotice("t-1", i.toLong(), s.toLong())) } }
        }.awaitAll()
        assertEquals(2000, withTimeout(30_000) { reader.await() })
        assertTrue("no frame failed: the connection is still up", q.isConnected)
        p.close(); q.close()
    }
    @Test
    fun `the pairing switch is race free and both ends export the same keys (ADR-056)`() = runBlocking {
        // Confirm-pairing bootstrap keys for both roles from one exchange.
        val eI = EphemeralKey.generate()
        val eR = EphemeralKey.generate()
        val transcript = ConnectionKeySchedule.transcript("p".toByteArray(), "a".toByteArray())
        val staticKey = ByteArray(32) { 3 }
        val nA = ByteArray(32) { 1 }
        val nB = ByteArray(32) { 2 }
        val bootI = ConnectionKeySchedule.pairConfirm(LinkRole.INITIATOR, phone, pc, transcript, nA, nB, eI.agree(eR.publicKey), staticKey)
        val bootR = ConnectionKeySchedule.pairConfirm(LinkRole.RESPONDER, phone, pc, transcript, nA, nB, eR.agree(eI.publicKey), staticKey)
        val (a, b) = wire()
        val initiator = connection(a, bootI).also { it.heartbeatPaused = true; it.start() }
        val responder = connection(b, bootR).also { it.heartbeatPaused = true; it.start() }

        initiator.send(PeerMessage.PairHello(phone, "Phone", "android", "1"))
        assertTrue(responder.receiveHandshake(5_000)?.payload is PeerMessage.PairHello)
        assertThrows(IllegalStateException::class.java) { initiator.exportKey("veyro-vtc3", ByteArray(0), 32) }

        val secret = ByteArray(32) { 9 }
        responder.sendPairOkAndSwitch(PeerMessage.PairOk(pc, "PC", "windows", FrameCrypto.b64(secret)), secret.copyOf())
        // The responder's session hello goes out at once, before the initiator switched.
        responder.send(PeerMessage.Hello(pc, "PC", "windows"))
        val ok = initiator.receiveHandshake(5_000)?.payload as PeerMessage.PairOk
        initiator.switchToSession(FrameCrypto.decodeStrictBase64(ok.sessionSecretB64)!!)
        initiator.send(PeerMessage.Hello(phone, "Phone", "android"))
        assertTrue(withTimeout(5_000) { initiator.awaitEstablished() } && withTimeout(5_000) { responder.awaitEstablished() })
        initiator.admit(); responder.admit()
        assertArrayEquals(initiator.exportKey("veyro-vtc3", byteArrayOf(7), 32), responder.exportKey("veyro-vtc3", byteArrayOf(7), 32))
        assertTrue(initiator.isConnected && responder.isConnected)
        initiator.close(); responder.close()
    }

    @Test
    fun `the pairing responder accepts pairHello only once and only first`() = runBlocking {
        val eI = EphemeralKey.generate()
        val eR = EphemeralKey.generate()
        val transcript = ConnectionKeySchedule.transcript("p".toByteArray(), "a".toByteArray())
        val bootI = ConnectionKeySchedule.pairConfirm(LinkRole.INITIATOR, phone, pc, transcript, ByteArray(32), ByteArray(32), eI.agree(eR.publicKey), ByteArray(32) { 4 })
        val bootR = ConnectionKeySchedule.pairConfirm(LinkRole.RESPONDER, phone, pc, transcript, ByteArray(32), ByteArray(32), eR.agree(eI.publicKey), ByteArray(32) { 4 })
        val (a, b) = wire()
        val responder = connection(b, bootR).also { it.start() }
        val peer = V3TestPeer(phone).apply { install(bootI) }
        a.outbox.send(peer.seal(PeerMessage.Ping("first")))
        withTimeout(5_000) { responder.closed.first() }
        assertEquals("a ping before pairHello is not allowed", PeerConnection.CloseCause.PROTOCOL, responder.closeCause)
    }
}
