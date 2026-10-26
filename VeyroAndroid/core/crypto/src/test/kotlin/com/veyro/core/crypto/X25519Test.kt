package com.veyro.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X25519 against the RFC 7748 published vectors.
 *
 * These are the RFC's own values. If [X25519] ever disagrees with them, the implementation is
 * wrong regardless of how confident anyone is - that is the entire value of a published vector.
 *
 * The base-point and iterated vectors are included deliberately. The iterated one runs the ladder
 * 1,000 times and is the strongest cheap check that carries and reduction are correct: a subtle
 * bug in either usually survives small inputs and shows up in long chains.
 */
class X25519Test {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // ── RFC 7748 section 5.2, vector 1 ─────────────────────────────────────────────────────

    @Test
    fun `rfc 7748 vector 1`() {
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val uCoord = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            toHex(X25519.agree(scalar, uCoord)),
        )
    }

    // ── RFC 7748 section 5.2, vector 2 ─────────────────────────────────────────────────────

    @Test
    fun `rfc 7748 vector 2`() {
        val scalar = hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d")
        val uCoord = hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            toHex(X25519.agree(scalar, uCoord)),
        )
    }

    // ── RFC 7748 section 5.2, iterated ─────────────────────────────────────────────────────

    @Test
    fun `rfc 7748 iterated after 1 and after 1000 rounds`() {
        var k = hex("0900000000000000000000000000000000000000000000000000000000000000")
        var u = k.copyOf()

        for (i in 1..1000) {
            val result = X25519.agree(k, u)
            u = k
            k = result
            if (i == 1) {
                assertEquals(
                    "after 1 iteration",
                    "422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079",
                    toHex(k),
                )
            }
        }
        assertEquals(
            "after 1000 iterations",
            "684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51",
            toHex(k),
        )
    }

    // ── RFC 7748 section 6.1, Diffie-Hellman ───────────────────────────────────────────────

    @Test
    fun `rfc 7748 diffie-hellman key exchange`() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePubExpected = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPubExpected = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
        val sharedExpected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

        val alicePub = X25519.publicKeyOf(alicePriv)
        val bobPub = X25519.publicKeyOf(bobPriv)
        assertEquals("alice public key", alicePubExpected, toHex(alicePub))
        assertEquals("bob public key", bobPubExpected, toHex(bobPub))

        // Both directions must agree - this is the property the whole handshake rests on.
        assertEquals("alice -> bob", sharedExpected, toHex(X25519.agree(alicePriv, bobPub)))
        assertEquals("bob -> alice", sharedExpected, toHex(X25519.agree(bobPriv, alicePub)))
    }

    // ── Structural properties ──────────────────────────────────────────────────────────────

    @Test
    fun `base point constant is u equals 9 little-endian`() {
        // RFC 7748 section 4.1: the base point is u = 9, and RFC 7748 is little-endian, so it is
        // first byte 9, not last. Getting this backwards produces a valid-looking key that no
        // other implementation will agree with.
        assertEquals(32, X25519.BASE_POINT.size)
        assertEquals(9, X25519.BASE_POINT[0].toInt())
        for (i in 1 until 32) assertEquals(0, X25519.BASE_POINT[i].toInt())
    }

    @Test
    fun `public key derivation matches the base point definition`() {
        // publicKeyOf(k) must equal agree(k, 9). If these ever diverge, one of them is using a
        // different base point and the two sides of a handshake will never agree.
        val priv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        assertArrayEquals(X25519.publicKeyOf(priv), X25519.agree(priv, X25519.BASE_POINT))
    }

    @Test
    fun `generated keys are 32 bytes`() {
        repeat(16) {
            val priv = X25519.generatePrivateKey()
            assertEquals(32, priv.size)
            assertEquals(32, X25519.publicKeyOf(priv).size)
        }
    }

    @Test
    fun `generated keys are distinct`() {
        val keys = (1..32).map { toHex(X25519.generatePrivateKey()) }
        assertEquals("CSPRNG produced a duplicate", keys.size, keys.toSet().size)
    }

    @Test
    fun `agreement is symmetric for random pairs`() {
        repeat(8) {
            val a = X25519.generatePrivateKey()
            val b = X25519.generatePrivateKey()
            assertArrayEquals(
                X25519.agree(a, X25519.publicKeyOf(b)),
                X25519.agree(b, X25519.publicKeyOf(a)),
            )
        }
    }

    @Test
    fun `different peers produce different shared secrets`() {
        val a = X25519.generatePrivateKey()
        val b = X25519.generatePrivateKey()
        val c = X25519.generatePrivateKey()
        assertFalse(
            X25519.agree(a, X25519.publicKeyOf(b))
                .contentEquals(X25519.agree(a, X25519.publicKeyOf(c))),
        )
    }

    // ── Rejection paths ────────────────────────────────────────────────────────────────────

    @Test
    fun `all zero public key is rejected as a low-order point`() {
        // The classic attack: send u = 0 and the shared secret becomes zero, which the attacker
        // knows. Accepting it hands them the session key.
        val priv = X25519.generatePrivateKey()
        var message: String? = null
        try {
            X25519.agree(priv, ByteArray(32))
        } catch (e: IllegalArgumentException) {
            message = e.message
        }
        assertTrue("all-zero public key must be rejected", message != null)
        assertTrue(
            "the rejection should name the cause: $message",
            message!!.contains("low-order", ignoreCase = true),
        )
    }

    @Test
    fun `known low-order points are all rejected`() {
        // The standard small-order points on Curve25519, from the "May the Fourth" set.
        val lowOrder = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        )
        for (hexPoint in lowOrder) {
            assertTrue(
                "low-order point $hexPoint must be rejected",
                X25519.isLowOrder(hex(hexPoint)),
            )
        }
    }

    @Test
    fun `a normal public key is not flagged as low order`() {
        val pub = X25519.publicKeyOf(X25519.generatePrivateKey())
        assertFalse(X25519.isLowOrder(pub))
    }

    @Test
    fun `wrong key sizes are rejected with a clear message`() {
        for (size in intArrayOf(0, 16, 31, 33, 64)) {
            var threw = false
            try {
                X25519.agree(ByteArray(size), ByteArray(32))
            } catch (e: IllegalArgumentException) {
                threw = true
                assertTrue("message should name the offending input", e.message!!.contains("private key"))
            }
            assertTrue("private key of $size bytes must be rejected", threw)
        }

        var threw = false
        try {
            X25519.publicKeyOf(ByteArray(31))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `a public key is not accepted as a private key by accident`() {
        // Sanity guard: both are 32 bytes, so nothing but the algorithm distinguishes them.
        // Deriving a public key from a public key gives a different, valid-looking value - it must
        // not throw, but it also must not equal the input, which would indicate it was a no-op.
        val pub = X25519.publicKeyOf(X25519.generatePrivateKey())
        val derived = X25519.publicKeyOf(pub)
        assertEquals(32, derived.size)
        assertFalse(derived.contentEquals(pub))
    }
}
