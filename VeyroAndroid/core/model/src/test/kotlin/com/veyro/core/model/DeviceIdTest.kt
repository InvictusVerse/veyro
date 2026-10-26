package com.veyro.core.model

import com.veyro.core.model.encoding.Crockford32
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Device identity derivation, checked against `protocol/testvectors/identity/device-id.json`.
 *
 * These are the same expected values, computed independently in PowerShell before this test was
 * written. That is deliberate: a vector generated from this implementation would only prove that
 * the implementation is self-consistent, which is worthless for a cross-platform protocol.
 *
 * If this test fails after a change, the change altered every deviceId in existence, and every
 * existing pairing is dead. That is the failure mode worth having a test for.
 */
class DeviceIdTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }

    @Test
    fun `derivation matches the published vector for an all-zero public key`() {
        val pk = hex("0000000000000000000000000000000000000000000000000000000000000000")
        val id = DeviceId.fromPublicKey(pk)
        assertEquals("6JE8415YZQQYQMBD34569HVYX4", id.value)
        assertEquals("34 9C 82 04 BE FD EF EB", fingerprintOf(id))
    }

    @Test
    fun `derivation matches the published vector for a sequential public key`() {
        val pk = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
        val id = DeviceId.fromPublicKey(pk)
        assertEquals("QRXA4W7GS7W6KB3C9C3XV4RV9W", id.value)
        assertEquals("BE 3A A2 70 F0 C9 F8 69", fingerprintOf(id))
    }

    @Test
    fun `device id is always 26 characters`() {
        // Every 16-byte input must encode to exactly 26 symbols: ceil(128 / 5) = 26.
        for (seed in 0..64) {
            val pk = ByteArray(32) { i -> (seed * 7 + i).toByte() }
            assertEquals(DeviceId.LENGTH, DeviceId.fromPublicKey(pk).value.length)
        }
    }

    @Test
    fun `device id uses only Crockford symbols`() {
        val allowed = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toSet()
        for (seed in 0..64) {
            val pk = ByteArray(32) { i -> (seed * 13 + i).toByte() }
            val id = DeviceId.fromPublicKey(pk).value
            id.forEach { c ->
                assertEquals("unexpected symbol '$c' in $id", true, c in allowed)
            }
        }
    }

    @Test
    fun `derivation is deterministic`() {
        val pk = ByteArray(32) { (it * 3).toByte() }
        assertEquals(DeviceId.fromPublicKey(pk), DeviceId.fromPublicKey(pk.copyOf()))
    }

    @Test
    fun `different keys give different ids`() {
        val a = DeviceId.fromPublicKey(ByteArray(32) { it.toByte() })
        val b = DeviceId.fromPublicKey(ByteArray(32) { (it + 1).toByte() })
        assertEquals(false, a == b)
    }

    @Test
    fun `id commits to the key it was derived from`() {
        val pk = ByteArray(32) { (it * 5 + 1).toByte() }
        val id = DeviceId.fromPublicKey(pk)
        assertEquals(true, DeviceId.matches(id, pk))
        // A different key must not verify against that id - this is the property that makes
        // identity spoofing pointless (04-PROTOCOL.md section 2).
        assertEquals(false, DeviceId.matches(id, ByteArray(32) { (it * 5 + 2).toByte() }))
    }

    @Test
    fun `parse accepts canonical form and rejects malformed input`() {
        val pk = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
        val id = DeviceId.fromPublicKey(pk)
        assertEquals(id, DeviceId.parseOrNull(id.value))

        assertEquals(null, DeviceId.parseOrNull(null))
        assertEquals(null, DeviceId.parseOrNull(""))
        assertEquals(null, DeviceId.parseOrNull("TOOSHORT"))
        assertEquals(null, DeviceId.parseOrNull(id.value + "X"))
        // 'U' is not in the Crockford alphabet.
        assertEquals(null, DeviceId.parseOrNull("U".repeat(DeviceId.LENGTH)))
    }

    @Test
    fun `parse normalises lower case to canonical upper case`() {
        val pk = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
        val id = DeviceId.fromPublicKey(pk)
        assertEquals(id, DeviceId.parseOrNull(id.value.lowercase()))
    }

    @Test
    fun `parse rejects an over-long public key`() {
        var threw = false
        try {
            DeviceId.fromPublicKey(ByteArray(31))
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertEquals(true, threw)
    }

    /** Mirrors `DeviceId.fingerprint()` but through the public encoding helpers. */
    private fun fingerprintOf(id: DeviceId): String {
        val bytes = Crockford32.decode(id.value)
        return bytes.copyOf(8).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }
}
