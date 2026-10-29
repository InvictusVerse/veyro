package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Platform
import com.veyro.core.model.Transfer
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferRoute
import com.veyro.core.model.TransferStage
import com.veyro.core.model.TransferState
import com.veyro.core.model.TrustedPeer
import com.veyro.core.protocol.tcp.TcpFrame
import com.veyro.core.protocol.tcp.TcpFrameCodec
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * ADR-055: a Cancel reaches a send at every stage, and a cancel on one side reads as "cancelled"
 * on the other even when the closed data socket arrives before the Cancel message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class TransferCancelTest {

    @get:Rule
    public val tempDir: TemporaryFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = testDispatcher
        override val io = Dispatchers.IO
        override val main = testDispatcher
    }

    private companion object {
        const val CHUNK = 512 * 1024
        const val SENDER_ID = "sender-dev"
        const val RECEIVER_ID = "receiver-dev"
    }

    /** Two sessions wired to each other: `first` is the sender's, `second` the receiver's. */
    private fun connectedPair(): Pair<PeerConnection, PeerConnection> {
        val key = FrameCrypto.sessionKeyFromBytes(ByteArray(32) { 42 })
        val toReceiver = Channel<Frame>(Channel.UNLIMITED)
        val toSender = Channel<Frame>(Channel.UNLIMITED)
        val senderWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = toSender
            override val outgoing: SendChannel<Frame> = toReceiver
        }
        val receiverWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = toReceiver
            override val outgoing: SendChannel<Frame> = toSender
        }
        fun session(ws: TestWebSocketSession, local: String, peer: String, tag: String) = PeerConnection(
            ws = ws,
            initialKey = key,
            initialEncrypt = true,
            localDeviceId = local,
            tag = tag,
            dispatcher = testDispatcher,
            peerDeviceId = peer,
            enableHeartbeat = false,
            remoteHost = "127.0.0.1",
            localHost = "127.0.0.1",
        )
        val sender = session(senderWs, SENDER_ID, RECEIVER_ID, "CancelSender").apply { start() }
        val receiver = session(receiverWs, RECEIVER_ID, SENDER_ID, "CancelReceiver").apply { start() }
        return sender to receiver
    }

    /**
     * Plays a sender by hand: the offer on the session, then the data socket with one chunk of
     * two. Returns the open socket; closing it is the sender's half of a cancel.
     */
    private suspend fun offerOneChunk(sender: PeerConnection, tid: String): Socket = withContext(Dispatchers.IO) {
        val size = 2L * CHUNK
        val mailbox = sender.mailbox(tid)
        sender.send(PeerMessage.TransferOffer(tid, listOf(PeerMessage.Item(0, "cancel.bin", size, 0L)), size, "Sender", listOf("LAN_TCP")))
        val decision = withTimeout(10_000) { mailbox.awaitDecision(10_000) }!!
        assertTrue(decision.accept)
        val socket = Socket("127.0.0.1", decision.tcpPort!!)
        val out = BufferedOutputStream(socket.getOutputStream())
        TcpFrameCodec.writeFrame(
            out,
            TcpFrame.Auth(seq = 1L, transferId = tid, authToken = decision.authToken!!, senderDeviceId = SENDER_ID, route = TransferRoute.LAN_TCP),
        )
        out.flush()
        assertTrue((TcpFrameCodec.readFrame(BufferedInputStream(socket.getInputStream())) as TcpFrame.AuthAck).ok)
        TcpFrameCodec.writeFrame(out, TcpFrame.ItemBegin(2L, tid, 0, size, 2, CHUNK))
        TcpFrameCodec.writeFrame(out, TcpFrame.Chunk(3L, tid, 0, 0, ByteArray(CHUNK) { 7 }))
        out.flush()
        socket
    }

    @Test
    fun `a sender cancel whose socket closes before its Cancel arrives ends as cancelled`() = runTest(testDispatcher) {
        val (sender, receiver) = connectedPair()
        val stages = Collections.synchronizedList(mutableListOf<TransferStage>())
        val dest = tempDir.newFolder("dest")
        val runner = TransferRunner(dispatchers)
        val receive = async(Dispatchers.IO) {
            runCatching {
                runner.receive(session = receiver, destinationDir = dest, onProgress = { _, _, _, stage, _, _ -> stages += stage })
            }
        }

        offerOneChunk(sender, "race-rx").close()
        withContext(Dispatchers.IO) {
            Thread.sleep(300) // the Cancel follows the closed socket, as it does on a busy phone
            sender.send(PeerMessage.Cancel("race-rx", "cancelled"))
        }

        val outcome = withContext(Dispatchers.IO) { withTimeout(15_000) { receive.await() } }
        assertTrue("a cancel, not a failure: ${outcome.exceptionOrNull()}", outcome.isSuccess)
        assertEquals(TransferStage.CANCELLED, stages.last())
        assertTrue("the half-received file is gone", dest.walkTopDown().none { it.isFile })
        sender.close()
        receiver.close()
    }

    @Test
    fun `a socket that breaks with no Cancel is still a failure`() = runTest(testDispatcher) {
        val (sender, receiver) = connectedPair()
        val stages = Collections.synchronizedList(mutableListOf<TransferStage>())
        val runner = TransferRunner(dispatchers)
        val receive = async(Dispatchers.IO) {
            runCatching {
                runner.receive(session = receiver, destinationDir = tempDir.newFolder("dest"), onProgress = { _, _, _, stage, _, _ -> stages += stage })
            }
        }

        offerOneChunk(sender, "race-broken").close()

        val outcome = withContext(Dispatchers.IO) { withTimeout(15_000) { receive.await() } }
        assertTrue("an honest failure", outcome.isFailure)
        assertEquals(TransferStage.FAILED, stages.last())
        sender.close()
        receiver.close()
    }

    @Test
    fun `a receiver cancel whose socket closes before its Cancel arrives ends the send as cancelled`() = runTest(testDispatcher) {
        val (sender, receiver) = connectedPair()
        val tid = "race-tx"
        val source = tempDir.newFile("big.bin").apply { writeBytes(ByteArray(16 * 1024 * 1024) { (it % 251).toByte() }) }
        val item = TransferItem(0, "big.bin", source.length(), 0L, isDirectory = false)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val runner = TransferRunner(dispatchers)
        val send = async(Dispatchers.IO) {
            runner.send(session = sender, items = listOf(item), senderName = "Sender", transferId = tid, resolveFile = { source })
        }

        withContext(Dispatchers.IO) {
            withTimeout(10_000) { receiver.receiveOffer() }
            receiver.send(
                PeerMessage.TransferDecision(
                    transferId = tid,
                    accept = true,
                    route = "LAN_TCP",
                    tcpHost = "127.0.0.1",
                    tcpPort = server.localPort,
                    fallbackHost = "127.0.0.1",
                    authToken = "token",
                ),
            )
            server.accept().use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val out = BufferedOutputStream(socket.getOutputStream())
                assertTrue(TcpFrameCodec.readFrame(input) is TcpFrame.Auth)
                TcpFrameCodec.writeFrame(out, TcpFrame.AuthAck(1L, tid, ok = true))
                out.flush()
                TcpFrameCodec.readFrame(input) // ItemBegin
                TcpFrameCodec.readFrame(input) // the first chunk; then the receiver cancels
            }
            server.close()
            Thread.sleep(300)
            receiver.send(PeerMessage.Cancel(tid, "User cancelled"))
        }

        val outcome = withContext(Dispatchers.IO) { withTimeout(15_000) { send.await() } }
        assertEquals(TransferRunner.SendOutcome.Cancelled, outcome)
        sender.close()
        receiver.close()
    }

    // ── Cancel before the runner has the send ──────────────────────────────────────────────

    private val peerId = DeviceId.fromPublicKey(ByteArray(32) { 2 })

    private fun coordinator(
        staging: java.io.File,
        history: MutableList<Transfer>,
        discover: suspend (DeviceId, Long) -> List<com.veyro.core.model.Endpoint> = { _, _ -> emptyList() },
    ): TransferCoordinator {
        val localPk = ByteArray(32) { 1 }
        val trusted = TrustedPeer(
            deviceId = peerId,
            staticPublicKey = ByteArray(32) { 2 },
            pairingSecret = ByteArray(32) { 3 },
            name = "Desk PC",
            platform = Platform.WINDOWS,
            pairedAtEpochMillis = 1L,
        )
        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { DeviceId.fromPublicKey(localPk).value },
            localDeviceName = { "Phone" },
            localPublicKey = { localPk },
            trustedPeer = { trusted },
            saveTrustedPeer = {},
            client = object : ConnectionClient() {},
        )
        return TransferCoordinator(
            connectionManager = manager,
            runner = TransferRunner(dispatchers),
            dispatchers = dispatchers,
            stagingDir = staging,
            destinationProvider = { tempDir.newFolder() },
            localName = { "Phone" },
            endpointsFor = { emptyList() },
            autoAcceptFor = { true },
            discoverEndpoints = discover,
            onTransferCompleted = { history += it },
        )
    }

    /** A shared video that never ends; reading it is staging. */
    private class EndlessStream(private val reading: CompletableDeferred<Unit>) : InputStream() {
        override fun read(): Int = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reading.complete(Unit)
            Thread.sleep(2)
            return minOf(len, 64 * 1024)
        }
    }

    @Test
    fun `cancel while staging stops the send and deletes the staged copy`() = runTest(testDispatcher) {
        val staging = tempDir.newFolder("staging")
        val history = Collections.synchronizedList(mutableListOf<Transfer>())
        val coordinator = coordinator(staging, history)
        val reading = CompletableDeferred<Unit>()
        val video = OutgoingFile("video.mp4", 4L shl 30, 0L) { EndlessStream(reading) }

        val result = withContext(Dispatchers.IO) {
            val send = async { coordinator.sendTo(peerId, "Desk PC", listOf(video)) }
            withTimeout(10_000) { reading.await() }
            coordinator.cancel()
            withTimeout(10_000) { send.await() }
        }

        assertEquals(SendResult.Cancelled, result)
        assertEquals(TransferStage.CANCELLED, coordinator.activeTransfer.value?.stage)
        assertEquals(listOf(TransferState.CANCELLED), history.map { it.state })
        assertTrue("the half-staged copy is deleted", staging.walkTopDown().none { it.isFile })
    }

    @Test
    fun `cancel while connecting stops the send`() = runTest(testDispatcher) {
        val history = Collections.synchronizedList(mutableListOf<Transfer>())
        val looking = CompletableDeferred<Unit>()
        val coordinator = coordinator(tempDir.newFolder("staging"), history) { _, _ ->
            looking.complete(Unit)
            awaitCancellation()
        }
        val photo = OutgoingFile("photo.jpg", 3L, 0L) { "abc".byteInputStream() }

        val result = withContext(Dispatchers.IO) {
            val send = async { coordinator.sendTo(peerId, "Desk PC", listOf(photo)) }
            withTimeout(10_000) { looking.await() }
            coordinator.cancel()
            withTimeout(10_000) { send.await() }
        }

        assertEquals(SendResult.Cancelled, result)
        assertEquals(listOf(TransferState.CANCELLED), history.map { it.state })
    }
}
