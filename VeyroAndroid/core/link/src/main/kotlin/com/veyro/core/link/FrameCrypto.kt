package com.veyro.core.link

import com.veyro.core.protocol.AeadStreamException
import com.veyro.core.protocol.AeadStreamFramer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Session frame cryptography for link protocol v3 (04-PROTOCOL.md 5, ADR-058).
 *
 * Every frame after the ack is `{"f": fromDeviceId, "c": base64(ciphertext || tag)}`, sealed with
 * ChaCha20-Poly1305 under the sender's direction key of this connection. The nonce is
 * `u32be(0) || u64be(counter)` and the AAD `utf8(from) || 0x00 || dirByte || u64be(counter)`; the
 * receiver builds both from its own counter and the expected peer id, so a replayed, reordered,
 * reflected or re-attributed frame fails its tag. One failure closes the connection.
 *
 * The keys come from `ConnectionKeySchedule`; the stored pairing secret never seals anything.
 */
internal object FrameCrypto {

    const val DIR_I2R: Byte = 0x01
    const val DIR_R2I: Byte = 0x02

    /** Session frames use stream id 0 of the AEAD framer. */
    const val SESSION_STREAM_ID: Int = 0

    private val random = SecureRandom()

    /** A fresh 32-byte pairing secret, minted by the device being paired *to*. */
    fun newSessionSecret(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** `utf8(from) || 0x00 || dirByte || u64be(counter)`. */
    fun aad(fromDeviceId: String, dir: Byte, counter: Long): ByteArray {
        val from = fromDeviceId.toByteArray(Charsets.UTF_8)
        val out = ByteArray(from.size + 2 + 8)
        System.arraycopy(from, 0, out, 0, from.size)
        out[from.size + 1] = dir
        for (i in 0 until 8) out[from.size + 2 + i] = (counter ushr (56 - 8 * i)).toByte()
        return out
    }

    /**
     * Strict standard padded base64: length a multiple of 4, alphabet `[A-Za-z0-9+/]`, at most two
     * trailing `=`, no whitespace, and canonical (it re-encodes to the same text). Null otherwise.
     */
    fun decodeStrictBase64(value: String?): ByteArray? {
        if (value == null || value.isEmpty() || value.length % 4 != 0) return null
        var padding = 0
        for ((i, ch) in value.withIndex()) {
            val ok = when (ch) {
                in 'A'..'Z', in 'a'..'z', in '0'..'9', '+', '/' -> padding == 0
                '=' -> {
                    padding++
                    i >= value.length - 2
                }
                else -> false
            }
            if (!ok) return null
        }
        val bytes = try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (Base64.getEncoder().encodeToString(bytes) != value) return null
        return bytes
    }

    /** A 32-byte key or nonce field: exactly 44 characters of canonical base64 (04-PROTOCOL.md 4.1). */
    fun decodeKey32(value: String?): ByteArray? {
        if (value == null || value.length != 44) return null
        return decodeStrictBase64(value)?.takeIf { it.size == 32 }
    }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

/** A session frame was rejected. [authFailed] is true for a tag failure, false for a malformed frame. */
internal class SessionFrameException(message: String, val authFailed: Boolean, cause: Throwable? = null) :
    Exception(message, cause)

/** Seals one direction's envelopes. Not thread-safe; the owner holds its send lock. */
internal class SessionSealer(key: ByteArray, private val localDeviceId: String, private val dir: Byte) {
    private val framer = AeadStreamFramer(key, FrameCrypto.SESSION_STREAM_ID, AeadStreamFramer.Role.SEAL)

    val nextCounter: Long get() = framer.nextCounter

    private val idBytes = localDeviceId.toByteArray(Charsets.UTF_8).size

    /** The UTF-8 length of the wire text [seal] returns for a plaintext of [plainBytes] bytes. */
    fun wireLength(plainBytes: Int): Long =
        "{\"f\":\"\",\"c\":\"\"}".length + idBytes + 4L * ((plainBytes.toLong() + 16 + 2) / 3)

    /**
     * Returns the wire text. Throws [AeadStreamException] when the counter is exhausted, and
     * [IllegalArgumentException] when the frame would be longer than [maxWireBytes]; that check
     * runs before sealing, so no counter is spent (review issue 14, ADR-058).
     */
    fun seal(envelopeJson: String, maxWireBytes: Long = Long.MAX_VALUE): String {
        val plain = envelopeJson.toByteArray(Charsets.UTF_8)
        if (wireLength(plain.size) > maxWireBytes) {
            plain.fill(0)
            throw IllegalArgumentException("control frame exceeds the protocol limit")
        }
        val aad = FrameCrypto.aad(localDeviceId, dir, framer.nextCounter)
        val sealed = ByteArray(plain.size + 16)
        framer.seal(aad, aad.size, plain, 0, plain.size, sealed, 0)
        plain.fill(0)
        return PeerMessageJson.codec.encodeToString(
            LinkWireFrame.serializer(),
            LinkWireFrame(from = localDeviceId, ciphertext = FrameCrypto.b64(sealed)),
        )
    }

    fun wipe() = framer.wipe()
}

/** Opens one direction's frames from [peerDeviceId]. Not thread-safe; the read loop owns it. */
internal class SessionOpener(key: ByteArray, private val peerDeviceId: String, private val dir: Byte) {
    private val framer = AeadStreamFramer(key, FrameCrypto.SESSION_STREAM_ID, AeadStreamFramer.Role.OPEN)

    /** The counter of the next frame; the frame just opened had `nextCounter - 1`. */
    val nextCounter: Long get() = framer.nextCounter

    /** Returns the envelope JSON. Any failure throws [SessionFrameException]. */
    fun open(wireText: String): String {
        val wire = try {
            PeerMessageJson.codec.decodeFromString(LinkWireFrame.serializer(), wireText)
        } catch (t: Throwable) {
            throw SessionFrameException("malformed frame", authFailed = false, cause = t)
        }
        // Byte-exact, before any crypto: a frame naming another sender is not ours to open.
        if (wire.from != peerDeviceId) throw SessionFrameException("frame from an unexpected sender", authFailed = false)
        val sealed = FrameCrypto.decodeStrictBase64(wire.ciphertext)
            ?: throw SessionFrameException("malformed frame", authFailed = false)
        if (sealed.size < 16) throw SessionFrameException("malformed frame", authFailed = false)
        val aad = FrameCrypto.aad(peerDeviceId, dir, framer.nextCounter)
        val plain = ByteArray(sealed.size)
        val len = try {
            framer.open(aad, aad.size, sealed, 0, sealed.size, plain, 0)
        } catch (e: AeadStreamException) {
            throw SessionFrameException("frame failed authentication", authFailed = true, cause = e)
        }
        return String(plain, 0, len, Charsets.UTF_8).also { plain.fill(0) }
    }

    fun wipe() = framer.wipe()
}
