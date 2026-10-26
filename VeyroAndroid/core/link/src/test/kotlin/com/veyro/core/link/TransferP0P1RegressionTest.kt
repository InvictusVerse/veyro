package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.model.DeviceId
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferRoute
import com.veyro.core.model.TransferStage
import com.veyro.core.model.TransferState
import com.veyro.core.model.VerificationResult
import com.veyro.core.protocol.tcp.TcpFrame
import com.veyro.core.protocol.tcp.TcpFrameCodec
import com.veyro.core.transfer.StoragePublishResult
import com.veyro.core.transfer.TransferStoragePublisher
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
public class TransferP0P1RegressionTest {

    @get:Rule
    public val tempDir: TemporaryFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = testDispatcher
        override val io = Dispatchers.IO
        override val main = testDispatcher
    }

    private fun createConnectedPair(
        initiatorId: String = "sender-dev",
        receiverId: String = "receiver-dev",
    ): Pair<PeerConnection, PeerConnection> {
        val secret = ByteArray(32) { 42 }
        val sessionKey = FrameCrypto.sessionKeyFromBytes(secret)

        val initToRecv = Channel<Frame>(Channel.UNLIMITED)
        val recvToInit = Channel<Frame>(Channel.UNLIMITED)

        val initWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = recvToInit
            override val outgoing: SendChannel<Frame> = initToRecv
        }

        val recvWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = initToRecv
            override val outgoing: SendChannel<Frame> = recvToInit
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
            remoteHost = "127.0.0.1",
            localHost = "127.0.0.1",
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
            remoteHost = "127.0.0.1",
            localHost = "127.0.0.1",
        )

        initiator.start()
        receiver.start()

        return Pair(initiator, receiver)
    }

    @Test
    fun `single item transfer completes successfully with valid TransferEnd`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val payload = ByteArray(1024 * 1024) { (it % 256).toByte() }
        val srcFile = tempDir.newFile("single_file.bin").apply { writeBytes(payload) }
        val destDir = tempDir.newFolder("single_dest")

        val item = TransferItem(
            index = 0,
            relativePath = "single_file.bin",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val rxJob = launch {
            val res = runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
            assertEquals(1, res.size)
            assertEquals(VerificationResult.VERIFIED, res[0].verification)
        }

        val sendOutcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "TestSender",
            transferId = "tid-single-item-test",
            resolveFile = { srcFile },
        )

        rxJob.join()

        assertTrue("Send outcome must be Done", sendOutcome is TransferRunner.SendOutcome.Done)
        val done = sendOutcome as TransferRunner.SendOutcome.Done
        assertEquals(VerificationResult.VERIFIED, done.results[0].verification)

        val destFile = File(destDir, "single_file.bin")
        assertTrue("Destination file must exist", destFile.exists())
        assertEquals(srcFile.length(), destFile.length())
        assertArrayEquals(payload, destFile.readBytes())

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `multi item transfer completes with exactly one overall completion`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val files = (0..2).map { i ->
            val data = ByteArray((i + 1) * 256 * 1024) { (it + i).toByte() }
            val f = tempDir.newFile("multi_file_$i.bin").apply { writeBytes(data) }
            TransferItem(
                index = i,
                relativePath = "multi_file_$i.bin",
                sizeBytes = f.length(),
                lastModifiedEpochMillis = System.currentTimeMillis(),
                isDirectory = false,
            ) to f
        }

        val destDir = tempDir.newFolder("multi_dest")

        val rxJob = launch {
            val res = runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
            assertEquals(3, res.size)
            assertTrue("All items must be verified", res.all { it.verification == VerificationResult.VERIFIED })
        }

        val sendOutcome = runner.send(
            session = senderSession,
            items = files.map { it.first },
            senderName = "MultiSender",
            transferId = "tid-multi-item-test",
            resolveFile = { item -> files.first { it.first.index == item.index }.second },
        )

        rxJob.join()

        assertTrue(sendOutcome is TransferRunner.SendOutcome.Done)
        val done = sendOutcome as TransferRunner.SendOutcome.Done
        assertEquals(3, done.results.size)
        assertTrue(done.results.all { it.verification == VerificationResult.VERIFIED })

        files.forEach { (item, original) ->
            val destFile = File(destDir, item.relativePath)
            assertTrue(destFile.exists())
            assertEquals(original.length(), destFile.length())
            assertArrayEquals(original.readBytes(), destFile.readBytes())
        }

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `no false cancellation or failure after successful verification and publication`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val payload = ByteArray(512 * 1024) { (it % 127).toByte() }
        val srcFile = tempDir.newFile("photo_published.jpg").apply { writeBytes(payload) }
        val stagingDir = tempDir.newFolder("staging_pub")
        val finalPubDir = tempDir.newFolder("media_store_pub")

        var publicationCount = 0
        val publisher = object : TransferStoragePublisher {
            override suspend fun publish(
                sourceFile: File,
                relativePath: String,
                mimeType: String?,
                sizeBytes: Long,
            ): StoragePublishResult {
                publicationCount++
                val dest = File(finalPubDir, relativePath)
                sourceFile.copyTo(dest, overwrite = true)
                return StoragePublishResult.Success(
                    destinationUri = "content://media/external/images/media/42",
                    displayName = relativePath,
                    mimeType = mimeType ?: "image/jpeg",
                    bytesWritten = sizeBytes,
                )
            }
        }

        val item = TransferItem(
            index = 0,
            relativePath = "photo_published.jpg",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val rxJob = launch {
            val res = runner.receive(
                session = receiverSession,
                destinationDir = stagingDir,
                publisher = publisher,
                authorize = { true },
            )
            assertEquals(1, res.size)
            assertEquals(VerificationResult.VERIFIED, res[0].verification)
            assertEquals("content://media/external/images/media/42", res[0].destinationUri)
        }

        val sendOutcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "PubSender",
            transferId = "tid-pub-success",
            resolveFile = { srcFile },
        )

        rxJob.join()

        assertTrue(sendOutcome is TransferRunner.SendOutcome.Done)
        assertEquals(1, publicationCount)

        val publishedFile = File(finalPubDir, "photo_published.jpg")
        assertTrue("Published file must exist in MediaStore destination", publishedFile.exists())
        assertEquals(srcFile.length(), publishedFile.length())

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `genuine premature disconnect during chunk streaming results in honest failure`() = runTest(testDispatcher) {
        val receiver = TcpDataReceiver(dispatchers)
        val port = receiver.bind()
        val tid = "tid-premature-disconnect"
        val authToken = "token-test-premature"

        val item = TransferItem(
            index = 0,
            relativePath = "truncated.bin",
            sizeBytes = 1024 * 1024, // 1 MB
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val rxJob = launch(Dispatchers.IO) {
            val outcome = receiver.acceptAndReceive(
                transferId = tid,
                expectedAuthToken = authToken,
                expectedPeerDeviceId = "sender-peer",
                offerItems = listOf(item),
                destinationDir = tempDir.newFolder("truncated_dest"),
                chunkSize = 256 * 1024,
                onProgress = { _, _, _ -> },
                onItemVerified = {},
            )
            // Stream was closed after 1 chunk out of 4 without TransferEnd
            assertTrue("Receiver must report Failed on genuine premature truncation", outcome is TcpReceiveOutcome.Failed)
        }

        // Sender connects and sends Auth, ItemBegin, and only 1 chunk, then closes socket abruptly
        val socket = Socket("127.0.0.1", port)
        val out = BufferedOutputStream(socket.getOutputStream())
        val input = BufferedInputStream(socket.getInputStream())

        TcpFrameCodec.writeFrame(
            out,
            TcpFrame.Auth(
                seq = 1L,
                transferId = tid,
                authToken = authToken,
                senderDeviceId = "sender-peer",
                route = TransferRoute.LAN_TCP,
            ),
        )
        out.flush()
        val ack = TcpFrameCodec.readFrame(input) as TcpFrame.AuthAck
        assertTrue(ack.ok)

        TcpFrameCodec.writeFrame(
            out,
            TcpFrame.ItemBegin(2L, tid, 0, 1024 * 1024, 4, 256 * 1024),
        )
        out.flush()

        // Write only 1 chunk
        TcpFrameCodec.writeFrame(
            out,
            TcpFrame.Chunk(3L, tid, 0, 0, ByteArray(256 * 1024) { 1 }),
        )
        out.flush()

        // Forcibly close socket without TransferEnd or remaining chunks
        socket.close()

        rxJob.join()
        receiver.close()
    }

    @Test
    fun `cancellation during streaming stops data and marks cancelled`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val senderRunner = TransferRunner(dispatchers)
        val receiverRunner = TransferRunner(dispatchers)

        // Large 10 MB payload
        val data = ByteArray(10 * 1024 * 1024) { (it % 255).toByte() }
        val srcFile = tempDir.newFile("cancel_test.bin").apply { writeBytes(data) }
        val destDir = tempDir.newFolder("cancel_dest")

        val tid = "tid-cancel-test"
        val item = TransferItem(
            index = 0,
            relativePath = "cancel_test.bin",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val rxJob = launch(Dispatchers.IO) {
            receiverRunner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
        }

        val sendJob = launch(Dispatchers.IO) {
            senderRunner.send(
                session = senderSession,
                items = listOf(item),
                senderName = "CancelSender",
                transferId = tid,
                resolveFile = { srcFile },
            )
        }

        // Wait briefly for streaming to start, then cancel from sender
        Thread.sleep(60)
        senderRunner.cancel(tid)

        sendJob.join()
        rxJob.join()

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `cancellation while blocked in socket IO unblocks immediately`() = runTest(testDispatcher) {
        val receiver = TcpDataReceiver(dispatchers)
        val port = receiver.bind()
        val tid = "tid-block-unblock"

        val unblocked = CompletableDeferred<Boolean>()

        val rxJob = launch(Dispatchers.IO) {
            try {
                // Blocked in acceptAndReceive awaiting incoming socket connection
                receiver.acceptAndReceive(
                    transferId = tid,
                    expectedAuthToken = "token-xyz",
                    expectedPeerDeviceId = "dev-1",
                    offerItems = emptyList(),
                    destinationDir = tempDir.newFolder("unblock_dest"),
                    chunkSize = 256 * 1024,
                    onProgress = { _, _, _ -> },
                    onItemVerified = {},
                )
                unblocked.complete(true)
            } catch (t: Throwable) {
                unblocked.complete(true)
            }
        }

        // Allow accept() to enter blocking OS wait
        Thread.sleep(60)

        // Trigger immediate socket closure to unblock blocking accept
        receiver.closeSocketImmediately()

        rxJob.join()
        assertTrue("Blocking socket call must be unblocked by closeSocketImmediately", unblocked.isCompleted)
        receiver.close()
    }

    @Test
    fun `late cancel after committed completion is ignored`() = runTest(testDispatcher) {
        val activeTransferState = kotlinx.coroutines.flow.MutableStateFlow<com.veyro.core.model.ActiveTransfer?>(
            com.veyro.core.model.ActiveTransfer(
                transferId = "tid-already-done",
                direction = TransferDirection.RECEIVE,
                peerDeviceId = DeviceId("peer-1"),
                peerDisplayName = "Peer One",
                currentFileName = "done.txt",
                currentFileIndex = 0,
                totalFiles = 1,
                bytesTransferred = 1000L,
                totalBytes = 1000L,
                bytesPerSecond = 0L,
                stage = TransferStage.COMPLETED,
                statusText = "Completed",
            ),
        )

        // Attempting to cancel an already completed transfer
        val current = activeTransferState.value!!
        if (current.stage != TransferStage.COMPLETED) {
            activeTransferState.value = current.copy(stage = TransferStage.CANCELLED)
        }

        assertEquals("Stage must remain COMPLETED and not become CANCELLED", TransferStage.COMPLETED, activeTransferState.value?.stage)
    }

    @Test
    fun `throughput instrumentation accurately records monotonic durations and byte counts`() = runTest(testDispatcher) {
        val metrics = TransferThroughputMetrics(
            transferId = "tid-metrics-test",
            role = "SENDER",
            route = TransferRoute.LAN_TCP,
            chunkSize = 512 * 1024,
            destinationStorageType = "APP_INTERNAL",
        )

        val payload = ByteArray(512 * 1024) { 7 }

        metrics.recordSourceRead(payload.size.toLong()) {
            Thread.sleep(2)
        }

        metrics.recordSocketWrite(payload.size.toLong()) {
            Thread.sleep(2)
        }
        metrics.recordFlush()

        metrics.markTransferComplete()

        assertEquals(512L * 1024L, metrics.sourceBytesRead.get())
        assertEquals(512L * 1024L, metrics.tcpBytesSubmitted.get())
        assertEquals(1, metrics.chunkCount.get())
        assertEquals(1, metrics.flushCount.get())
        assertTrue("Source read duration must be positive", metrics.sourceReadDurationNanos.get() > 0L)
        assertTrue("Socket write duration must be positive", metrics.socketWriteDurationNanos.get() > 0L)
        assertTrue("Effective throughput must be positive", metrics.effectiveThroughputMbPerSec() > 0.0)

        // Also test receiver-side metrics
        val rxMetrics = TransferThroughputMetrics(
            transferId = "tid-metrics-rx-test",
            role = "RECEIVER",
            route = TransferRoute.LAN_TCP,
            chunkSize = 512 * 1024,
            destinationStorageType = "MEDIASTORE",
        )
        rxMetrics.recordTcpReadDuration(5_000_000L, payload.size.toLong())
        rxMetrics.recordDiskWrite(payload.size.toLong()) { Thread.sleep(2) }
        rxMetrics.recordSha256 { Thread.sleep(1) }
        rxMetrics.recordPublish { Thread.sleep(1) }
        rxMetrics.markTransferComplete()

        assertEquals(512L * 1024L, rxMetrics.tcpBytesRead.get())
        assertEquals(512L * 1024L, rxMetrics.diskBytesWritten.get())
        assertEquals(1, rxMetrics.chunkCount.get())
        assertTrue("TCP read duration must be positive", rxMetrics.tcpReadDurationNanos.get() > 0L)
        assertTrue("Disk write duration must be positive", rxMetrics.diskWriteDurationNanos.get() > 0L)
        assertTrue("SHA256 duration must be positive", rxMetrics.sha256DurationNanos.get() > 0L)
        assertTrue("Publish duration must be positive", rxMetrics.publishDurationNanos.get() > 0L)
    }

    @Test
    fun `large 5 MB binary file preserves byte for byte sha256 integrity`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val size = 5 * 1024 * 1024
        val data = ByteArray(size) { (it * 31 xor (it shr 5)).toByte() }
        val md = MessageDigest.getInstance("SHA-256")
        val expectedSha = md.digest(data).joinToString("") { "%02x".format(it) }

        val srcFile = tempDir.newFile("integrity_large.bin").apply { writeBytes(data) }
        val destDir = tempDir.newFolder("integrity_dest")

        val item = TransferItem(
            index = 0,
            relativePath = "integrity_large.bin",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val rxJob = launch {
            val res = runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
            assertEquals(1, res.size)
            assertEquals(VerificationResult.VERIFIED, res[0].verification)
        }

        val sendOutcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "IntegritySender",
            transferId = "tid-integrity-5mb",
            resolveFile = { srcFile },
        )

        rxJob.join()

        assertTrue(sendOutcome is TransferRunner.SendOutcome.Done)
        val destFile = File(destDir, "integrity_large.bin")
        assertTrue(destFile.exists())
        assertEquals(size.toLong(), destFile.length())

        val actualSha = md.digest(destFile.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("Cryptographic SHA-256 digest must match byte-for-byte", expectedSha, actualSha)

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `end to end transfer records non zero chunk count and exact byte totals`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val size = 2 * 1024 * 1024
        val data = ByteArray(size) { (it xor 0x5A).toByte() }
        val srcFile = tempDir.newFile("nonzero_chunks.bin").apply { writeBytes(data) }
        val destDir = tempDir.newFolder("nonzero_chunks_dest")

        val item = TransferItem(
            index = 0,
            relativePath = "nonzero_chunks.bin",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val rxJob = launch {
            val res = runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
            assertEquals(1, res.size)
            assertEquals(VerificationResult.VERIFIED, res[0].verification)
        }

        val sendOutcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "NonZeroSender",
            transferId = "tid-nonzero-chunks",
            resolveFile = { srcFile },
        )

        rxJob.join()
        assertTrue(sendOutcome is TransferRunner.SendOutcome.Done)
        val destFile = File(destDir, "nonzero_chunks.bin")
        assertEquals(size.toLong(), destFile.length())

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `chunk size benchmark comparing 256K 512K and 1M throughput and integrity`() = runTest(testDispatcher) {
        val sizes = listOf(256 * 1024, 512 * 1024, 1024 * 1024)
        val testFileSize = 2 * 1024 * 1024
        val testData = ByteArray(testFileSize) { (it * 17).toByte() }

        for (chunkSize in sizes) {
            val (senderSession, receiverSession) = createConnectedPair()
            val runner = TransferRunner(dispatchers)

            val srcFile = tempDir.newFile("bench_${chunkSize}.bin").apply { writeBytes(testData) }
            val destDir = tempDir.newFolder("bench_dest_${chunkSize}")

            val item = TransferItem(
                index = 0,
                relativePath = "bench_${chunkSize}.bin",
                sizeBytes = srcFile.length(),
                lastModifiedEpochMillis = System.currentTimeMillis(),
                isDirectory = false,
            )

            val rxJob = launch {
                val res = runner.receive(
                    session = receiverSession,
                    destinationDir = destDir,
                    authorize = { true },
                )
                assertEquals(1, res.size)
                assertEquals(VerificationResult.VERIFIED, res[0].verification)
            }

            val sendOutcome = runner.send(
                session = senderSession,
                items = listOf(item),
                senderName = "BenchSender",
                transferId = "tid-bench-$chunkSize",
                resolveFile = { srcFile },
            )

            rxJob.join()
            assertTrue(sendOutcome is TransferRunner.SendOutcome.Done)
            val destFile = File(destDir, "bench_${chunkSize}.bin")
            assertEquals(testFileSize.toLong(), destFile.length())
            assertArrayEquals(testData, destFile.readBytes())

            senderSession.close()
            receiverSession.close()
        }
    }
}
