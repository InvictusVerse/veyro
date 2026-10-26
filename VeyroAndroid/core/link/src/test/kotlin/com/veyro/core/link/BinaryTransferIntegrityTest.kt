package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.model.TransferItem
import com.veyro.core.model.VerificationResult
import com.veyro.core.transfer.ChunkMath
import com.veyro.core.transfer.PartFile
import io.ktor.websocket.Frame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class BinaryTransferIntegrityTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = testDispatcher
        override val io = kotlinx.coroutines.Dispatchers.IO
        override val main = testDispatcher
    }

    private fun createConnectedPair(): Pair<PeerConnection, PeerConnection> {
        val secret = ByteArray(32) { 77 }
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
            localDeviceId = "sender-dev",
            tag = "TestSender",
            dispatcher = testDispatcher,
            peerDeviceId = "receiver-dev",
            enableHeartbeat = false,
        )

        val receiver = PeerConnection(
            ws = recvWs,
            initialKey = sessionKey,
            initialEncrypt = true,
            localDeviceId = "receiver-dev",
            tag = "TestReceiver",
            dispatcher = testDispatcher,
            peerDeviceId = "sender-dev",
            enableHeartbeat = false,
        )

        initiator.start()
        receiver.start()

        return initiator to receiver
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `arbitrary binary data with all 256 byte values transfers byte-for-byte`() = runTest(testDispatcher) {
        val (senderConn, receiverConn) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val srcFile = tempDir.newFile("binary_all_bytes.bin")
        // Build data containing every byte value from 0x00 to 0xFF multiple times
        val rawBytes = ByteArray(512 * 1024) { idx -> (idx % 256).toByte() }
        srcFile.writeBytes(rawBytes)
        val expectedSha256 = sha256(rawBytes)

        val destDir = tempDir.newFolder("rx_binary")
        val items = listOf(
            TransferItem(
                index = 0,
                relativePath = "binary_all_bytes.bin",
                sizeBytes = srcFile.length(),
                lastModifiedEpochMillis = System.currentTimeMillis(),
                isDirectory = false,
            ),
        )

        val rxJob = launch {
            runner.receive(
                session = receiverConn,
                destinationDir = destDir,
                authorize = { true },
            )
        }

        val sendOutcome = runner.send(
            session = senderConn,
            items = items,
            senderName = "SenderDevice",
            transferId = "tid-all-bytes",
            resolveFile = { srcFile },
        )

        rxJob.join()

        assertTrue(sendOutcome is TransferRunner.SendOutcome.Done)
        val done = sendOutcome as TransferRunner.SendOutcome.Done
        assertEquals(1, done.results.size)
        assertEquals(VerificationResult.VERIFIED, done.results[0].verification)

        val receivedFile = File(destDir, "binary_all_bytes.bin")
        assertTrue(receivedFile.exists())
        assertEquals(rawBytes.size.toLong(), receivedFile.length())

        val receivedBytes = receivedFile.readBytes()
        assertArrayEquals("Received bytes must match source bytes exactly", rawBytes, receivedBytes)
        assertEquals(expectedSha256, sha256(receivedBytes))

        senderConn.close()
        receiverConn.close()
    }

    @Test
    fun `multi-chunk simulated PDF with non-aligned final chunk transfers with identical SHA256`() = runTest(testDispatcher) {
        val (senderConn, receiverConn) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        // Realistic simulated PDF: PDF header (%PDF-1.7), random binary stream, PDF trailer (%%EOF)
        val pdfLength = 1_234_567 // 4 full 256KB chunks + 1 non-aligned 185,543-byte chunk
        val pdfBytes = ByteArray(pdfLength)
        Random(42).nextBytes(pdfBytes)
        // PDF magic header
        "%PDF-1.7\n".toByteArray().copyInto(pdfBytes, 0)
        // PDF EOF trailer
        "\n%%EOF".toByteArray().copyInto(pdfBytes, pdfLength - 6)

        val srcFile = tempDir.newFile("Letter_3126.pdf")
        srcFile.writeBytes(pdfBytes)
        val expectedSha256 = sha256(pdfBytes)

        val destDir = tempDir.newFolder("rx_pdf")
        val items = listOf(
            TransferItem(
                index = 0,
                relativePath = "Letter_3126.pdf",
                sizeBytes = srcFile.length(),
                lastModifiedEpochMillis = System.currentTimeMillis(),
                isDirectory = false,
            ),
        )

        var reportedSentBytes = 0L
        var lastReportedStage: com.veyro.core.model.TransferStage? = null

        val rxJob = launch {
            runner.receive(
                session = receiverConn,
                destinationDir = destDir,
                authorize = { true },
            )
        }

        val sendOutcome = runner.send(
            session = senderConn,
            items = items,
            senderName = "SenderDevice",
            transferId = "tid-pdf-test",
            onProgress = { sent, total, _, stage, _, _ ->
                reportedSentBytes = sent
                lastReportedStage = stage
            },
            resolveFile = { srcFile },
        )

        rxJob.join()

        assertTrue(sendOutcome is TransferRunner.SendOutcome.Done)
        val done = sendOutcome as TransferRunner.SendOutcome.Done
        assertEquals(VerificationResult.VERIFIED, done.results[0].verification)

        val receivedFile = File(destDir, "Letter_3126.pdf")
        assertTrue(receivedFile.exists())
        assertEquals(pdfLength.toLong(), receivedFile.length())

        val receivedBytes = receivedFile.readBytes()
        assertArrayEquals(pdfBytes, receivedBytes)
        assertEquals("SHA-256 of received PDF must match source exactly", expectedSha256, sha256(receivedBytes))
        assertEquals(pdfLength.toLong(), reportedSentBytes)

        senderConn.close()
        receiverConn.close()
    }

    @Test
    fun `part file verifyAndCommit rejects corrupt expected digest`() {
        val destDir = tempDir.newFolder("rx_corrupt")
        val rawData = ByteArray(1024) { it.toByte() }
        val correctHash = sha256(rawData)
        val corruptedHash = "0000000000000000000000000000000000000000000000000000000000000000"

        val part = PartFile.open(destDir, "test.bin", rawData.size.toLong(), 256 * 1024)
        part.writeChunk(0, rawData)

        val result = part.verifyAndCommit(corruptedHash)
        assertEquals(VerificationResult.HASH_MISMATCH, result)

        // Published target must NOT exist when hash fails
        assertFalse(File(destDir, "test.bin").exists())
    }
}
