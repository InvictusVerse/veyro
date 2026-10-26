package com.veyro.core.crypto

import com.veyro.core.common.Bytes
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Noise symmetric primitives: `CipherState`, `SymmetricState` and the hash/HKDF plumbing they
 * need, for the `25519_ChaChaPoly_SHA256` suite.
 *
 * This file implements the *symmetric* half of the Noise framework, which is deliberately separated
 * from the handshake patterns in [NoiseHandshake]: this part is mechanical and heavily reused,
 * while the patterns are where a mistake changes the security properties.
 *
 * ## Honesty about what this is
 *
 * Per ADR-004 this is first-party code over vetted primitives. It is **not** validated against the
 * official Noise test vectors yet, and that gate is still open - `07-STATUS.md` and
 * `12-TESTING.md` say so. The tests that do exist prove the properties that can be checked without
 * published vectors (both sides agree, tampering fails, replay fails, wrong key fails), which is
 * necessary but not sufficient.
 *
 * Until that gate closes, treat the handshake as unproven.
 *
 * ## Nonce handling
 *
 * Noise nonces are 64-bit counters encoded as 8 little-endian bytes in a 12-byte IETF nonce, with
 * the first 4 bytes zero. Getting that padding or endianness wrong produces a cipher that works
 * perfectly against itself and fails against every other implementation - the exact failure mode
 * the shared test vectors exist to catch.
 */
internal object NoiseConstants {
    const val HASH_LEN = 32
    const val KEY_LEN = 32
    const val TAG_LEN = 16
    const val MAX_NONCE = Long.MAX_VALUE
    const val PROTOCOL_NAME_KK = "Noise_KK_25519_ChaChaPoly_SHA256"
    const val PROTOCOL_NAME_XK = "Noise_XK_25519_ChaChaPoly_SHA256"
}

/**
 * A one-way AEAD cipher with a key and a nonce counter.
 *
 * ChaCha20-Poly1305 comes from the platform (`Cipher.getInstance("ChaCha20-Poly1305")`, available
 * from API 28 and so safe at minSdk 29). We supply the nonce rather than letting the provider pick
 * one, because Noise specifies the counter exactly.
 */
internal class CipherState {

    private var key: ByteArray? = null
    private var nonce: Long = 0

    fun initializeKey(k: ByteArray?) {
        key?.let { Bytes.wipe(it) }
        key = k?.copyOf()
        nonce = 0
    }

    fun hasKey(): Boolean = key != null

    fun setNonce(value: Long) {
        nonce = value
    }

    fun nonce(): Long = nonce

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext.copyOf()
        checkNonce()
        val out = aead(Cipher.ENCRYPT_MODE, k, nonce, ad, plaintext)
        nonce++
        return out
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext.copyOf()
        checkNonce()
        val out = aead(Cipher.DECRYPT_MODE, k, nonce, ad, ciphertext)
        nonce++
        return out
    }

    private fun checkNonce() {
        // Noise reserves 2^64-1 and requires the session to end rather than wrap. Wrapping would
        // reuse a nonce with the same key, which destroys confidentiality outright.
        if (nonce == NoiseConstants.MAX_NONCE) {
            throw IllegalStateException("Noise nonce exhausted; the session must be closed")
        }
    }

    fun clear() {
        key?.let { Bytes.wipe(it) }
        key = null
        nonce = 0
    }

    companion object {
        /**
         * Builds the 12-byte IETF nonce from Noise's 64-bit counter: 4 zero bytes, then the
         * counter **little-endian**.
         */
        fun nonceBytes(counter: Long): ByteArray {
            val out = ByteArray(12)
            for (i in 0 until 8) {
                out[4 + i] = ((counter ushr (8 * i)) and 0xFF).toByte()
            }
            return out
        }

        fun aead(
            mode: Int,
            key: ByteArray,
            counter: Long,
            ad: ByteArray,
            input: ByteArray,
        ): ByteArray {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val spec = IvParameterSpec(nonceBytes(counter))
            cipher.init(mode, SecretKeySpec(key, "ChaCha20"), spec)
            if (ad.isNotEmpty()) cipher.updateAAD(ad)
            return cipher.doFinal(input)
        }
    }
}

/**
 * Noise `SymmetricState`: the running chaining key and handshake hash, plus `MixKey`, `MixHash`
 * and the `Encrypt/DecryptAndHash` operations.
 *
 * The handshake hash `h` is the value every security property hangs off: it is what the SAS is
 * derived from, and it is what makes a spliced handshake produce different six digits on the two
 * screens. Every byte that goes over the wire during the handshake must be mixed into it.
 */
internal class SymmetricState(protocolName: String) {

    private val cipher = CipherState()
    private var chainingKey: ByteArray
    private var hash: ByteArray

    init {
        val nameBytes = protocolName.toByteArray(Charsets.US_ASCII)
        hash = if (nameBytes.size <= NoiseConstants.HASH_LEN) {
            ByteArray(NoiseConstants.HASH_LEN).also { nameBytes.copyInto(it) }
        } else {
            Bytes.sha256(nameBytes)
        }
        chainingKey = hash.copyOf()
    }

    fun mixHash(data: ByteArray) {
        hash = Bytes.sha256(hash, data)
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        // HKDF with the chaining key as salt, producing two outputs: the new chaining key and the
        // cipher key. This is Noise's HKDF(ck, ikm, 2).
        val prk = Hkdf.extract(chainingKey, inputKeyMaterial)
        val output = Hkdf.expand(prk, ByteArray(0), NoiseConstants.HASH_LEN * 2)
        Bytes.wipe(chainingKey)
        chainingKey = output.copyOfRange(0, NoiseConstants.HASH_LEN)
        val cipherKey = output.copyOfRange(NoiseConstants.HASH_LEN, NoiseConstants.HASH_LEN * 2)
        cipher.initializeKey(cipherKey)
        Bytes.wipe(output, cipherKey)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipher.encryptWithAd(hash, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        // The hash must be mixed with the *ciphertext*, and only after a successful decrypt, or a
        // tampered message would still advance the transcript.
        val plaintext = cipher.decryptWithAd(hash, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    fun hasKey(): Boolean = cipher.hasKey()

    fun handshakeHash(): ByteArray = hash.copyOf()

    /** Noise `Split()`: two one-way cipher states for the transport phase. */
    fun split(): Pair<CipherState, CipherState> {
        val prk = Hkdf.extract(chainingKey, ByteArray(0))
        val output = Hkdf.expand(prk, ByteArray(0), NoiseConstants.HASH_LEN * 2)
        val k1 = output.copyOfRange(0, NoiseConstants.KEY_LEN)
        val k2 = output.copyOfRange(NoiseConstants.KEY_LEN, NoiseConstants.KEY_LEN * 2)
        val c1 = CipherState().apply { initializeKey(k1) }
        val c2 = CipherState().apply { initializeKey(k2) }
        Bytes.wipe(output, k1, k2)
        return c1 to c2
    }

    fun clear() {
        cipher.clear()
        Bytes.wipe(chainingKey, hash)
    }
}
