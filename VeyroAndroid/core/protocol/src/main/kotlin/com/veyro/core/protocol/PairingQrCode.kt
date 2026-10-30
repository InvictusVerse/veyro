package com.veyro.core.protocol

import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The pairing QR code a PC shows on its pairing screen (04-PROTOCOL.md section 4.2, ADR-030).
 *
 * ```
 * veyro:pair?v=2&id=<DeviceId>&n=<name>&a=<ip:port>[,<ip:port>...]&s=<secret>&e=<unix seconds>
 * ```
 *
 * Out of band, not on the wire. Format 2 (ADR-058): `s` is a 128-bit secret, 22 characters of
 * unpadded base64url. The phone proves it holds it (`qrProof` in `pairHello`) and mixes it into
 * the pairing keys, so a photo of the code plus a recording of the exchange opens nothing.
 * Everything here is untrusted until the PC's answering key hashes to [deviceId]. [name] is
 * display-only; the stored name comes from `pairOk`.
 *
 * [toString] redacts the secret, so an accidental log line cannot leak it (AGENTS.md).
 */
public class PairingQrCode(
    public val deviceId: DeviceId,
    /** Decoded display name, or null when the PC sent an empty one. Never trusted. */
    public val name: String?,
    /** 1..4 private or link-local IPv4 endpoints, in the order the PC listed them. */
    public val endpoints: List<Endpoint>,
    /** The 16-byte one-time QR secret. Never log it; [wipe] it once pairing returns. */
    public val secret: ByteArray,
    public val expiresAtEpochSeconds: Long,
) {
    override fun toString(): String =
        "PairingQrCode(deviceId=$deviceId, endpoints=${endpoints.size}, secret=<redacted>)"

    /** Zeroes [secret] (ADR-058 wipe rule). */
    public fun wipe() {
        secret.fill(0)
    }

    /** Why a scanned string was rejected. Specific so the UI can say something useful. */
    public enum class Error {
        /** Not a `veyro:pair?` string at all, or structurally broken. */
        NOT_VEYRO,
        /** `v` is not `2`. An older (`1`) or newer PC app. */
        UNSUPPORTED_VERSION,
        /** A required key is absent or repeated. */
        MALFORMED,
        BAD_DEVICE_ID,
        BAD_NAME,
        BAD_ENDPOINTS,
        /** `s` is not 22 canonical base64url characters of a 16-byte secret. */
        BAD_SECRET,
        BAD_EXPIRY,
        /** `now > e + 600`. The user should refresh the code on the PC. */
        EXPIRED,
    }

    public sealed interface Result {
        public class Valid(public val qr: PairingQrCode) : Result {
            override fun toString(): String = "Valid($qr)"
        }

        public data class Invalid(public val error: Error) : Result
    }

    public companion object {
        public const val PREFIX: String = "veyro:pair?"
        public val VERSION: String = ProtocolConstants.QR_FORMAT_VERSION.toString()
        public const val SECRET_SIZE: Int = 16
        public const val SECRET_LENGTH: Int = 22
        public const val MAX_NAME_BYTES: Int = 64
        public const val MAX_ENDPOINTS: Int = 4

        /** Clock-skew allowance after `e` (section 4.2). */
        public const val EXPIRY_GRACE_SECONDS: Long = 600

        /** Far above any legal code; bounds the work done on hostile input. */
        private const val MAX_RAW_LENGTH = 1024

        private val REQUIRED_KEYS = listOf("v", "id", "n", "a", "s", "e")

        /**
         * Parses and validates [raw] against section 4.2.
         *
         * Total: never throws, whatever the input. Unknown keys are ignored so a later format can
         * add fields; known keys appearing twice are rejected, because "which one wins" is an
         * ambiguity an attacker could exploit.
         */
        public fun parse(raw: String?, nowEpochSeconds: Long): Result {
            if (raw == null || raw.length > MAX_RAW_LENGTH) return invalid(Error.NOT_VEYRO)
            val text = raw.trim()
            if (!text.startsWith(PREFIX, ignoreCase = true)) return invalid(Error.NOT_VEYRO)
            val query = text.substring(PREFIX.length)
            if (query.isEmpty()) return invalid(Error.NOT_VEYRO)

            val fields = HashMap<String, String>()
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) return invalid(Error.NOT_VEYRO)
                val key = pair.substring(0, eq)
                val value = pair.substring(eq + 1)
                if (key in REQUIRED_KEYS && fields.put(key, value) != null) {
                    return invalid(Error.MALFORMED)
                }
            }

            // Version first: a future format may change every other field.
            val version = fields["v"] ?: return invalid(Error.MALFORMED)
            if (version != VERSION) return invalid(Error.UNSUPPORTED_VERSION)
            if (REQUIRED_KEYS.any { it !in fields }) return invalid(Error.MALFORMED)

            val deviceId = DeviceId.parseOrNull(fields.getValue("id")) ?: return invalid(Error.BAD_DEVICE_ID)

            val name = decodeName(fields.getValue("n")) ?: return invalid(Error.BAD_NAME)

            val endpoints = parseEndpoints(fields.getValue("a")) ?: return invalid(Error.BAD_ENDPOINTS)

            val secret = decodeSecret(fields.getValue("s")) ?: return invalid(Error.BAD_SECRET)

            val expiry = parseUnsignedLong(fields.getValue("e")) ?: return invalid(Error.BAD_EXPIRY)
            if (nowEpochSeconds > expiry + EXPIRY_GRACE_SECONDS) {
                secret.fill(0)
                return invalid(Error.EXPIRED)
            }

            return Result.Valid(
                PairingQrCode(
                    deviceId = deviceId,
                    name = name.ifEmpty { null },
                    endpoints = endpoints,
                    secret = secret,
                    expiresAtEpochSeconds = expiry,
                ),
            )
        }

        private fun invalid(error: Error): Result = Result.Invalid(error)

        /**
         * Strict unpadded base64url of exactly 16 bytes: 22 characters from `[A-Za-z0-9_-]`, and
         * the last character's low 4 bits zero (canonical). Not `Base64Url.decodeOrNull`, which
         * also accepts `+`, `/` and `=`.
         */
        private fun decodeSecret(text: String): ByteArray? {
            if (text.length != SECRET_LENGTH) return null
            val values = IntArray(SECRET_LENGTH)
            for (i in text.indices) {
                values[i] = base64UrlValue(text[i])
                if (values[i] < 0) return null
            }
            if (values[SECRET_LENGTH - 1] and 0x0F != 0) return null
            val out = ByteArray(SECRET_SIZE)
            var bits = 0
            var acc = 0
            var n = 0
            for (v in values) {
                acc = (acc shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    if (n < SECRET_SIZE) out[n++] = ((acc shr bits) and 0xFF).toByte()
                }
            }
            return out
        }

        private fun base64UrlValue(ch: Char): Int = when (ch) {
            in 'A'..'Z' -> ch - 'A'
            in 'a'..'z' -> ch - 'a' + 26
            in '0'..'9' -> ch - '0' + 52
            '-' -> 62
            '_' -> 63
            else -> -1
        }

        /**
         * Strict percent-decoding to UTF-8. `+` is a space (form encoding); a literal plus is
         * `%2B`. Malformed escapes, invalid UTF-8, control characters or more than
         * [MAX_NAME_BYTES] decoded bytes all reject. Returns "" for an empty name.
         */
        private fun decodeName(encoded: String): String? {
            val bytes = ByteArray(encoded.length)
            var count = 0
            var i = 0
            while (i < encoded.length) {
                val ch = encoded[i]
                when {
                    ch == '%' -> {
                        if (i + 2 >= encoded.length) return null
                        val hi = hexValue(encoded[i + 1])
                        val lo = hexValue(encoded[i + 2])
                        if (hi < 0 || lo < 0) return null
                        bytes[count++] = ((hi shl 4) or lo).toByte()
                        i += 3
                    }
                    ch == '+' -> {
                        bytes[count++] = ' '.code.toByte()
                        i++
                    }
                    ch.code in 0x21..0x7E -> {
                        bytes[count++] = ch.code.toByte()
                        i++
                    }
                    else -> return null
                }
                if (count > MAX_NAME_BYTES) return null
            }
            val decoded = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, count))
                    .toString()
            } catch (_: CharacterCodingException) {
                return null
            }
            if (decoded.any { Character.isISOControl(it) }) return null
            return decoded
        }

        /** ASCII hex only; `Character.digit` would also accept non-ASCII digits. */
        private fun hexValue(ch: Char): Int = when (ch) {
            in '0'..'9' -> ch - '0'
            in 'a'..'f' -> ch - 'a' + 10
            in 'A'..'F' -> ch - 'A' + 10
            else -> -1
        }

        private fun parseEndpoints(value: String): List<Endpoint>? {
            if (value.isEmpty()) return null
            val parts = value.split(',')
            if (parts.size > MAX_ENDPOINTS) return null
            val result = ArrayList<Endpoint>(parts.size)
            for (part in parts) {
                val colon = part.lastIndexOf(':')
                if (colon <= 0) return null
                val host = part.substring(0, colon)
                val octets = parseIpv4(host) ?: return null
                if (!isPrivateOrLinkLocal(octets)) return null
                val port = parseUnsignedLong(part.substring(colon + 1))?.takeIf { it in 1..65535 } ?: return null
                val endpoint = Endpoint(host, port.toInt())
                if (endpoint !in result) result += endpoint
            }
            return result
        }

        /** Dotted-quad only: four decimal octets 0..255, no leading zeros, no hostnames. */
        private fun parseIpv4(host: String): IntArray? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val octets = IntArray(4)
            for ((index, part) in parts.withIndex()) {
                if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
                if (part.length > 1 && part[0] == '0') return null
                val octet = part.toInt()
                if (octet > 255) return null
                octets[index] = octet
            }
            return octets
        }

        /** 10/8, 172.16/12, 192.168/16, 100.64/10 (CGNAT, e.g. some hotspots), 169.254/16. */
        private fun isPrivateOrLinkLocal(o: IntArray): Boolean = when {
            o[0] == 10 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 100 && o[1] in 64..127 -> true
            o[0] == 169 && o[1] == 254 -> true
            else -> false
        }

        /** Plain decimal digits only (no sign, no spaces), at most 18 of them. */
        private fun parseUnsignedLong(text: String): Long? {
            if (text.isEmpty() || text.length > 18 || !text.all { it in '0'..'9' }) return null
            return text.toLong()
        }
    }
}
