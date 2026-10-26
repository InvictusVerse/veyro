package com.veyro.core.data

import com.veyro.core.model.DeviceId
import com.veyro.core.model.encoding.hexToBytesOrNull
import com.veyro.core.model.encoding.toHex
import com.veyro.core.protocol.Base64Url
import com.veyro.core.protocol.Vlp

/**
 * The pairing payload, as specified in `04-PROTOCOL.md` section 8.
 *
 * ```
 * veyro://pair?v=1&id=<deviceId>&pk=<base64url public key>&n=<name>&a=<ip:port>
 * ```
 *
 * This is the out-of-band channel. Its entire purpose is that the key travels by a route an attacker
 * on the network does not control - a QR code the user points a camera at, or a link they share.
 * That is what `Noise_XK` needs and what makes an active MITM on the LAN impossible during pairing.
 *
 * The same information now also travels in the presence record's `pk` attribute, which removes the
 * camera requirement but is **not** authenticated. Both paths work; neither is a substitute for the
 * six-digit comparison, which is what actually confirms the key.
 */
public object PairingCodec {

    private const val SCHEME = "veyro://pair"

    public data class Payload(
        val version: Int,
        val deviceId: DeviceId,
        val staticPublicKey: ByteArray,
        val name: String?,
        val endpoint: String?,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Payload &&
                version == other.version &&
                deviceId == other.deviceId &&
                staticPublicKey.contentEquals(other.staticPublicKey) &&
                name == other.name &&
                endpoint == other.endpoint

        override fun hashCode(): Int =
            31 * (31 * (31 * version + deviceId.hashCode()) + staticPublicKey.contentHashCode()) +
                (name?.hashCode() ?: 0) + (endpoint?.hashCode() ?: 0)
    }

    public fun encode(
        deviceId: DeviceId,
        staticPublicKey: ByteArray,
        port: Int?,
        name: String?,
        host: String? = null,
    ): String = buildString {
        append(SCHEME)
        append("?v=").append(Vlp.VERSION)
        append("&id=").append(deviceId.value)
        append("&pk=").append(Base64Url.encode(staticPublicKey))
        name?.takeIf { it.isNotBlank() }?.let { append("&n=").append(percentEncode(it)) }
        if (host != null && port != null) append("&a=").append(percentEncode("$host:$port"))
    }

    /**
     * Parses a payload from a scanned code or a pasted link.
     *
     * Returns null for anything malformed. Every field here arrives from outside the app, so the
     * parser is total: no exceptions, no partial results, and it refuses a payload whose key is not
     * the length X25519 requires.
     */
    public fun decode(uri: String?): Payload? {
        if (uri == null || !uri.startsWith(SCHEME)) return null
        val query = uri.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null

        val params = HashMap<String, String>()
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            params[pair.substring(0, eq)] = percentDecode(pair.substring(eq + 1))
        }

        val version = params["v"]?.toIntOrNull() ?: return null
        if (version != Vlp.VERSION) return null

        val deviceId = DeviceId.parseOrNull(params["id"]) ?: return null
        val key = Base64Url.decodeOrNull(params["pk"])?.takeIf { it.size == 32 } ?: return null

        // The id must be the commitment to the key. Without this, a crafted payload could pair us
        // with a key that does not match the id we would then display, so the fingerprint the user
        // compares would not describe the device we actually trust.
        if (!DeviceId.matches(deviceId, key)) return null

        return Payload(
            version = version,
            deviceId = deviceId,
            staticPublicKey = key,
            name = params["n"]?.takeIf { it.isNotBlank() },
            endpoint = params["a"]?.takeIf { it.isNotBlank() },
        )
    }

    /** Minimal percent-encoding for the few characters that would break the query form. */
    private fun percentEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' || c == ':') {
                append(c)
            } else {
                append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }

    private fun percentDecode(value: String): String {
        val out = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                val byte = hex.hexToBytesOrNull()?.firstOrNull()
                if (byte != null) {
                    out.write(byte.toInt())
                    i += 3
                    continue
                }
            }
            out.write(c.code)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Convenience for diagnostics: the fingerprint a user would compare. */
    public fun fingerprintOf(staticPublicKey: ByteArray): String =
        staticPublicKey.copyOf(8).toHex()
}
