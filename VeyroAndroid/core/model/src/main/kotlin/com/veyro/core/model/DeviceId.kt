package com.veyro.core.model

import com.veyro.core.model.encoding.Crockford32
import com.veyro.core.model.encoding.toFingerprintString
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * The stable identifier for one Veyro installation.
 *
 * It is a *commitment to a public key*, not a name the device picks:
 *
 * ```
 * deviceIdBytes = SHA-256("veyro-id-v1" || staticPublicKey)[0..15]
 * deviceId      = Crockford-Base32(deviceIdBytes)        // 26 characters
 * ```
 *
 * That is what makes identity spoofing pointless. An attacker can broadcast your device id in an
 * mDNS record all day; the moment a handshake starts it must prove possession of the matching
 * X25519 private key, and it cannot (ProjectInfo/05-SECURITY.md, adversary A2/A3).
 *
 * 128 bits is deliberate. Truncating SHA-256 to 16 bytes leaves a collision resistance of 2^64,
 * which is irrelevant here because a collision gains an attacker nothing: the key check still
 * fails. The shorter form just keeps the identifier readable and the mDNS TXT record small.
 */
@Serializable
@JvmInline
public value class DeviceId(public val value: String) {

    /** Short, human-comparable form. Not unique enough for equality - display only. */
    public val short: String get() = value.take(8)

    override fun toString(): String = value

    public companion object {
        public const val LENGTH: Int = 26
        public const val BYTE_LENGTH: Int = 16
        private const val DOMAIN = "veyro-id-v1"

        /** Derives the id from a 32-byte X25519 static public key. */
        public fun fromPublicKey(staticPublicKey: ByteArray): DeviceId {
            require(staticPublicKey.size == 32) {
                "X25519 public key must be 32 bytes, was ${staticPublicKey.size}"
            }
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(DOMAIN.toByteArray(Charsets.US_ASCII))
                update(staticPublicKey)
            }.digest()
            return DeviceId(Crockford32.encode(digest.copyOf(BYTE_LENGTH)))
        }

        /**
         * Parses an id received over the network or scanned from a QR code.
         * Returns `null` rather than throwing, because every caller is handling untrusted input.
         */
        public fun parseOrNull(raw: String?): DeviceId? {
            if (raw == null || raw.length != LENGTH) return null
            val bytes = Crockford32.decodeOrNull(raw) ?: return null
            if (bytes.size != BYTE_LENGTH) return null
            // Re-encode so the stored form is always canonical upper case.
            return DeviceId(Crockford32.encode(bytes))
        }

        /** Verifies that [id] really is the commitment to [staticPublicKey]. */
        public fun matches(id: DeviceId, staticPublicKey: ByteArray): Boolean =
            runCatching { fromPublicKey(staticPublicKey) == id }.getOrDefault(false)
    }
}

/**
 * The 8-byte fingerprint a user compares between two screens, e.g. `A7 4C 91 2E 55 0B 83 1D`.
 *
 * Derived from the id, so it needs no extra state, and grouped in pairs because ungrouped hex is
 * demonstrably harder to compare correctly (REQ-I05).
 */
public fun DeviceId.fingerprint(): String {
    val bytes = Crockford32.decodeOrNull(value) ?: return value
    return bytes.copyOf(8).toFingerprintString()
}
