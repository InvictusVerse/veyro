package com.veyro.core.protocol

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One direction of an AEAD stream (ADR-058, 04-PROTOCOL.md 5.5): one key, one implicit counter.
 *
 * Nonce = `u32be(streamId) || u64be(counter)`; the counter starts at 0 and advances by one after
 * every successful [seal] or [open]. The caller never supplies a counter, so it cannot reuse a
 * nonce (invariant 1). Session frames use stream id 0; VTC3 uses 1 (sender to receiver) and 2.
 *
 * Fail closed: after any failure the framer is poisoned and every later call throws
 * [AeadStreamException]. Not thread-safe by design; each owner wraps it in its own lock.
 */
public class AeadStreamFramer(key: ByteArray, private val streamId: Int, private val role: Role) {

    public enum class Role { SEAL, OPEN }

    private val rawKey: ByteArray = key.copyOf()
    private val keySpec: SecretKeySpec = ChaChaPoly.keySpec(rawKey)
    private val cipher: Cipher = ChaChaPoly.newCipher()
    private val nonce = ByteArray(ChaChaPoly.NONCE_SIZE)
    private var counter: Long = 0
    private var poisoned = false
    private var wiped = false

    init {
        require(key.size == ChaChaPoly.KEY_SIZE) { "AEAD key must be ${ChaChaPoly.KEY_SIZE} bytes" }
    }

    /** The counter the next [seal] or [open] uses. */
    public val nextCounter: Long get() = counter

    /** Seals `src[srcOff, srcOff+len)` into `dst` at `dstOff` with AAD `aad[0, aadLen)`. Returns `len + 16`. */
    public fun seal(aad: ByteArray, aadLen: Int, src: ByteArray, srcOff: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        check(role == Role.SEAL) { "seal on an OPEN framer" }
        ensureUsable()
        if (counter == Long.MAX_VALUE) fail("AEAD stream counter exhausted", null)
        if (len < 0 || dst.size - dstOff < len + ChaChaPoly.TAG_SIZE) fail("AEAD destination too small", null)
        return try {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(nonceFor(counter)))
            if (aadLen > 0) cipher.updateAAD(aad, 0, aadLen)
            val written = cipher.doFinal(src, srcOff, len, dst, dstOff)
            if (written != len + ChaChaPoly.TAG_SIZE) fail("AEAD seal produced an unexpected length", null)
            counter++
            written
        } catch (e: GeneralSecurityException) {
            fail("AEAD seal failed", e)
        } catch (e: IllegalStateException) {
            fail("AEAD seal failed", e)
        }
    }

    /**
     * Seals the concatenation `head[0, headLen) || body[bodyOff, bodyOff+bodyLen)` as one record,
     * without copying [body] first (the VTC3 chunk path: 12 bytes of chunk metadata, then the
     * chunk). Same nonce, counter and failure rules as [seal]. Returns `headLen + bodyLen + 16`.
     */
    public fun seal(
        aad: ByteArray,
        aadLen: Int,
        head: ByteArray,
        headLen: Int,
        body: ByteArray,
        bodyOff: Int,
        bodyLen: Int,
        dst: ByteArray,
        dstOff: Int,
    ): Int {
        check(role == Role.SEAL) { "seal on an OPEN framer" }
        ensureUsable()
        if (counter == Long.MAX_VALUE) fail("AEAD stream counter exhausted", null)
        val total = headLen + bodyLen
        if (headLen < 0 || bodyLen < 0 || dst.size - dstOff < total + ChaChaPoly.TAG_SIZE) fail("AEAD destination too small", null)
        return try {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(nonceFor(counter)))
            if (aadLen > 0) cipher.updateAAD(aad, 0, aadLen)
            // A provider may buffer in update() and emit everything in doFinal(); both are handled.
            var written = if (headLen > 0) cipher.update(head, 0, headLen, dst, dstOff) else 0
            written += cipher.doFinal(body, bodyOff, bodyLen, dst, dstOff + written)
            if (written != total + ChaChaPoly.TAG_SIZE) fail("AEAD seal produced an unexpected length", null)
            counter++
            written
        } catch (e: GeneralSecurityException) {
            fail("AEAD seal failed", e)
        } catch (e: IllegalStateException) {
            fail("AEAD seal failed", e)
        }
    }

    /** Opens `src[srcOff, srcOff+len)` (ciphertext || tag) into `dst` at `dstOff`. Returns `len - 16`. */
    public fun open(aad: ByteArray, aadLen: Int, src: ByteArray, srcOff: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        check(role == Role.OPEN) { "open on a SEAL framer" }
        ensureUsable()
        if (len < ChaChaPoly.TAG_SIZE) fail("AEAD record too short", null)
        if (dst.size - dstOff < len - ChaChaPoly.TAG_SIZE) fail("AEAD destination too small", null)
        if (counter == Long.MAX_VALUE) fail("AEAD stream counter exhausted", null)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(nonceFor(counter)))
            if (aadLen > 0) cipher.updateAAD(aad, 0, aadLen)
            val written = cipher.doFinal(src, srcOff, len, dst, dstOff)
            if (written != len - ChaChaPoly.TAG_SIZE) fail("AEAD open produced an unexpected length", null)
            counter++
            written
        } catch (e: GeneralSecurityException) {
            fail("AEAD record failed authentication", e)
        } catch (e: IllegalStateException) {
            fail("AEAD record failed authentication", e)
        } catch (e: IllegalArgumentException) {
            fail("AEAD record failed authentication", e)
        }
    }

    /** Tests only: moves the counter, to exercise exhaustion without 2^63 records. */
    internal fun setCounterForTest(value: Long) {
        counter = value
    }

    /** Zeroes the key copy this framer holds (best effort on the JVM) and disables it. */
    public fun wipe() {
        wiped = true
        rawKey.fill(0)
        nonce.fill(0)
    }

    private fun ensureUsable() {
        if (wiped) throw AeadStreamException("AEAD stream closed")
        if (poisoned) throw AeadStreamException("AEAD stream failed earlier")
    }

    private fun nonceFor(value: Long): ByteArray {
        nonce[0] = (streamId ushr 24).toByte()
        nonce[1] = (streamId ushr 16).toByte()
        nonce[2] = (streamId ushr 8).toByte()
        nonce[3] = streamId.toByte()
        for (i in 0 until 8) nonce[4 + i] = (value ushr (56 - 8 * i)).toByte()
        return nonce
    }

    private fun fail(message: String, cause: Throwable?): Nothing {
        poisoned = true
        throw AeadStreamException(message, cause)
    }
}
