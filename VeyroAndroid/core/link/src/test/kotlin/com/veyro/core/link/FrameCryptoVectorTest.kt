package com.veyro.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FrameCrypto`, checked against `protocol/testvectors/link/framecrypto.json`.
 *
 * The expected values were produced with .NET BCL primitives in a throwaway program referencing
 * neither implementation, and the harness was sanity-checked against the published SHA-256 digest
 * of "abc". .NET's and the JCE's are independent implementations of the same published standards,
 * so agreement between them is meaningful; a value captured from either one alone would not be.
 *
 * The Windows suite loads the same JSON and asserts the same constants.
 */
class FrameCryptoVectorTest {

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }

    private val bootstrapKeyHex =
        "d463eb25b4cb7f915557dcf563b4910aa87aabb0f867e9add81d76711310e7ba"
    private val sessionKeyHex =
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val nonceHex = "000102030405060708090a0b"
    private val payloadJson = """{"t":"ping","nonce":"abc"}"""
    private val fromDeviceId = "ALPHA"

    @Test
    fun `vector - bootstrap key derivation`() {
        val key = FrameCrypto.deriveBootstrapKey("123456", "ALPHA", "bravo")
        assertEquals(bootstrapKeyHex, hex(key.encoded))
    }

    @Test
    fun `vector - bootstrap key is identical with the roles reversed`() {
        // The salt is min(id):max(id) precisely so this holds. If it ever fails, pairing works in
        // one direction and silently fails in the other.
        val forward = FrameCrypto.deriveBootstrapKey("123456", "ALPHA", "bravo")
        val reverse = FrameCrypto.deriveBootstrapKey("123456", "bravo", "ALPHA")
        assertEquals(hex(forward.encoded), hex(reverse.encoded))
        assertEquals(bootstrapKeyHex, hex(reverse.encoded))
    }

    @Test
    fun `vector - a different PIN derives a different key`() {
        val a = FrameCrypto.deriveBootstrapKey("123456", "ALPHA", "bravo")
        val b = FrameCrypto.deriveBootstrapKey("654321", "ALPHA", "bravo")
        assertNotEquals(hex(a.encoded), hex(b.encoded))
    }

    @Test
    fun `vector - pairing-mode MAC over plaintext body`() {
        val key = FrameCrypto.sessionKeyFromBytes(unhex(bootstrapKeyHex))
        val body = payloadJson.toByteArray(Charsets.UTF_8)
        val nonce = unhex(nonceHex)

        assertEquals("7b2274223a2270696e67222c226e6f6e6365223a22616263227d", hex(body))

        val mac = FrameCrypto.hmac(
            key,
            fromDeviceId.toByteArray(Charsets.UTF_8) + nonce + body,
        )
        assertEquals("731b1a4bf68ce0480ecd150ff82a1a53859ee51b1f550a60ceeaa18e41fd8870", hex(mac))
    }

    @Test
    fun `vector - session-mode body is ciphertext followed by tag`() {
        // Seal generates its own nonce, so this vector is verified by opening a frame built from
        // the published bytes rather than by re-sealing.
        val key = FrameCrypto.sessionKeyFromBytes(unhex(sessionKeyHex))
        val body = unhex(
            "3c20a239ffc7b272e326b5a793871703e0b3a50ed21a3d1f1a1a" +
                "4d40dfef1e88a818314ae226db1ad68f",
        )
        val nonce = unhex(nonceHex)
        val mac = FrameCrypto.hmac(
            key,
            fromDeviceId.toByteArray(Charsets.UTF_8) + nonce + body,
        )
        assertEquals("a0845f3ced0fffd974203f1a596eae1915f6449c8a9268cfd4d26f558f8be7c4", hex(mac))

        val frame = SealedFrame(
            nonce = nonce,
            body = body,
            mac = mac,
            encrypted = true,
            fromDeviceId = fromDeviceId,
        )
        assertEquals(payloadJson, FrameCrypto.open(frame, key))
    }

    @Test
    fun `seal then open round-trips in both modes`() {
        val key = FrameCrypto.sessionKeyFromBytes(unhex(sessionKeyHex))

        val sealed = FrameCrypto.seal(payloadJson, key, encrypt = true, fromDeviceId = fromDeviceId)
        assertEquals(payloadJson, FrameCrypto.open(sealed, key))

        val plain = FrameCrypto.seal(payloadJson, key, encrypt = false, fromDeviceId = fromDeviceId)
        assertEquals(payloadJson, FrameCrypto.open(plain, key))
    }

    @Test
    fun `rejection - altered MAC does not open`() {
        val key = FrameCrypto.sessionKeyFromBytes(unhex(sessionKeyHex))
        val sealed = FrameCrypto.seal(payloadJson, key, true, fromDeviceId)
        val badMac = sealed.mac.copyOf()
        badMac[badMac.size - 1] = (badMac[badMac.size - 1].toInt() xor 0xFF).toByte()
        assertNull(FrameCrypto.open(sealed.copy(mac = badMac), key))
    }

    @Test
    fun `rejection - a frame re-attributed to another sender does not open`() {
        // This is exactly what binding fromDeviceId into the MAC buys.
        val key = FrameCrypto.sessionKeyFromBytes(unhex(sessionKeyHex))
        val sealed = FrameCrypto.seal(payloadJson, key, true, fromDeviceId)
        assertNull(FrameCrypto.open(sealed.copy(fromDeviceId = "CHARLIE"), key))
    }

    @Test
    fun `rejection - altered ciphertext does not open`() {
        val key = FrameCrypto.sessionKeyFromBytes(unhex(sessionKeyHex))
        val sealed = FrameCrypto.seal(payloadJson, key, true, fromDeviceId)
        val badBody = sealed.body.copyOf()
        badBody[0] = (badBody[0].toInt() xor 0xFF).toByte()
        assertNull(FrameCrypto.open(sealed.copy(body = badBody), key))
    }

    @Test
    fun `rejection - open never throws on hostile input`() {
        val key = FrameCrypto.sessionKeyFromBytes(unhex(sessionKeyHex))
        assertNull(FrameCrypto.open(SealedFrame(ByteArray(12), ByteArray(0), ByteArray(32), true, "X"), key))
        assertNull(FrameCrypto.open(SealedFrame(ByteArray(12), byteArrayOf(1, 2, 3), ByteArray(32), true, "X"), key))
        assertNull(FrameCrypto.open(SealedFrame(ByteArray(12), ByteArray(64), ByteArray(0), true, "X"), key))
    }

    @Test
    fun `nonce cache rejects a replay and stays bounded`() {
        val cache = NonceCache(capacity = 4)

        assertTrue(cache.checkAndStore("a"))
        assertFalse(cache.checkAndStore("a"))

        assertTrue(cache.checkAndStore("b"))
        assertTrue(cache.checkAndStore("c"))
        assertTrue(cache.checkAndStore("d"))
        assertTrue(cache.checkAndStore("e")) // evicts the eldest

        assertFalse(cache.checkAndStore("b"))
        assertFalse(cache.checkAndStore("e"))
        assertTrue(cache.checkAndStore("a")) // was evicted, no longer recognised
    }

    @Test
    fun `newPin is always six digits`() {
        repeat(200) {
            val pin = FrameCrypto.newPin()
            assertEquals(6, pin.length)
            assertTrue(pin.all { it.isDigit() })
        }
    }

    @Test
    fun `sha256Hex matches the published digest`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FrameCrypto.sha256Hex("abc".toByteArray(Charsets.UTF_8)),
        )
    }
}
