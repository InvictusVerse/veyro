package com.veyro.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

class FrameCryptoTest {

    @Test
    fun `bootstrap key derives deterministically from matching pin and device IDs`() {
        val pin = "123456"
        val initiator = "dev-alice-1234"
        val target = "dev-bob-5678"

        val keyInitiator = FrameCrypto.deriveBootstrapKey(pin, initiator, target)
        val keyTarget = FrameCrypto.deriveBootstrapKey(pin, initiator, target)

        assertEquals("AES", keyInitiator.algorithm)
        assertEquals(32, keyInitiator.encoded.size)
        assertEquals(keyInitiator, keyTarget)
    }

    @Test
    fun `salt is canonical and reversing roles produces the identical key`() {
        val pin = "654321"
        val dev1 = "dev-alpha"
        val dev2 = "dev-beta"

        val keyDirect = FrameCrypto.deriveBootstrapKey(pin, dev1, dev2)
        val keyReversed = FrameCrypto.deriveBootstrapKey(pin, dev2, dev1)

        assertTrue(
            "Reversing initiator and target roles must produce the identical bootstrap key",
            keyDirect.encoded.contentEquals(keyReversed.encoded),
        )
    }

    @Test
    fun `device IDs in salt are case-insensitive`() {
        val pin = "999888"
        val keyLower = FrameCrypto.deriveBootstrapKey(pin, "device-a", "device-b")
        val keyUpper = FrameCrypto.deriveBootstrapKey(pin, "DEVICE-A", "Device-B")

        assertEquals(keyLower, keyUpper)
    }

    @Test
    fun `plaintext sealing and opening roundtrips cleanly`() {
        val key = FrameCrypto.newSessionSecret().let { FrameCrypto.sessionKeyFromBytes(it) }
        val payload = """{"msgId":"test-123","action":"hello"}"""
        val senderId = "sender-phone"

        val sealed = FrameCrypto.seal(payload, key, encrypt = false, fromDeviceId = senderId)
        assertFalse("Frame should not be marked encrypted", sealed.encrypted)

        val opened = FrameCrypto.open(sealed, key)
        assertEquals(payload, opened)
    }

    @Test
    fun `encrypted sealing and opening roundtrips cleanly`() {
        val secret = FrameCrypto.newSessionSecret()
        val key = FrameCrypto.sessionKeyFromBytes(secret)
        val payload = """{"secretData":"sensitive_file_chunk_content_abc_123"}"""
        val senderId = "sender-laptop"

        val sealed = FrameCrypto.seal(payload, key, encrypt = true, fromDeviceId = senderId)
        assertTrue("Frame must be marked encrypted", sealed.encrypted)
        assertFalse(
            "Ciphertext body must not contain plaintext string",
            String(sealed.body, Charsets.UTF_8).contains("sensitive_file_chunk"),
        )

        val opened = FrameCrypto.open(sealed, key)
        assertEquals(payload, opened)
    }

    @Test
    fun `tampering with ciphertext body causes authentication failure`() {
        val key = FrameCrypto.sessionKeyFromBytes(FrameCrypto.newSessionSecret())
        val sealed = FrameCrypto.seal("payload data", key, encrypt = true, fromDeviceId = "dev-1")

        // Corrupt one byte of ciphertext body
        val corruptedBody = sealed.body.copyOf()
        corruptedBody[0] = (corruptedBody[0].toInt() xor 0xFF).toByte()

        val tamperedFrame = sealed.copy(body = corruptedBody)
        val opened = FrameCrypto.open(tamperedFrame, key)
        assertNull("Opening tampered frame must return null", opened)
    }

    @Test
    fun `tampering with mac causes authentication failure`() {
        val key = FrameCrypto.sessionKeyFromBytes(FrameCrypto.newSessionSecret())
        val sealed = FrameCrypto.seal("payload data", key, encrypt = true, fromDeviceId = "dev-1")

        val corruptedMac = sealed.mac.copyOf()
        corruptedMac[corruptedMac.lastIndex] = (corruptedMac[corruptedMac.lastIndex].toInt() xor 0x01).toByte()

        val tamperedFrame = sealed.copy(mac = corruptedMac)
        assertNull("Opening frame with corrupted MAC must return null", FrameCrypto.open(tamperedFrame, key))
    }

    @Test
    fun `tampering with fromDeviceId causes mac verification failure`() {
        val key = FrameCrypto.sessionKeyFromBytes(FrameCrypto.newSessionSecret())
        val sealed = FrameCrypto.seal("transfer chunk", key, encrypt = true, fromDeviceId = "alice")

        // Frame replayed under a different claimed sender id
        val spoofedFrame = sealed.copy(fromDeviceId = "eve")
        assertNull("Frame attributed to spoofed sender must fail MAC verification", FrameCrypto.open(spoofedFrame, key))
    }

    @Test
    fun `wrong key fails authentication and returns null`() {
        val key1 = FrameCrypto.sessionKeyFromBytes(FrameCrypto.newSessionSecret())
        val key2 = FrameCrypto.sessionKeyFromBytes(FrameCrypto.newSessionSecret())

        val sealed = FrameCrypto.seal("some data", key1, encrypt = true, fromDeviceId = "dev")
        assertNull("Opening with wrong key must return null", FrameCrypto.open(sealed, key2))
    }

    @Test
    fun `newPin produces 6 digits`() {
        for (i in 0 until 50) {
            val pin = FrameCrypto.newPin()
            assertEquals(6, pin.length)
            assertTrue("Pin must be numeric: $pin", pin.all { it.isDigit() })
        }
    }

    @Test
    fun `nonce cache rejects replayed nonces`() {
        val cache = NonceCache(capacity = 10)
        val nonce = "random-nonce-abc"

        assertTrue("First check of nonce must succeed", cache.checkAndStore(nonce))
        assertFalse("Second check of same nonce must be rejected as replay", cache.checkAndStore(nonce))

        cache.clear()
        assertTrue("After clear, nonce is accepted again", cache.checkAndStore(nonce))
    }
}
