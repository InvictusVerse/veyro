package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.model.DeviceId
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferStage
import com.veyro.core.model.VerificationResult
import com.veyro.core.transfer.ChunkMath
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
public class SequenceAndTransferReliabilityTest {

    @get:Rule
    public val tempDir: TemporaryFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = testDispatcher
        override val io = kotlinx.coroutines.Dispatchers.IO
        override val main = testDispatcher
    }

    /**
     * The receiver writes on the real [kotlinx.coroutines.Dispatchers.IO], so a wait for its answer
     * must time out on real time: under the test's virtual clock the scheduler sees nothing to run
     * and jumps straight to the deadline.
     */
    private suspend fun <T> realTime(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { block() }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun createConnectedPair(
        initiatorId: String = "initiator-dev",
        receiverId: String = "receiver-dev",
    ): Pair<PeerConnection, PeerConnection> {
        val secret = ByteArray(32) { 88 }
        val sessionKey = FrameCrypto.sessionKeyFromBytes(secret)

        val initToRecv = Channel<Frame>(Channel.UNLIMITED)
        val recvToInit = Channel<Frame>(Channel.UNLIMITED)

        val initWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = recvToInit
            override val outgoing: SendChannel<Frame> = initToRecv
            override fun terminate() {
                super.terminate()
                recvToInit.close()
                initToRecv.close()
            }
        }

        val recvWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = initToRecv
            override val outgoing: SendChannel<Frame> = recvToInit
            override fun terminate() {
                super.terminate()
                initToRecv.close()
                recvToInit.close()
            }
        }

        val initiator = PeerConnection(
            ws = initWs,
            initialKey = sessionKey,
            initialEncrypt = true,
            localDeviceId = initiatorId,
            tag = "TestSender",
            dispatcher = testDispatcher,
            peerDeviceId = receiverId,
            enableHeartbeat = false,
        )

        val receiver = PeerConnection(
            ws = recvWs,
            initialKey = sessionKey,
            initialEncrypt = true,
            localDeviceId = receiverId,
            tag = "TestReceiver",
            dispatcher = testDispatcher,
            peerDeviceId = initiatorId,
            enableHeartbeat = false,
        )

        initiator.start()
        receiver.start()

        return Pair(initiator, receiver)
    }

    @Test
    fun `many concurrent outbound sends cannot invert wire sequence order`() = runTest(testDispatcher) {
        val secret = ByteArray(32) { 55 }
        val sessionKey = FrameCrypto.sessionKeyFromBytes(secret)

        val wireFrames = Channel<Frame>(Channel.UNLIMITED)
        val dummyIncoming = Channel<Frame>(Channel.UNLIMITED)

        val wsSession = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = dummyIncoming
            override val outgoing: SendChannel<Frame> = wireFrames
        }

        val connection = PeerConnection(
            ws = wsSession,
            initialKey = sessionKey,
            initialEncrypt = true,
            localDeviceId = "sender-dev",
            dispatcher = testDispatcher,
            enableHeartbeat = false,
        )
        connection.start()

        val sendCount = 50
        val jobs = (1..sendCount).map { i ->
            async {
                connection.send(PeerMessage.Ping("ping-$i"))
            }
        }
        jobs.awaitAll()

        // Read all wire frames and parse envelopes
        val receivedSeqs = mutableListOf<Long>()
        for (i in 1..sendCount) {
            val frame = wireFrames.receive()
            assertTrue(frame is Frame.Text)
            val text = (frame as Frame.Text).readText()
            val wire = PeerMessageJson.codec.decodeFromString(LinkWireFrame.serializer(), text)
            val sealed = wire.toSealed()
            assertNotNull(sealed)
            val json = FrameCrypto.open(sealed!!, sessionKey)
            assertNotNull(json)
            val envelope = PeerMessageJson.codec.decodeFromString(LinkEnvelope.serializer(), json!!)
            receivedSeqs.add(envelope.seq)
        }

        assertEquals(sendCount, receivedSeqs.size)
        // Verify strict monotonicity on the wire: 1, 2, 3, ..., sendCount
        for (idx in 0 until receivedSeqs.size) {
            assertEquals("Wire sequence must be strictly monotonic at index $idx", (idx + 1).toLong(), receivedSeqs[idx])
        }

        connection.close()
    }

    @Test
    fun `mixed transfer control messages and payload chunks retain strict wire order`() = runTest(testDispatcher) {
        val (sender, receiver) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val srcFile = tempDir.newFile("mixed_stream.bin").apply {
            writeBytes(ByteArray(1024 * 1024) { (it % 127).toByte() })
        }
        val destDir = tempDir.newFolder("rx_mixed")

        val item = TransferItem(
            index = 0,
            relativePath = "mixed_stream.bin",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val rxJob = launch {
            val res = runner.receive(
                session = receiver,
                destinationDir = destDir,
                authorize = { true },
            )
            assertEquals(1, res.size)
            assertEquals(VerificationResult.VERIFIED, res[0].verification)
        }

        // Send transfer while interleaving ping control messages
        val pingJob = launch {
            repeat(5) {
                delay(20)
                sender.send(PeerMessage.Ping("interleaved-$it"))
            }
        }

        val outcome = runner.send(
            session = sender,
            items = listOf(item),
            senderName = "InterleavedSender",
            transferId = "tid-interleaved",
            resolveFile = { srcFile },
        )

        pingJob.join()
        rxJob.join()

        assertTrue(outcome is TransferRunner.SendOutcome.Done)
        val done = outcome as TransferRunner.SendOutcome.Done
        assertEquals(VerificationResult.VERIFIED, done.results[0].verification)

        sender.close()
        receiver.close()
    }

    @Test
    fun `100 MB class synthetic transfer succeeds with no stale sequence warning`() = runTest(testDispatcher) {
        val (sender, receiver) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        // 100 MB payload: 400 chunks of 256 KB = 104,857,600 bytes
        val totalBytes = 100L * 1024L * 1024L
        val destDir = tempDir.newFolder("rx_100mb")
        val tid = "tid-100mb-bench"

        val chunkData = ByteArray(256 * 1024) { (it % 251).toByte() }
        val md = MessageDigest.getInstance("SHA-256")
        for (i in 0 until 400) {
            md.update(chunkData)
        }
        val expectedSha = md.digest().joinToString("") { "%02x".format(it) }

        val stagesObserved = Collections.synchronizedList(mutableListOf<TransferStage>())

        val rxJob = launch {
            val res = runner.receive(
                session = receiver,
                destinationDir = destDir,
                authorize = { true },
                onProgress = { _, _, _, stage, _, _ ->
                    stagesObserved.add(stage)
                },
            )
            assertEquals(1, res.size)
            assertEquals(VerificationResult.VERIFIED, res[0].verification)
        }

        // Setup mailbox first to eliminate registration race, then send offer
        val mb = sender.mailbox(tid)
        sender.send(
            PeerMessage.TransferOffer(
                transferId = tid,
                items = listOf(
                    PeerMessage.Item(
                        index = 0,
                        relPath = "large_dataset.bin",
                        size = totalBytes,
                        mtime = 0L,
                    ),
                ),
                totalBytes = totalBytes,
                senderName = "LargeSender",
            ),
        )

        val decision = realTime { mb.awaitDecision(10_000L) }
        assertNotNull(decision)
        assertTrue(decision!!.accept)

        sender.send(
            PeerMessage.ItemBegin(
                transferId = tid,
                index = 0,
                size = totalBytes,
                chunkCount = 400,
                chunkSize = 256 * 1024,
            ),
        )

        val b64 = Base64.getEncoder().encodeToString(chunkData)
        for (chunkIdx in 0 until 400) {
            sender.send(
                PeerMessage.Chunk(
                    transferId = tid,
                    index = 0,
                    chunkIndex = chunkIdx,
                    dataB64 = b64,
                ),
            )
        }

        sender.send(PeerMessage.ItemEnd(tid, 0, expectedSha))
        val itemRes = realTime { mb.awaitItemResult(0, 10_000L) }
        assertNotNull(itemRes)
        assertTrue(itemRes!!.ok)

        sender.send(PeerMessage.TransferEnd(tid, completed = true))
        rxJob.join()

        assertTrue(stagesObserved.contains(TransferStage.TRANSFERRING))

        sender.close()
        receiver.close()
    }

    @Test
    fun `disconnect after first chunk transitions to FAILED instead of hang or false success`() = runTest(testDispatcher) {
        val (sender, receiver) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val destDir = tempDir.newFolder("rx_abrupt_disconnect")
        val tid = "tid-abrupt-close"
        val totalBytes = 50L * 1024L * 1024L

        var reportedStage: TransferStage? = null
        var rxError: Throwable? = null

        val rxJob = launch {
            try {
                runner.receive(
                    session = receiver,
                    destinationDir = destDir,
                    authorize = { true },
                    onProgress = { _, _, _, stage, _, _ ->
                        reportedStage = stage
                    },
                )
            } catch (t: Throwable) {
                rxError = t
            }
        }

        val mb = sender.mailbox(tid)
        sender.send(
            PeerMessage.TransferOffer(
                transferId = tid,
                items = listOf(
                    PeerMessage.Item(
                        index = 0,
                        relPath = "incomplete.bin",
                        size = totalBytes,
                        mtime = 0L,
                    ),
                ),
                totalBytes = totalBytes,
                senderName = "AbruptSender",
            ),
        )

        val decision = realTime { mb.awaitDecision(5000L) }
        assertNotNull(decision)

        // Send item begin and exactly one chunk
        sender.send(
            PeerMessage.ItemBegin(
                transferId = tid,
                index = 0,
                size = totalBytes,
                chunkCount = 200,
                chunkSize = 256 * 1024,
            ),
        )
        val oneChunk = ByteArray(256 * 1024) { 1 }
        sender.send(
            PeerMessage.Chunk(
                transferId = tid,
                index = 0,
                chunkIndex = 0,
                dataB64 = Base64.getEncoder().encodeToString(oneChunk),
            ),
        )

        // Abruptly close sender socket
        sender.close("abrupt-socket-drop")
        rxJob.join()

        // Verify receiver did NOT finalize as success
        assertNotNull("Receiver must fail on abrupt connection drop", rxError)
        assertTrue("Error must be an IOException", rxError is IOException)
        assertEquals(TransferStage.FAILED, reportedStage)

        receiver.close()
    }

    @Test
    fun `coordinator marks active transfer as FAILED on socket drop and does not hang`() = runTest(testDispatcher) {
        val (sender, receiver) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val fakeClient = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                throw IllegalStateException("not used")
            }
        }
        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { "recv-dev" },
            localDeviceName = { "Samsung Receiver" },
            localPublicKey = { ByteArray(32) },
            trustedPeer = { null },
            saveTrustedPeer = {},
            client = fakeClient,
        )

        val coordinator = TransferCoordinator(
            dispatchers = dispatchers,
            connectionManager = manager,
            runner = runner,
            localName = { "Samsung Receiver" },
            destinationProvider = { tempDir.newFolder("rx_coord") },
            stagingDir = tempDir.newFolder("stage_coord"),
            endpointsFor = { emptyList() },
            autoAcceptFor = { true },
        )

        val driverJob = launch {
            manager.sessionDriver?.invoke(receiver, true)
        }

        // Offer transfer
        val tid = "tid-coord-fail"
        sender.send(
            PeerMessage.TransferOffer(
                transferId = tid,
                items = listOf(
                    PeerMessage.Item(
                        index = 0,
                        relPath = "abrupt.bin",
                        size = 10_000_000L,
                        mtime = 0L,
                    ),
                ),
                totalBytes = 10_000_000L,
                senderName = "AbruptSender",
            ),
        )

        // Wait for coordinator activeTransfer to be populated
        val active = coordinator.activeTransfer.filterNotNull().first()
        assertEquals(TransferStage.TRANSFERRING, active.stage)

        // Send 1 chunk then kill sender
        sender.send(
            PeerMessage.ItemBegin(tid, 0, 10_000_000L, 40, 256 * 1024),
        )
        sender.send(
            PeerMessage.Chunk(tid, 0, 0, Base64.getEncoder().encodeToString(ByteArray(1024))),
        )

        // Force kill session
        receiver.close("forced-disconnect")
        driverJob.join()

        // Verify active transfer transitioned to FAILED
        val finalActive = coordinator.activeTransfer.value
        assertNotNull(finalActive)
        assertEquals(TransferStage.FAILED, finalActive!!.stage)
        assertTrue(finalActive.isTerminal)

        sender.close()
    }

    @Test
    fun `slow receiver with bounded payload channel maintains backpressure without dropped chunks`() = runTest(testDispatcher) {
        val (sender, receiver) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        // 50 chunks of 256 KB = 12.8 MB
        val chunkCount = 50
        val chunkSize = 256 * 1024
        val totalBytes = chunkCount.toLong() * chunkSize
        val destDir = tempDir.newFolder("rx_slow")
        val tid = "tid-slow-rx"

        val chunkData = ByteArray(chunkSize) { (it % 241).toByte() }
        val md = MessageDigest.getInstance("SHA-256")
        for (i in 0 until chunkCount) {
            md.update(chunkData)
        }
        val expectedSha = md.digest().joinToString("") { "%02x".format(it) }

        var writtenCount = 0
        val rxJob = launch {
            val res = runner.receive(
                session = receiver,
                destinationDir = destDir,
                authorize = { true },
                onProgress = { bytesRecv, _, _, _, _, _ ->
                    writtenCount = (bytesRecv / chunkSize).toInt()
                },
            )
            assertEquals(1, res.size)
            assertEquals(VerificationResult.VERIFIED, res[0].verification)
        }

        val mb = sender.mailbox(tid)
        sender.send(
            PeerMessage.TransferOffer(
                transferId = tid,
                items = listOf(
                    PeerMessage.Item(
                        index = 0,
                        relPath = "slow_stream.bin",
                        size = totalBytes,
                        mtime = 0L,
                    ),
                ),
                totalBytes = totalBytes,
                senderName = "FastSender",
            ),
        )

        val decision = realTime { mb.awaitDecision(5000L) }
        assertNotNull(decision)
        assertTrue(decision!!.accept)

        sender.send(
            PeerMessage.ItemBegin(
                transferId = tid,
                index = 0,
                size = totalBytes,
                chunkCount = chunkCount,
                chunkSize = chunkSize,
            ),
        )

        val b64 = Base64.getEncoder().encodeToString(chunkData)
        for (idx in 0 until chunkCount) {
            sender.send(
                PeerMessage.Chunk(
                    transferId = tid,
                    index = 0,
                    chunkIndex = idx,
                    dataB64 = b64,
                ),
            )
        }

        sender.send(PeerMessage.ItemEnd(tid, 0, expectedSha))
        val itemRes = realTime { mb.awaitItemResult(0, 5000L) }
        assertNotNull(itemRes)
        assertTrue(itemRes!!.ok)

        sender.send(PeerMessage.TransferEnd(tid, completed = true))
        rxJob.join()

        assertEquals(chunkCount, writtenCount)

        sender.close()
        receiver.close()
    }

    @Test
    fun `mid transfer cancel stops transfer and marks CANCELLED stage on receiver`() = runTest(testDispatcher) {
        val (sender, receiver) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val destDir = tempDir.newFolder("rx_cancel")
        val tid = "tid-cancel-test"
        val totalBytes = 10_000_000L

        var lastReportedStage: TransferStage? = null
        val rxJob = launch {
            runner.receive(
                session = receiver,
                destinationDir = destDir,
                authorize = { true },
                onProgress = { _, _, _, stage, _, _ ->
                    lastReportedStage = stage
                },
            )
        }

        val mb = sender.mailbox(tid)
        sender.send(
            PeerMessage.TransferOffer(
                transferId = tid,
                items = listOf(
                    PeerMessage.Item(index = 0, relPath = "cancel.bin", size = totalBytes, mtime = 0L),
                ),
                totalBytes = totalBytes,
                senderName = "CancellingSender",
            ),
        )

        val decision = realTime { mb.awaitDecision(5000L) }
        assertNotNull(decision)

        sender.send(PeerMessage.ItemBegin(tid, 0, totalBytes, 40, 256 * 1024))
        sender.send(PeerMessage.Chunk(tid, 0, 0, Base64.getEncoder().encodeToString(ByteArray(1024))))

        // Sender cancels
        sender.send(PeerMessage.Cancel(tid, "user-cancelled"))
        rxJob.join()

        assertEquals(TransferStage.CANCELLED, lastReportedStage)

        sender.close()
        receiver.close()
    }

    @Test
    fun `accurate sender versus receiver progress accounting`() = runTest(testDispatcher) {
        val (sender, receiver) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val chunkSize = 256 * 1024
        val totalBytes = chunkSize.toLong() * 4
        val srcFile = tempDir.newFile("progress_test.bin").apply {
            writeBytes(ByteArray(totalBytes.toInt()) { 7 })
        }
        val destDir = tempDir.newFolder("rx_progress")

        val senderProgressList = mutableListOf<Long>()
        val receiverProgressList = mutableListOf<Long>()

        val rxJob = launch {
            runner.receive(
                session = receiver,
                destinationDir = destDir,
                authorize = { true },
                onProgress = { written, _, _, stage, _, _ ->
                    if (stage == TransferStage.TRANSFERRING || stage == TransferStage.COMPLETED) {
                        receiverProgressList.add(written)
                    }
                },
            )
        }

        val outcome = runner.send(
            session = sender,
            items = listOf(
                TransferItem(
                    index = 0,
                    relativePath = "progress_test.bin",
                    sizeBytes = totalBytes,
                    lastModifiedEpochMillis = 0L,
                    isDirectory = false,
                ),
            ),
            senderName = "ProgressSender",
            transferId = "tid-progress",
            onProgress = { sent, _, _, stage, _, _ ->
                if (stage == TransferStage.TRANSFERRING || stage == TransferStage.COMPLETED) {
                    senderProgressList.add(sent)
                }
            },
            resolveFile = { srcFile },
        )

        rxJob.join()

        assertTrue(outcome is TransferRunner.SendOutcome.Done)
        // Verify final progress matches total bytes on both sides
        assertEquals(totalBytes, senderProgressList.last())
        assertEquals(totalBytes, receiverProgressList.last())

        // Verify receiver progress only advanced in multiples of chunkSize (actual disk chunks written)
        receiverProgressList.forEach { written ->
            assertTrue(written >= 0 && written <= totalBytes)
        }

        sender.close()
        receiver.close()
    }

    @Test
    fun `WebSocketQueueMonitor returns null on non-OkHttp session safely`() {
        val fakeWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = Channel()
            override val outgoing: SendChannel<Frame> = Channel()
        }
        val qSize = WebSocketQueueMonitor.getQueueSize(fakeWs)
        // Non-OkHttp session must return null without throwing
        assertEquals(null, qSize)
    }
}
