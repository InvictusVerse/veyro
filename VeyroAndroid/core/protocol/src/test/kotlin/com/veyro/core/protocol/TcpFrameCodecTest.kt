package com.veyro.core.protocol

import com.veyro.core.model.TransferRoute
import com.veyro.core.protocol.tcp.TcpFrame
import com.veyro.core.protocol.tcp.TcpFrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random

public class TcpFrameCodecTest {

    @Test
    public fun roundTripAuthFrame() {
        val frame = TcpFrame.Auth(
            seq = 42L,
            transferId = "transfer-1234",
            authToken = "secret-token-xyz",
            senderDeviceId = "0123456789ABCDEF",
            route = TransferRoute.WIFI_DIRECT,
        )
        val encoded = TcpFrameCodec.encode(frame)
        val decoded = TcpFrameCodec.decode(encoded) as TcpFrame.Auth

        assertEquals(42L, decoded.seq)
        assertEquals("transfer-1234", decoded.transferId)
        assertEquals("secret-token-xyz", decoded.authToken)
        assertEquals("0123456789ABCDEF", decoded.senderDeviceId)
        assertEquals(TransferRoute.WIFI_DIRECT, decoded.route)
    }

    @Test
    public fun roundTripAuthAckFrame() {
        val frameOk = TcpFrame.AuthAck(
            seq = 1L,
            transferId = "tx-1",
            ok = true,
            reason = null,
        )
        val decodedOk = TcpFrameCodec.decode(TcpFrameCodec.encode(frameOk)) as TcpFrame.AuthAck
        assertEquals(1L, decodedOk.seq)
        assertEquals("tx-1", decodedOk.transferId)
        assertTrue(decodedOk.ok)
        assertNull(decodedOk.reason)

        val frameFail = TcpFrame.AuthAck(
            seq = 2L,
            transferId = "tx-2",
            ok = false,
            reason = "UNAUTHORIZED_PEER",
        )
        val decodedFail = TcpFrameCodec.decode(TcpFrameCodec.encode(frameFail)) as TcpFrame.AuthAck
        assertEquals(2L, decodedFail.seq)
        assertEquals("tx-2", decodedFail.transferId)
        assertEquals(false, decodedFail.ok)
        assertEquals("UNAUTHORIZED_PEER", decodedFail.reason)
    }

    @Test
    public fun roundTripItemBegin() {
        val frame = TcpFrame.ItemBegin(
            seq = 100L,
            transferId = "tx-100",
            itemIndex = 3,
            sizeBytes = 104857600L, // 100 MB
            chunkCount = 400,
            chunkSize = 262144,
        )
        val decoded = TcpFrameCodec.decode(TcpFrameCodec.encode(frame)) as TcpFrame.ItemBegin
        assertEquals(100L, decoded.seq)
        assertEquals("tx-100", decoded.transferId)
        assertEquals(3, decoded.itemIndex)
        assertEquals(104857600L, decoded.sizeBytes)
        assertEquals(400, decoded.chunkCount)
        assertEquals(262144, decoded.chunkSize)
    }

    @Test
    public fun roundTripChunkBinaryFidelity() {
        // Full range of byte values 0..255 and arbitrary length
        val randomBytes = ByteArray(256 * 1024)
        Random(42).nextBytes(randomBytes)

        val frame = TcpFrame.Chunk(
            seq = 555L,
            transferId = "tx-chunk-test",
            itemIndex = 0,
            chunkIndex = 12,
            data = randomBytes,
        )
        val encoded = TcpFrameCodec.encode(frame)
        val decoded = TcpFrameCodec.decode(encoded) as TcpFrame.Chunk

        assertEquals(555L, decoded.seq)
        assertEquals("tx-chunk-test", decoded.transferId)
        assertEquals(0, decoded.itemIndex)
        assertEquals(12, decoded.chunkIndex)
        assertArrayEquals(randomBytes, decoded.data)
    }

    @Test
    public fun roundTripItemEndAndResult() {
        val endFrame = TcpFrame.ItemEnd(
            seq = 600L,
            transferId = "tx-end",
            itemIndex = 1,
            sha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )
        val decodedEnd = TcpFrameCodec.decode(TcpFrameCodec.encode(endFrame)) as TcpFrame.ItemEnd
        assertEquals(600L, decodedEnd.seq)
        assertEquals("tx-end", decodedEnd.transferId)
        assertEquals(1, decodedEnd.itemIndex)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", decodedEnd.sha256Hex)

        val resultFrame = TcpFrame.ItemResult(
            seq = 601L,
            transferId = "tx-end",
            itemIndex = 1,
            ok = true,
            errorMessage = null,
        )
        val decodedResult = TcpFrameCodec.decode(TcpFrameCodec.encode(resultFrame)) as TcpFrame.ItemResult
        assertEquals(601L, decodedResult.seq)
        assertEquals(1, decodedResult.itemIndex)
        assertTrue(decodedResult.ok)
        assertNull(decodedResult.errorMessage)
    }

    @Test
    public fun roundTripTransferEndAndCancel() {
        val end = TcpFrame.TransferEnd(seq = 700L, transferId = "tx-final", completed = true)
        val decodedEnd = TcpFrameCodec.decode(TcpFrameCodec.encode(end)) as TcpFrame.TransferEnd
        assertEquals(700L, decodedEnd.seq)
        assertTrue(decodedEnd.completed)

        val cancel = TcpFrame.Cancel(seq = 701L, transferId = "tx-final", reason = "USER_CANCELLED")
        val decodedCancel = TcpFrameCodec.decode(TcpFrameCodec.encode(cancel)) as TcpFrame.Cancel
        assertEquals(701L, decodedCancel.seq)
        assertEquals("USER_CANCELLED", decodedCancel.reason)
    }

    @Test
    public fun handlesFragmentedPartialReadsAcrossStream() {
        val frame = TcpFrame.Chunk(
            seq = 12L,
            transferId = "frag-test",
            itemIndex = 2,
            chunkIndex = 0,
            data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
        )
        val encoded = TcpFrameCodec.encode(frame)

        // Slow stream returning 1 byte per read() call to simulate extreme TCP fragmentation
        val slowStream = object : InputStream() {
            private var idx = 0
            override fun read(): Int {
                return if (idx < encoded.size) (encoded[idx++].toInt() and 0xFF) else -1
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (idx >= encoded.size) return -1
                // Return at most 1 byte to simulate chunk fragmentation
                val byte = read()
                b[off] = byte.toByte()
                return 1
            }
        }

        val decoded = TcpFrameCodec.readFrame(slowStream) as TcpFrame.Chunk
        assertEquals(12L, decoded.seq)
        assertEquals("frag-test", decoded.transferId)
        assertArrayEquals(frame.data, decoded.data)
    }

    @Test
    public fun rejectsInvalidMagic() {
        val badBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x01, 0x00, 0x04) + "test".toByteArray()
        try {
            TcpFrameCodec.decode(badBytes)
            fail("Expected IOException on bad magic")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("magic") == true)
        }
    }

    @Test
    public fun rejectsOversizedDeclaredPayload() {
        val out = ByteArrayOutputStream()
        out.write(TcpFrameCodec.MAGIC)
        out.write(TcpFrameCodec.TYPE_TRANSFER_END.toInt())
        out.write(0) // transferId len high
        out.write(4) // transferId len low = 4
        out.write("test".toByteArray())
        // seq = 1L (8 bytes)
        for (i in 0 until 7) out.write(0)
        out.write(1)
        // payloadLen = 32 MiB (exceeds MAX_PAYLOAD_SIZE 16 MiB)
        out.write(0x02) // 33,554,432
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)

        try {
            TcpFrameCodec.decode(out.toByteArray())
            fail("Expected IOException on oversized payload length")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("Invalid payload length") == true)
        }
    }

    @Test
    public fun rejectsPrematureEOF() {
        val truncated = TcpFrameCodec.MAGIC.copyOf(3) // only 3 bytes
        try {
            TcpFrameCodec.decode(truncated)
            fail("Expected EOFException on truncated header")
        } catch (e: EOFException) {
            // Success
        }
    }

    @Test
    public fun readFrameOrNullReturnsNullOnCleanEof() {
        val emptyStream = ByteArrayInputStream(ByteArray(0))
        val frame = TcpFrameCodec.readFrameOrNull(emptyStream)
        assertNull(frame)
    }

    @Test
    public fun readFrameOrNullThrowsEofExceptionOnTruncatedHeader() {
        // 2 bytes into the 7-byte prefix
        val partialStream = ByteArrayInputStream(byteArrayOf(0x56, 0x54))
        try {
            TcpFrameCodec.readFrameOrNull(partialStream)
            fail("Expected EOFException on truncated 2-byte header")
        } catch (e: EOFException) {
            assertTrue(e.message?.contains("read 1 of 6 expected bytes") == true)
        }
    }

    @Test
    public fun readFrameOrNullReadsMultipleFramesUntilCleanEof() {
        val frame1 = TcpFrame.ItemBegin(1L, "t1", 0, 1024L, 1, 1024)
        val frame2 = TcpFrame.Chunk(2L, "t1", 0, 0, byteArrayOf(1, 2, 3, 4))
        val frame3 = TcpFrame.TransferEnd(3L, "t1", true)

        val out = ByteArrayOutputStream()
        TcpFrameCodec.writeFrame(out, frame1)
        TcpFrameCodec.writeFrame(out, frame2)
        TcpFrameCodec.writeFrame(out, frame3)

        val input = ByteArrayInputStream(out.toByteArray())
        val f1 = TcpFrameCodec.readFrameOrNull(input)
        val f2 = TcpFrameCodec.readFrameOrNull(input)
        val f3 = TcpFrameCodec.readFrameOrNull(input)
        val f4 = TcpFrameCodec.readFrameOrNull(input)

        assertEquals(frame1.seq, f1?.seq)
        assertEquals(frame2.seq, f2?.seq)
        assertEquals(frame3.seq, f3?.seq)
        assertNull(f4)
    }
}

