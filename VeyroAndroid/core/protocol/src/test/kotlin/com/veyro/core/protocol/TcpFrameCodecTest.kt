package com.veyro.core.protocol

import com.veyro.core.model.TransferRoute
import com.veyro.core.protocol.tcp.TcpFrame
import com.veyro.core.protocol.tcp.TcpFrameCodec
import com.veyro.core.protocol.tcp.Vtc3
import com.veyro.core.protocol.tcp.Vtc3Reader
import com.veyro.core.protocol.tcp.Vtc3Writer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * VTC3 records (ADR-058, 04-PROTOCOL.md 7.3): the VTC1 payload codecs inside sealed records, the
 * reader's header checks, and the stream-open exchange. Byte-exact values are pinned by
 * `Vtc3VectorTest` in `:core:link` against `frames/vtc3-frames.json`.
 */
public class TcpFrameCodecTest {

    private val key = ByteArray(32) { (it * 3).toByte() }
    private val tid = "transfer-1234"

    private fun roundTrip(vararg frames: TcpFrame): List<TcpFrame> {
        val out = ByteArrayOutputStream()
        val writer = Vtc3Writer(out, key, 1, tid)
        frames.forEach { writer.write(it) }
        val reader = Vtc3Reader(out.toByteArray().inputStream(), key, 1, tid)
        val read = frames.map { reader.readOrNull()!! }
        assertNull(reader.readOrNull())
        return read
    }

    @Test
    public fun everyFrameTypeRoundTripsAndSeqIsTheCounter() {
        val chunk = Random(7).nextBytes(300_000)
        val frames = listOf(
            TcpFrame.Auth(99L, tid, "secret-token-xyz", "0123456789ABCDEF", TransferRoute.WIFI_DIRECT),
            TcpFrame.AuthAck(99L, tid, ok = false, reason = "UNAUTHORIZED_PEER"),
            TcpFrame.ItemBegin(99L, tid, 3, 1_000_000L, 2, 524_288),
            TcpFrame.Chunk(99L, tid, 3, 1, chunk),
            TcpFrame.ItemEnd(99L, tid, 3, "ab".repeat(32)),
            TcpFrame.ItemResult(99L, tid, 3, ok = false, errorMessage = "HASH_MISMATCH"),
            TcpFrame.TransferEnd(99L, tid, completed = true),
            TcpFrame.Cancel(99L, tid, "user"),
        )
        val read = roundTrip(*frames.toTypedArray())
        for ((i, frame) in read.withIndex()) {
            assertEquals("seq is the record counter, not the caller's", i.toLong(), frame.seq)
        }
        assertEquals("secret-token-xyz", (read[0] as TcpFrame.Auth).authToken)
        assertEquals(TransferRoute.WIFI_DIRECT, (read[0] as TcpFrame.Auth).route)
        assertEquals("UNAUTHORIZED_PEER", (read[1] as TcpFrame.AuthAck).reason)
        assertEquals(1_000_000L, (read[2] as TcpFrame.ItemBegin).sizeBytes)
        assertArrayEquals(chunk, (read[3] as TcpFrame.Chunk).data)
        assertEquals("HASH_MISMATCH", (read[5] as TcpFrame.ItemResult).errorMessage)
        assertTrue((read[6] as TcpFrame.TransferEnd).completed)
        assertEquals("user", (read[7] as TcpFrame.Cancel).reason)
    }

    @Test
    public fun chunkPathMatchesTheGenericPayloadEncoding() {
        // The writer encrypts chunk bytes straight from the caller's array; the plaintext must be
        // exactly the VTC1 chunk payload (itemIndex, chunkIndex, dataLen, data).
        val data = Random(3).nextBytes(1000)
        val out = ByteArrayOutputStream()
        Vtc3Writer(out, key, 1, tid).write(TcpFrame.Chunk(0, tid, 2, 5, data))
        val record = out.toByteArray()
        val headerLen = 4 + 1 + 2 + tid.length + 8 + 4
        val open = AeadStreamFramer(key, 1, AeadStreamFramer.Role.OPEN)
        val plain = ByteArray(record.size)
        val len = open.open(record, headerLen, record, headerLen, record.size - headerLen, plain, 0)
        assertArrayEquals(TcpFrameCodec.encodePayload(TcpFrame.Chunk(0, tid, 2, 5, data)), plain.copyOf(len))
    }

    @Test
    public fun cleanEofAtARecordBoundaryIsNullAndTruncationFails() {
        assertNull(Vtc3Reader(ByteArray(0).inputStream(), key, 1, tid).readOrNull())
        val out = ByteArrayOutputStream()
        Vtc3Writer(out, key, 1, tid).write(TcpFrame.TransferEnd(0, tid, true))
        val good = out.toByteArray()
        assertThrows(EOFException::class.java) { Vtc3Reader(good.copyOf(good.size - 1).inputStream(), key, 1, tid).readOrNull() }
        assertThrows(EOFException::class.java) { Vtc3Reader(good.copyOf(5).inputStream(), key, 1, tid).readOrNull() }
    }

    @Test
    public fun aRecordForAnotherTransferOrKeyOrStreamFails() {
        val out = ByteArrayOutputStream()
        Vtc3Writer(out, key, 1, "other-transfer").write(TcpFrame.TransferEnd(0, "other-transfer", true))
        assertThrows(AeadStreamException::class.java) { Vtc3Reader(out.toByteArray().inputStream(), key, 1, tid).readOrNull() }
        val mine = ByteArrayOutputStream()
        Vtc3Writer(mine, key, 1, tid).write(TcpFrame.TransferEnd(0, tid, true))
        assertThrows(AeadStreamException::class.java) { Vtc3Reader(mine.toByteArray().inputStream(), ByteArray(32), 1, tid).readOrNull() }
        assertThrows(AeadStreamException::class.java) { Vtc3Reader(mine.toByteArray().inputStream(), key, 2, tid).readOrNull() }
    }

    @Test
    public fun anOversizedLengthIsRejectedBeforeAllocation() {
        val out = ByteArrayOutputStream()
        Vtc3Writer(out, key, 1, tid).write(TcpFrame.TransferEnd(0, tid, true))
        val record = out.toByteArray()
        val lenOff = 4 + 1 + 2 + tid.length + 8
        for (len in listOf(TcpFrameCodec.MAX_PAYLOAD_SIZE + 17, 15, -1)) {
            val bad = record.copyOf()
            TcpFrameCodec.writeIntBE(bad, lenOff, len)
            assertThrows(AeadStreamException::class.java) { Vtc3Reader(bad.inputStream(), key, 1, tid).readOrNull() }
        }
    }

    @Test
    public fun aSlowStreamDeliveringOneByteAtATimeIsReassembled() {
        val out = ByteArrayOutputStream()
        val data = Random(11).nextBytes(5000)
        Vtc3Writer(out, key, 1, tid).write(TcpFrame.Chunk(0, tid, 0, 0, data))
        val bytes = out.toByteArray()
        val slow = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= bytes.size) return -1
                b[off] = bytes[pos++]
                return 1
            }
        }
        assertArrayEquals(data, (Vtc3Reader(slow, key, 1, tid).readOrNull() as TcpFrame.Chunk).data)
    }

    @Test
    public fun aTimeoutBeforeARecordPassesThroughButInsideOneIsFatal() {
        val out = ByteArrayOutputStream()
        Vtc3Writer(out, key, 1, tid).write(TcpFrame.TransferEnd(0, tid, true))
        val bytes = out.toByteArray()
        var calls = 0
        val idleThenData = object : InputStream() {
            var pos = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (calls++ == 0) throw SocketTimeoutException("idle")
                if (pos >= bytes.size) return -1
                val n = minOf(len, bytes.size - pos)
                System.arraycopy(bytes, pos, b, off, n)
                pos += n
                return n
            }
        }
        val reader = Vtc3Reader(idleThenData, key, 1, tid)
        assertThrows(SocketTimeoutException::class.java) { reader.readOrNull() }
        assertTrue(reader.readOrNull() is TcpFrame.TransferEnd)

        var served = 0
        val stallsMidRecord = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= 3) throw SocketTimeoutException("stalled")
                b[off] = bytes[served++]
                return 1
            }
        }
        val broken = Vtc3Reader(stallsMidRecord, key, 1, tid)
        assertThrows(AeadStreamException::class.java) { broken.readOrNull() }
        assertThrows("the reader stays failed", IOException::class.java) { broken.readOrNull() }
    }

    @Test
    public fun streamOpenBothWaysGivesBothEndsTheSameKeysWithFreshSalts() {
        val toReceiver = PipedOutputStream()
        val receiverIn = PipedInputStream(toReceiver, 1 shl 16)
        val toSender = PipedOutputStream()
        val senderIn = PipedInputStream(toSender, 1 shl 16)
        val seen = mutableListOf<Pair<ByteArray, ByteArray>>()
        val derive: (ByteArray, ByteArray) -> ByteArray = { a, b ->
            synchronized(seen) { seen += a to b }
            ByteArray(64) { (a[it % 16] + b[(it + 1) % 16] + it).toByte() }
        }
        var received: TcpFrame? = null
        val t = thread {
            val ch = Vtc3.openAsReceiver(receiverIn, toSender, tid, derive)
            received = ch.reader.readOrNull()
            ch.writer.write(TcpFrame.AuthAck(0, tid, true))
        }
        val sender = Vtc3.openAsSender(senderIn, toReceiver, tid, derive)
        sender.writer.write(TcpFrame.Auth(0, tid, "tok", "dev", TransferRoute.LAN_TCP))
        assertTrue(sender.reader.readOrNull() is TcpFrame.AuthAck)
        t.join()
        assertTrue(received is TcpFrame.Auth)
        assertEquals(2, seen.size)
        assertArrayEquals("both ends derive from the same salts", seen[0].first, seen[1].first)
        assertArrayEquals(seen[0].second, seen[1].second)
        assertTrue("each side contributes its own salt", !seen[0].first.contentEquals(seen[0].second))
    }

    @Test
    public fun aStreamOpenForAnotherTransferIsRefused() {
        val record = Vtc3.streamOpenRecord("other", ByteArray(16))
        assertThrows(AeadStreamException::class.java) { Vtc3.readStreamOpen(record.inputStream(), tid) }
    }
}

/** The AEAD framer itself (ADR-058, 04-PROTOCOL.md 5.5). */
public class AeadStreamFramerTest {

    private val key = ByteArray(32) { 1 }
    private val aad = byteArrayOf(9, 9)

    @Test
    public fun counterAdvancesAndRecordsOpenOnlyInOrder() {
        val seal = AeadStreamFramer(key, 4, AeadStreamFramer.Role.SEAL)
        val open = AeadStreamFramer(key, 4, AeadStreamFramer.Role.OPEN)
        val records = (0 until 3).map { i ->
            val p = byteArrayOf(i.toByte(), 2, 3)
            ByteArray(p.size + 16).also { seal.seal(aad, aad.size, p, 0, p.size, it, 0) }
        }
        assertEquals(3, seal.nextCounter)
        val out = ByteArray(64)
        assertEquals(3, open.open(aad, aad.size, records[0], 0, records[0].size, out, 0))
        assertThrows(AeadStreamException::class.java) { open.open(aad, aad.size, records[2], 0, records[2].size, out, 0) }
        assertThrows("poisoned after one failure", AeadStreamException::class.java) { open.open(aad, aad.size, records[1], 0, records[1].size, out, 0) }
    }

    @Test
    public fun twoPartSealEqualsOnePartSeal() {
        val head = byteArrayOf(1, 2, 3, 4)
        val body = Random(5).nextBytes(10_000)
        val a = AeadStreamFramer(key, 1, AeadStreamFramer.Role.SEAL)
        val b = AeadStreamFramer(key, 1, AeadStreamFramer.Role.SEAL)
        val one = ByteArray(head.size + body.size + 16)
        a.seal(aad, aad.size, head + body, 0, head.size + body.size, one, 0)
        val two = ByteArray(one.size)
        b.seal(aad, aad.size, head, head.size, body, 0, body.size, two, 0)
        assertArrayEquals(one, two)
    }

    @Test
    public fun roleMisuseAndExhaustionAreRefused() {
        val seal = AeadStreamFramer(key, 1, AeadStreamFramer.Role.SEAL)
        val open = AeadStreamFramer(key, 1, AeadStreamFramer.Role.OPEN)
        assertThrows(IllegalStateException::class.java) { open.seal(aad, 0, ByteArray(1), 0, 1, ByteArray(17), 0) }
        assertThrows(IllegalStateException::class.java) { seal.open(aad, 0, ByteArray(17), 0, 17, ByteArray(1), 0) }
        seal.setCounterForTest(Long.MAX_VALUE)
        assertThrows(AeadStreamException::class.java) { seal.seal(aad, 0, ByteArray(1), 0, 1, ByteArray(17), 0) }
        assertThrows(AeadStreamException::class.java) { open.open(aad, 0, ByteArray(15), 0, 15, ByteArray(1), 0) }
    }

    @Test
    public fun wipedFramerRefusesEverything() {
        val seal = AeadStreamFramer(key, 1, AeadStreamFramer.Role.SEAL)
        seal.wipe()
        assertThrows(AeadStreamException::class.java) { seal.seal(aad, 0, ByteArray(1), 0, 1, ByteArray(17), 0) }
    }

    @Test
    public fun theStandardCipherNameResolvesOnTheBuildJvm() {
        ChaChaPoly.requireAvailable()
        assertEquals("ChaCha20-Poly1305", ChaChaPoly.TRANSFORMATIONS.first())
    }
}
