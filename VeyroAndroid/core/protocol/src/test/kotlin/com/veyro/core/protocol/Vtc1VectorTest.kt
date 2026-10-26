package com.veyro.core.protocol

import com.veyro.core.model.TransferRoute
import com.veyro.core.protocol.tcp.TcpFrame
import com.veyro.core.protocol.tcp.TcpFrameCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * VTC1 wire format, checked against `protocol/testvectors/frames/vtc1-frames.json`.
 *
 * These expected values were assembled field-by-field from `ProjectInfo/04-PROTOCOL.md` section
 * 7.3 by an independent script that links against neither implementation, then cross-checked
 * against the C# codec. The same constants appear in the Windows test suite, which loads them
 * from the JSON directly.
 *
 * ## Why this test exists
 *
 * The first C# implementation of this codec omitted `transferId` and `seq` from the header
 * entirely, used u32 where the wire uses u16, and gave `Chunk` a byte offset instead of a chunk
 * index. **It passed all of its own round-trip tests**, because the same wrong assumption produced
 * both the encoder and the decoder. Only a byte-exact comparison against an externally derived
 * value catches that class of defect.
 *
 * If this test fails after a change, the change is a wire-format change: bump `VLP_VERSION`,
 * update section 7.3, and update the vector file — in that order.
 */
class Vtc1VectorTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun assertVector(expectedHex: String, expectedLength: Int, frame: TcpFrame) {
        val encoded = TcpFrameCodec.encode(frame)
        assertEquals("frame length", expectedLength, encoded.size)
        assertEquals("frame bytes", expectedHex, encoded.hex())

        // And the bytes must decode back to an equal frame.
        assertEquals("decoded frame", frame, TcpFrameCodec.decode(encoded))
    }

    @Test
    fun `vector - TransferEnd completed`() = assertVector(
        "5654433107000374783100000000000000010000000101",
        23,
        TcpFrame.TransferEnd(seq = 1, transferId = "tx1", completed = true),
    )

    @Test
    fun `vector - Chunk with two data bytes`() = assertVector(
        "565443310400017400000000000000020000000e000000000000000100000002aabb",
        34,
        TcpFrame.Chunk(
            seq = 2,
            transferId = "t",
            itemIndex = 0,
            chunkIndex = 1,
            data = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
        ),
    )

    @Test
    fun `vector - ItemBegin`() = assertVector(
        "5654433103000261620000000000000007000000140000000300000000000004000000000200000200",
        41,
        TcpFrame.ItemBegin(
            seq = 7,
            transferId = "ab",
            itemIndex = 3,
            sizeBytes = 1024,
            chunkCount = 2,
            chunkSize = 512,
        ),
    )

    @Test
    fun `vector - Auth over LAN_TCP`() = assertVector(
        "565443310100017800000000000000010000000b0003746f6b000364657602",
        31,
        TcpFrame.Auth(
            seq = 1,
            transferId = "x",
            authToken = "tok",
            senderDeviceId = "dev",
            route = TransferRoute.LAN_TCP,
        ),
    )

    @Test
    fun `vector - Auth over WIFI_DIRECT uses route byte 0x01`() {
        val encoded = TcpFrameCodec.encode(
            TcpFrame.Auth(1, "x", "t", "d", TransferRoute.WIFI_DIRECT),
        )
        assertEquals(0x01.toByte(), encoded.last())
    }

    @Test
    fun `vector - AuthAck ok with no reason`() = assertVector(
        "5654433102000178000000000000000200000003010000",
        23,
        TcpFrame.AuthAck(seq = 2, transferId = "x", ok = true, reason = null),
    )

    @Test
    fun `vector - ItemEnd`() = assertVector(
        "56544331050001780000000000000003000000080000000000026162",
        28,
        TcpFrame.ItemEnd(seq = 3, transferId = "x", itemIndex = 0, sha256Hex = "ab"),
    )

    @Test
    fun `vector - ItemResult failed with error`() = assertVector(
        "56544331060001780000000000000004000000080000000100000145",
        28,
        TcpFrame.ItemResult(seq = 4, transferId = "x", itemIndex = 1, ok = false, errorMessage = "E"),
    )

    @Test
    fun `vector - Cancel`() = assertVector(
        "565443310800017800000000000000050000000400026e6f",
        24,
        TcpFrame.Cancel(seq = 5, transferId = "x", reason = "no"),
    )

    // ── Bounds vectors ─────────────────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `bounds - transferId longer than 256 bytes is rejected`() {
        TcpFrameCodec.encode(TcpFrame.TransferEnd(1, "a".repeat(257), true))
    }

    @Test
    fun `bounds - clean EOF at a frame boundary yields null, not an error`() {
        assertNull(TcpFrameCodec.readFrameOrNull(ByteArray(0).inputStream()))
    }

    @Test(expected = java.io.EOFException::class)
    fun `bounds - stream closing mid-frame is an error`() {
        val good = TcpFrameCodec.encode(TcpFrame.TransferEnd(1, "tx1", true))
        TcpFrameCodec.readFrameOrNull(good.copyOf(good.size - 1).inputStream())
    }

    @Test(expected = java.io.IOException::class)
    fun `bounds - bad magic is rejected`() {
        val good = TcpFrameCodec.encode(TcpFrame.TransferEnd(1, "tx1", true))
        good[0] = 0x00
        TcpFrameCodec.readFrameOrNull(good.inputStream())
    }

    @Test(expected = java.io.IOException::class)
    fun `bounds - zero-length transferId is rejected on read`() {
        // Hand-built frame claiming transferIdLen = 0.
        val frame = byteArrayOf(
            0x56, 0x54, 0x43, 0x31, // VTC1
            0x07, // TransferEnd
            0x00, 0x00, // transferIdLen = 0
            0, 0, 0, 0, 0, 0, 0, 1, // seq
            0, 0, 0, 1, // payloadLen
            0x01,
        )
        TcpFrameCodec.readFrameOrNull(frame.inputStream())
    }
}
