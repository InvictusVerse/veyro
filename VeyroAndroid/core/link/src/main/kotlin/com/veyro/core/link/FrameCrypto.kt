package com.veyro.core.link

import com.veyro.core.common.VeyroLog
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Frame cryptography for the Veyro link and connection protocol.
 *
 * ## Provenance
 *
 * This is a port of the pairing and session cryptography from the author's Invox ecosystem code
 * (`services/ecosystem/protocol/Crypto.kt`), which is a working, field-tested system on these
 * devices. Adapted rather than invented, because a scheme that demonstrably pairs two phones beats
 * a stronger one that does not yet connect.
 *
 * ## Two modes
 *
 * 1. **Pairing** - the key is derived from a 6-digit PIN by PBKDF2. Payloads travel as plaintext
 *    with an HMAC over `(fromId ‖ nonce ‖ payload)`. The window is short and the PIN is single-use,
 *    so the work factor plus the attempt limit is what makes a 6-digit secret acceptable.
 *
 * 2. **Session** - the key is a persisted 32-byte shared secret. Payloads are AES-GCM sealed, and
 *    the outer HMAC is kept as defence in depth: GCM already authenticates the ciphertext, but the
 *    outer MAC also binds the frame to the sender id on the wire, which is what stops a frame being
 *    replayed under a different claimed sender.
 *
 * ## Why the sender id is inside the MAC
 *
 * `hmac(key, fromId ‖ nonce ‖ sealed)` rather than `hmac(key, nonce ‖ sealed)`. Without the id, a
 * frame could be lifted and re-attributed to a different peer in a multi-device group - the MAC
 * would still verify because the key is shared. Binding it costs nothing and closes that.
 */
internal object FrameCrypto {

    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

    /**
     * 120k iterations. This is the only thing standing between a 6-digit PIN and an offline
     * attacker who captured the pairing exchange, so it is deliberately slow - roughly a tenth of a
     * second on a mid-range phone, which is unnoticeable once but expensive a million times over.
     */
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12

    private const val TAG = "FrameCrypto"

    private val random = SecureRandom()

    // ── Key derivation ─────────────────────────────────────────────────────────────────────

    /**
     * Derives the pairing bootstrap key from the PIN.
     *
     * The salt is canonically ordered `min(idA, idB):max(idA, idB)` (lower-cased).
     * Sorting the IDs lexicographically ensures that both sides derive the identical
     * shared secret regardless of who initiated the connection or if roles are reversed.
     */
    fun deriveBootstrapKey(pin: String, initiatorId: String, targetId: String): SecretKey {
        val a = initiatorId.lowercase()
        val b = targetId.lowercase()
        val salt = (if (a <= b) "$a:$b" else "$b:$a")
            .toByteArray(Charsets.US_ASCII)
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val raw = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    fun sessionKeyFromBytes(secret: ByteArray): SecretKey {
        require(secret.size == 32) { "session secret must be 32 bytes" }
        return SecretKeySpec(secret, "AES")
    }

    /** A fresh 32-byte session secret, minted by the device being paired *to*. */
    fun newSessionSecret(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    /** Six digits, uniform. Never logged. */
    fun newPin(): String = "%06d".format(random.nextInt(1_000_000))

    // ── Seal / open ────────────────────────────────────────────────────────────────────────

    fun seal(
        payloadJson: String,
        key: SecretKey,
        encrypt: Boolean,
        fromDeviceId: String,
    ): SealedFrame {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }

        val body = if (encrypt) {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
                doFinal(payloadJson.toByteArray(Charsets.UTF_8))
            }
        } else {
            payloadJson.toByteArray(Charsets.UTF_8)
        }

        val from = fromDeviceId.toByteArray(Charsets.UTF_8)
        return SealedFrame(
            nonce = nonce,
            body = body,
            mac = hmac(key, from + nonce + body),
            encrypted = encrypt,
            fromDeviceId = fromDeviceId,
        )
    }

    /** Returns the payload JSON, or null on any failure. Never throws on hostile input. */
    fun open(frame: SealedFrame, key: SecretKey): String? {
        val from = frame.fromDeviceId.toByteArray(Charsets.UTF_8)
        val expected = hmac(key, from + frame.nonce + frame.body)
        // Constant-time: a short-circuiting compare leaks how many leading MAC bytes an attacker
        // guessed, which is enough to forge a tag one byte at a time.
        if (!constantTimeEquals(expected, frame.mac)) return null

        return try {
            if (frame.encrypted) {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, frame.nonce))
                    String(doFinal(frame.body), Charsets.UTF_8)
                }
            } else {
                String(frame.body, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            VeyroLog.d(TAG) { "Frame failed to open: ${t.message}" }
            null
        }
    }

    fun hmac(key: SecretKey, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.encoded, "HmacSHA256"))
            doFinal(data)
        }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

/** Legacy alias for backwards compatibility. */
internal typealias LinkCrypto = FrameCrypto

/** A frame after sealing, before base64 encoding for the wire. */
internal data class SealedFrame(
    val nonce: ByteArray,
    val body: ByteArray,
    val mac: ByteArray,
    val encrypted: Boolean,
    val fromDeviceId: String,
) {
    override fun equals(other: Any?): Boolean =
        other is SealedFrame &&
            nonce.contentEquals(other.nonce) &&
            body.contentEquals(other.body) &&
            mac.contentEquals(other.mac) &&
            encrypted == other.encrypted &&
            fromDeviceId == other.fromDeviceId

    override fun hashCode(): Int {
        var r = nonce.contentHashCode()
        r = 31 * r + body.contentHashCode()
        r = 31 * r + mac.contentHashCode()
        r = 31 * r + encrypted.hashCode()
        r = 31 * r + fromDeviceId.hashCode()
        return r
    }
}

/**
 * Bounded LRU of recently-seen nonces. Rejects replays regardless of timestamp.
 *
 * 4096 entries is far beyond the expected frame rate for control traffic, and bounding it matters:
 * an unbounded set would be a memory-exhaustion target for anything that can send us frames.
 */
internal class NonceCache(private val capacity: Int = 4096) {

    private val seen = object : LinkedHashMap<String, Boolean>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > capacity
    }

    @Synchronized
    fun checkAndStore(nonce: String): Boolean {
        if (seen.containsKey(nonce)) return false
        seen[nonce] = true
        return true
    }

    @Synchronized
    fun clear(): Unit = seen.clear()
}
