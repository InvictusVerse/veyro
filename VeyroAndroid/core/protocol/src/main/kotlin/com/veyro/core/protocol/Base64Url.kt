package com.veyro.core.protocol

/**
 * Base64url, unpadded (RFC 4648 section 5).
 *
 * Hand-written because `:core:protocol` has **zero `android.*` imports** (ADR-009): it is mirrored
 * by the C# client and unit-tested without a device, so `android.util.Base64` and
 * `java.util.Base64` are both unavailable to it - the former is Android-only, the latter needs
 * API 26 for the URL-safe variant and is a JDK detail the C# side does not share.
 *
 * Unpadded on the wire. Padding carries no information and every byte on an mDNS TXT record costs
 * space; the decoder accepts padding anyway so a lenient peer is not a failure.
 */
public object Base64Url {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val DECODE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
        // Accept the standard alphabet too, so a peer that padded with '+' and '/' still works.
        table['+'.code] = 62
        table['/'.code] = 63
    }

    public fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0

            out.append(ALPHABET[b0 ushr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (i + 1 < bytes.size) out.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            if (i + 2 < bytes.size) out.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        return out.toString()
    }

    /** Returns null for anything malformed. Callers handle untrusted input. */
    public fun decodeOrNull(text: String?): ByteArray? {
        if (text == null) return null
        val clean = text.trimEnd('=')
        if (clean.isEmpty()) return ByteArray(0)

        val out = ArrayList<Byte>(clean.length * 3 / 4 + 1)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = if (c.code < 128) DECODE[c.code] else -1
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                out.add(((buffer shr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        // Leftover bits below a byte are padding and must be zero for a canonical encoding; a
        // non-zero remainder means the input was truncated mid-character.
        if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) return null
        return out.toByteArray()
    }
}