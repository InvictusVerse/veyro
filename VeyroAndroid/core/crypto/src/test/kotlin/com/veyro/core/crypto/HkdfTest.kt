package com.veyro.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFC 5869 test vectors, verbatim from Appendix A of the RFC.
 *
 * These are the RFC's own published values, not values produced by this implementation. The
 * expected bytes were also reproduced independently in PowerShell by hand-coding the RFC's
 * extract/expand pseudocode and confirming all three test cases matched, before this test was
 * written. Two independent derivations of the same constants is the point.
 */
class HkdfTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }

    private fun toHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // ── RFC 5869 A.1 — basic test case with SHA-256 ────────────────────────────────────────

    @Test
    fun `test case 1 extract`() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val prk = Hkdf.extract(salt, ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            toHex(prk),
        )
    }

    @Test
    fun `test case 1 expand`() {
        val prk = hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = Hkdf.expand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            toHex(okm),
        )
    }

    @Test
    fun `test case 1 extract then expand matches deriveKey`() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        assertArrayEquals(
            Hkdf.expand(Hkdf.extract(salt, ikm), info, 42),
            Hkdf.deriveKey(ikm, salt, info, 42),
        )
    }

    // ── RFC 5869 A.2 — longer inputs, output spans multiple blocks ─────────────────────────

    @Test
    fun `test case 2 extract`() {
        val ikm = hex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                "404142434445464748494a4b4c4d4e4f",
        )
        val salt = hex(
            "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f" +
                "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f" +
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf",
        )
        assertEquals(
            "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244",
            toHex(Hkdf.extract(salt, ikm)),
        )
    }

    @Test
    fun `test case 2 expand across multiple blocks`() {
        val prk = hex("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244")
        val info = hex(
            "b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                "d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
        )
        // 82 bytes needs three HMAC blocks; this is the vector that catches an off-by-one in
        // the block loop or a missing final truncation.
        val okm = Hkdf.expand(prk, info, 82)
        assertEquals(82, okm.size)
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            toHex(okm),
        )
    }

    // ── RFC 5869 A.3 — empty salt and info ─────────────────────────────────────────────────

    @Test
    fun `test case 3 empty salt and info`() {
        val ikm = hex("0b".repeat(22))
        val prk = Hkdf.extract(ByteArray(0), ikm)
        assertEquals(
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
            toHex(prk),
        )
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
            toHex(Hkdf.expand(prk, ByteArray(0), 42)),
        )
    }

    @Test
    fun `absent salt is treated as HashLen zero bytes`() {
        // RFC 5869 section 2.2: "if not provided, it is set to a string of HashLen zeros".
        // An empty array must therefore behave identically to 32 explicit zero bytes.
        val ikm = hex("0b".repeat(22))
        assertArrayEquals(
            Hkdf.extract(ByteArray(32), ikm),
            Hkdf.extract(ByteArray(0), ikm),
        )
    }

    // ── Structural properties ──────────────────────────────────────────────────────────────

    @Test
    fun `output is truncated to the requested length`() {
        val prk = Hkdf.extract(ByteArray(0), hex("0b".repeat(22)))
        for (len in intArrayOf(1, 16, 31, 32, 33, 64, 100)) {
            assertEquals(len, Hkdf.expand(prk, ByteArray(0), len).size)
        }
    }

    @Test
    fun `output length 32 equals the first block exactly`() {
        val prk = Hkdf.extract(ByteArray(0), hex("0b".repeat(22)))
        val a = Hkdf.expand(prk, ByteArray(0), 32)
        val b = Hkdf.expand(prk, ByteArray(0), 64).copyOf(32)
        assertArrayEquals("a longer expansion must share the same first block", a, b)
    }

    @Test
    fun `different info produces different output`() {
        val prk = Hkdf.extract(ByteArray(0), hex("0b".repeat(22)))
        val a = Hkdf.expand(prk, "veyro-sas-v1".toByteArray(), 32)
        val b = Hkdf.expand(prk, "veyro-pair-v1".toByteArray(), 32)
        assertTrue("info must provide domain separation", !a.contentEquals(b))
    }

    @Test
    fun `different salt produces different output`() {
        val ikm = hex("0b".repeat(22))
        val a = Hkdf.extract(hex("00010203"), ikm)
        val b = Hkdf.extract(hex("00010204"), ikm)
        assertTrue("a differing salt byte must change the PRK", !a.contentEquals(b))
    }

    @Test
    fun `zero-byte salt and an absent salt really are equivalent`() {
        // This is a genuine property of HMAC, not a quirk worth guarding against: HMAC pads any
        // key shorter than the 64-byte block with zeros, so a 1-byte 0x00 key, a 32-byte all-zero
        // key and no key at all all expand to the same 64 zero bytes. RFC 5869 leans on exactly
        // this equivalence when it says an absent salt means HashLen zero bytes.
        //
        // Asserted explicitly because it is surprising, and because someone "fixing" this later
        // by making absent salt mean an empty HMAC key would silently change every derived key.
        val ikm = hex("0b".repeat(22))
        assertArrayEquals(Hkdf.extract(ByteArray(0), ikm), Hkdf.extract(hex("00"), ikm))
        assertArrayEquals(Hkdf.extract(ByteArray(0), ikm), Hkdf.extract(ByteArray(32), ikm))
    }

    @Test
    fun `output length above the maximum is rejected`() {
        val prk = Hkdf.extract(ByteArray(0), hex("0b".repeat(22)))
        var threw = false
        try {
            Hkdf.expand(prk, ByteArray(0), 255 * 32 + 1)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("RFC 5869 caps output at 255 * HashLen", threw)
    }

    @Test
    fun `zero output length is rejected`() {
        val prk = Hkdf.extract(ByteArray(0), hex("0b".repeat(22)))
        var threw = false
        try {
            Hkdf.expand(prk, ByteArray(0), 0)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ── Protocol-specific derivations ──────────────────────────────────────────────────────

    @Test
    fun `short auth string is six digits`() {
        // Any transcript hash must produce exactly six characters, so the UI never has to pad or
        // truncate and both devices are always showing the same shape.
        for (i in 0..200) {
            val hash = ByteArray(32) { b -> (i * 7 + b).toByte() }
            val sas = VlpKdf.shortAuthString(hash)
            assertEquals("SAS '$sas' must be 6 chars", 6, sas.length)
            assertTrue("SAS '$sas' must be numeric", sas.all { it in '0'..'9' })
        }
    }

    @Test
    fun `short auth string is deterministic and transcript-sensitive`() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 1).toByte() }
        assertEquals(VlpKdf.shortAuthString(a), VlpKdf.shortAuthString(a.copyOf()))
        assertTrue(
            "a spliced handshake must produce a different SAS, or the MITM check is useless",
            VlpKdf.shortAuthString(a) != VlpKdf.shortAuthString(b),
        )
    }

    @Test
    fun `pairing secret is 32 bytes and distinct from the sas material`() {
        val hash = ByteArray(32) { (it * 3).toByte() }
        assertEquals(32, VlpKdf.pairingSecret(hash).size)
        assertEquals(16, VlpKdf.sessionId(hash).size)
        assertTrue(
            !VlpKdf.pairingSecret(hash).contentEquals(VlpKdf.sessionId(hash)),
        )
    }

    @Test
    fun `presence token is 8 bytes and rotates per epoch`() {
        val secret = ByteArray(32) { it.toByte() }
        val t1 = VlpKdf.presenceToken(secret, 100)
        val t2 = VlpKdf.presenceToken(secret, 101)
        assertEquals(8, t1.size)
        assertTrue("token must change every epoch, or it is a stable identifier", !t1.contentEquals(t2))
        assertArrayEquals("token must be recomputable by the peer", t1, VlpKdf.presenceToken(secret, 100))
    }

    @Test
    fun `presence token differs between pairing secrets`() {
        val a = VlpKdf.presenceToken(ByteArray(32) { 1 }, 100)
        val b = VlpKdf.presenceToken(ByteArray(32) { 2 }, 100)
        assertTrue(!a.contentEquals(b))
    }
}
