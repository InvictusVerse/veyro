package com.veyro.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing and reassembly.
 *
 * The reassembler is the component that sees hostile bytes after decryption, so the tests that
 * matter are the ones about declared lengths and fragment boundaries. A bug here is either a
 * denial of service (allocate what the peer claims) or a stall (lose sync and never recover).
 */
class FrameCodecTest {

    private fun reassemble(pieces: List<ByteArray>): List<FrameCodec.Decoded> {
        val r = FrameCodec.Reassembler()
        return pieces.flatMap { r.offer(it) }
    }

    private fun frames(decoded: List<FrameCodec.Decoded>) =
        decoded.filterIsInstance<FrameCodec.Decoded.Frame>()

    // ── Round trip ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a small frame round trips in one piece`() {
        val payload = "control message".toByteArray()
        val pieces = FrameCodec.encodeFrame(ProtocolConstants.FRAME_CONTROL, payload)
        assertEquals("a small frame must not be fragmented", 1, pieces.size)

        val out = frames(reassemble(pieces))
        assertEquals(1, out.size)
        assertEquals(ProtocolConstants.FRAME_CONTROL, out[0].type)
        assertArrayEquals(payload, out[0].payload)
    }

    @Test
    fun `an empty payload round trips`() {
        val out = frames(reassemble(FrameCodec.encodeFrame(ProtocolConstants.FRAME_KEEPALIVE, ByteArray(0))))
        assertEquals(1, out.size)
        assertEquals(0, out[0].payload.size)
    }

    @Test
    fun `a frame larger than one noise message is fragmented and rejoined`() {
        // A 4 MiB chunk cannot be a single Noise message. If fragmentation were left to callers,
        // someone would eventually get the 65535 limit wrong on one platform only.
        val payload = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val pieces = FrameCodec.encodeFrame(ProtocolConstants.FRAME_BULK, payload)
        assertTrue("expected fragmentation, got ${pieces.size} piece(s)", pieces.size > 1)
        pieces.forEach {
            assertTrue("a fragment exceeds the Noise limit", it.size <= FrameCodec.MAX_NOISE_PLAINTEXT)
        }

        val r = FrameCodec.Reassembler(maxPayload = 8 * 1024 * 1024)
        val out = pieces.flatMap { r.offer(it) }.filterIsInstance<FrameCodec.Decoded.Frame>()
        assertEquals(1, out.size)
        assertArrayEquals(payload, out[0].payload)
    }

    @Test
    fun `a frame exactly at the fragment boundary round trips`() {
        // Off-by-one at the boundary is the likeliest fragmentation bug, so both sides of it are
        // exercised explicitly.
        for (delta in intArrayOf(-1, 0, 1)) {
            val size = FrameCodec.MAX_NOISE_PLAINTEXT - FrameCodec.APP_HEADER_BYTES + delta
            val payload = ByteArray(size) { 7 }
            val out = frames(reassemble(FrameCodec.encodeFrame(ProtocolConstants.FRAME_BULK, payload)))
            assertEquals("delta $delta", 1, out.size)
            assertEquals("delta $delta", size, out[0].payload.size)
        }
    }

    @Test
    fun `several frames in one piece are all returned`() {
        val a = FrameCodec.encodeFrame(ProtocolConstants.FRAME_CONTROL, "one".toByteArray()).single()
        val b = FrameCodec.encodeFrame(ProtocolConstants.FRAME_CONTROL, "two".toByteArray()).single()
        val out = frames(reassemble(listOf(a + b)))
        assertEquals(2, out.size)
        assertArrayEquals("one".toByteArray(), out[0].payload)
        assertArrayEquals("two".toByteArray(), out[1].payload)
    }

    @Test
    fun `a frame split at every possible byte still reassembles`() {
        // Real sockets split wherever they like. A reassembler that only works on tidy boundaries
        // fails intermittently under load, which is the worst kind of bug to chase.
        val payload = "the quick brown fox jumps over the lazy dog".toByteArray()
        val whole = FrameCodec.encodeFrame(ProtocolConstants.FRAME_CONTROL, payload).single()

        for (split in 1 until whole.size) {
            val out = frames(
                reassemble(listOf(whole.copyOfRange(0, split), whole.copyOfRange(split, whole.size))),
            )
            assertEquals("split at $split", 1, out.size)
            assertArrayEquals("split at $split", payload, out[0].payload)
        }
    }

    @Test
    fun `byte at a time delivery works`() {
        val payload = "dribble".toByteArray()
        val whole = FrameCodec.encodeFrame(ProtocolConstants.FRAME_CONTROL, payload).single()
        val r = FrameCodec.Reassembler()
        val collected = mutableListOf<FrameCodec.Decoded>()
        for (b in whole) collected += r.offer(byteArrayOf(b))
        val out = collected.filterIsInstance<FrameCodec.Decoded.Frame>()
        assertEquals(1, out.size)
        assertArrayEquals(payload, out[0].payload)
    }

    // ── Rejection ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown frame type is rejected`() {
        val bad = byteArrayOf(0x7F, 0, 0, 0, 0)
        val out = FrameCodec.Reassembler().offer(bad)
        assertTrue(out.any { it is FrameCodec.Decoded.Invalid && it.reason == FrameCodec.Reason.UNKNOWN_TYPE })
    }

    @Test
    fun `an absurd declared length is rejected before allocating`() {
        // A declared 2 GB payload from a hostile peer must not become an allocation. Checking the
        // length before reserving is the difference between a rejected frame and a one-packet
        // denial of service.
        val bad = byteArrayOf(ProtocolConstants.FRAME_CONTROL, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val out = FrameCodec.Reassembler().offer(bad)
        assertTrue(
            out.any { it is FrameCodec.Decoded.Invalid && it.reason == FrameCodec.Reason.LENGTH_TOO_LARGE },
        )
    }

    @Test
    fun `a length above the configured cap is rejected`() {
        val r = FrameCodec.Reassembler(maxPayload = 1024)
        val header = byteArrayOf(ProtocolConstants.FRAME_CONTROL, 0, 0, 0x10, 0) // 4096
        val out = r.offer(header)
        assertTrue(
            out.any { it is FrameCodec.Decoded.Invalid && it.reason == FrameCodec.Reason.LENGTH_TOO_LARGE },
        )
    }

    @Test
    fun `a partial header reports incomplete rather than failing`() {
        val r = FrameCodec.Reassembler()
        val out = r.offer(byteArrayOf(ProtocolConstants.FRAME_CONTROL, 0, 0))
        assertTrue("a partial header is normal, not an error", out.none { it is FrameCodec.Decoded.Invalid })
        assertEquals(3, r.pending())
    }

    @Test
    fun `a partial payload reports incomplete`() {
        val whole = FrameCodec.encodeFrame(ProtocolConstants.FRAME_CONTROL, ByteArray(100)).single()
        val r = FrameCodec.Reassembler()
        val out = r.offer(whole.copyOfRange(0, 50))
        assertTrue(out.contains(FrameCodec.Decoded.Incomplete))
        assertEquals(50, r.pending())
    }

    @Test
    fun `reset clears buffered bytes`() {
        val r = FrameCodec.Reassembler()
        r.offer(byteArrayOf(ProtocolConstants.FRAME_CONTROL, 0, 0, 0, 100))
        assertTrue(r.pending() > 0)
        r.reset()
        assertEquals(0, r.pending())
    }

    // ── Wire prefix ────────────────────────────────────────────────────────────────────────

    @Test
    fun `wire prefix round trips`() {
        for (len in intArrayOf(1, 255, 256, 65535, 1000)) {
            assertEquals(len, FrameCodec.readWirePrefix(FrameCodec.wirePrefix(len)))
        }
    }

    @Test
    fun `wire prefix rejects out-of-range lengths`() {
        for (len in intArrayOf(0, -1, 65536, Int.MAX_VALUE)) {
            var threw = false
            try {
                FrameCodec.wirePrefix(len)
            } catch (expected: IllegalArgumentException) {
                threw = true
            }
            assertTrue("length $len should be rejected", threw)
        }
    }

    @Test
    fun `wire prefix is big endian`() {
        // VLP is big-endian everywhere except the X25519 scalars, which are RFC 7748''s
        // little-endian. Mixing the two up is a real trap, so this is pinned.
        //
        // The value stays within the Noise message limit on purpose: 0x01020304 would be a tidier
        // bit pattern but wirePrefix correctly refuses it, since no Noise message can exceed
        // 65535 bytes. Testing the layout and testing the range guard are separate concerns.
        assertArrayEquals(byteArrayOf(0, 0, 0x01, 0x02), FrameCodec.wirePrefix(0x0102))
        assertArrayEquals(byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte()), FrameCodec.wirePrefix(65535))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), FrameCodec.wirePrefix(1))
    }

    @Test
    fun `reading a prefix needs four bytes`() {
        var threw = false
        try {
            FrameCodec.readWirePrefix(byteArrayOf(1, 2))
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
