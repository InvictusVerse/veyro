package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * ADR-058 AC 15 and AC 16 on Android (review issue 10): the bytes on the data socket between the
 * real [TcpDataSender] and [TcpDataReceiver], kept by a relay in both directions.
 */
class Vtc3WireCaptureTest {

    @get:Rule
    val tempDir: TemporaryFolder = TemporaryFolder()

    private val dispatchers = object : AppDispatchers {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }

    /** Relays one TCP connection to [targetPort] and keeps every byte in each direction. */
    private class CaptureRelay(targetPort: Int) {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        private val forward = ByteArrayOutputStream()
        private val backward = ByteArrayOutputStream()
        private val worker = thread(name = "vtc3-capture-relay") {
            server.use { listener ->
                listener.accept().use { inbound ->
                    Socket(InetAddress.getLoopbackAddress(), targetPort).use { outbound ->
                        val a = pump(inbound, outbound, forward)
                        val b = pump(outbound, inbound, backward)
                        a.join()
                        b.join()
                    }
                }
            }
        }

        private fun pump(from: Socket, to: Socket, capture: ByteArrayOutputStream): Thread = thread {
            val buffer = ByteArray(64 * 1024)
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    synchronized(capture) { capture.write(buffer, 0, n) }
                    output.write(buffer, 0, n)
                }
            } catch (_: IOException) {
            } finally {
                runCatching { to.shutdownOutput() }
            }
        }

        fun join() = worker.join(10_000)

        /** What the dialler (the VTC3 sender) wrote. */
        fun forwardBytes(): ByteArray = synchronized(forward) { forward.toByteArray() }

        /** What the listener (the VTC3 receiver) wrote back. */
        fun backwardBytes(): ByteArray = synchronized(backward) { backward.toByteArray() }
    }

    private class Attempt(val relay: CaptureRelay, val outcome: TcpReceiveOutcome)

    /** One whole transfer of [source] through a fresh data socket and relay. */
    private suspend fun transfer(tid: String, source: ByteArray, dest: File, token: String): Attempt = coroutineScope {
        val chunkSize = 256 * 1024
        val receiver = TcpDataReceiver(dispatchers)
        val relay = CaptureRelay(receiver.bind(InetAddress.getLoopbackAddress()))
        val item = TransferItem(
            index = 0,
            relativePath = "pattern.bin",
            sizeBytes = source.size.toLong(),
            lastModifiedEpochMillis = 0L,
            isDirectory = false,
        )
        val rx = async(Dispatchers.IO) {
            receiver.acceptAndReceive(
                transferId = tid,
                expectedAuthToken = token,
                expectedPeerDeviceId = "sender-dev",
                deriveKeys = TestLinkKeys.deriver(tid),
                offerItems = listOf(item),
                destinationDir = dest,
                chunkSize = chunkSize,
                onProgress = { _, _, _ -> },
                onItemVerified = {},
            )
        }
        val sender = TcpDataSender(dispatchers)
        sender.connectAndAuthenticate("127.0.0.1", relay.port, tid, token, "sender-dev", TransferRoute.LAN_TCP, TestLinkKeys.deriver(tid))
        val chunkCount = (source.size + chunkSize - 1) / chunkSize
        sender.sendItemBegin(tid, 0, source.size.toLong(), chunkCount, chunkSize)
        for (i in 0 until chunkCount) {
            val from = i * chunkSize
            sender.sendChunk(tid, 0, i, source.copyOfRange(from, minOf(source.size, from + chunkSize)))
        }
        sender.sendItemEnd(tid, 0, sha256Hex(source))
        sender.sendTransferEnd(tid, completed = true)
        sender.gracefulShutdown()
        val outcome = withTimeout(30_000) { rx.await() }
        sender.close()
        relay.join()
        Attempt(relay, outcome)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun `file bytes and the token never cross the data socket in the clear (AC 15)`() = runBlocking {
        val pattern = "VEYRO-AC15-PLAINTEXT-PATTERN-0123456789-ABCDEFGHIJKLMNOPQRSTUVWX".toByteArray()
        assertEquals(64, pattern.size)
        val source = ByteArray(1024 * 1024 + 777) { pattern[it % pattern.size] }
        val dest = tempDir.newFolder("ac15")
        val token = "ac15-one-time-token"

        val attempt = transfer("tid-ac15", source, dest, token)

        assertTrue("completed: ${attempt.outcome}", attempt.outcome is TcpReceiveOutcome.Completed)
        assertEquals(sha256Hex(source), sha256Hex(File(dest, "pattern.bin").readBytes()))
        val forward = attempt.relay.forwardBytes()
        val backward = attempt.relay.backwardBytes()
        assertTrue("the file crossed this socket", forward.size > source.size)
        assertTrue("the receiver answered on this socket", backward.isNotEmpty())
        for ((name, wire) in listOf("sender to receiver" to forward, "receiver to sender" to backward)) {
            assertEquals("$name: file pattern", -1, wire.indexOf(pattern))
            assertEquals("$name: token", -1, wire.indexOf(token.toByteArray()))
            assertEquals("$name: sender id", -1, wire.indexOf("sender-dev".toByteArray()))
        }
    }

    /** The stream-open salt and the first sealed record (header, then ciphertext and tag) of one direction. */
    private fun firstSealedRecord(wire: ByteArray, tid: String): Triple<ByteArray, ByteArray, ByteArray> {
        val n = tid.toByteArray().size
        val openLength = 19 + n + 16
        assertEquals(0x09, wire[4].toInt())
        val headerLength = 19 + n
        val at = openLength + 15 + n
        val ctLength = ((wire[at].toInt() and 0xFF) shl 24) or ((wire[at + 1].toInt() and 0xFF) shl 16) or
            ((wire[at + 2].toInt() and 0xFF) shl 8) or (wire[at + 3].toInt() and 0xFF)
        return Triple(
            wire.copyOfRange(19 + n, openLength),
            wire.copyOfRange(openLength, openLength + headerLength),
            wire.copyOfRange(openLength + headerLength, openLength + headerLength + ctLength),
        )
    }

    /**
     * Reopening the data socket for the same transfer id in the same session (a second dial, as
     * after a route or host fallback; a resume dials the same way) uses new keys. The first sealed
     * record of each direction has the same header (type, transfer id, seq 0, length) and the same
     * plaintext in both attempts, so the same nonce; its ciphertext differs, so the key differs.
     */
    @Test
    fun `reopening the data socket for one transfer uses new keys (AC 16)`() = runBlocking {
        val tid = "tid-ac16"
        val source = ByteArray(600 * 1024) { (it * 31).toByte() }
        val first = transfer(tid, source, tempDir.newFolder("first"), "token")
        val second = transfer(tid, source, tempDir.newFolder("second"), "token")
        assertTrue(first.outcome is TcpReceiveOutcome.Completed && second.outcome is TcpReceiveOutcome.Completed)
        val n = tid.toByteArray().size
        val directions = listOf(
            Triple("sender to receiver", first.relay.forwardBytes(), second.relay.forwardBytes()),
            Triple("receiver to sender", first.relay.backwardBytes(), second.relay.backwardBytes()),
        )
        for ((name, a, b) in directions) {
            val (saltA, headerA, sealedA) = firstSealedRecord(a, tid)
            val (saltB, headerB, sealedB) = firstSealedRecord(b, tid)
            assertFalse("$name: a fresh salt per attempt", saltA.contentEquals(saltB))
            assertArrayEquals("$name: same header, so the same nonce", headerA, headerB)
            assertTrue("$name: seq 0", headerA.copyOfRange(7 + n, 15 + n).all { it == 0.toByte() })
            assertFalse("$name: same plaintext and nonce, different ciphertext: a different key", sealedA.contentEquals(sealedB))
        }
    }
}
