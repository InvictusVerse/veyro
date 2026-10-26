package com.veyro.core.model.encoding

/**
 * Crockford Base32 - the encoding used for [com.veyro.core.model.DeviceId].
 *
 * Chosen over RFC 4648 Base32 because it excludes the characters that people confuse when
 * reading an identifier aloud or typing it from a screen: `I`, `L`, `O` and `U` are absent, and
 * decoding maps `I`/`L` to `1` and `O` to `0`. There is no padding, so the output length is a
 * pure function of the input length.
 *
 * Pure Kotlin on purpose: this is in `:core:model`, which the Windows client mirrors (ADR-009).
 */
public object Crockford32 {

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Reverse table for the 128 ASCII code points; -1 means "not a symbol". */
    private val DECODE: IntArray = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
        // Lower case is accepted.
        ALPHABET.forEachIndexed { index, c -> table[c.lowercaseChar().code] = index }
        // Visually ambiguous characters, per the Crockford specification.
        for (c in charArrayOf('O', 'o')) table[c.code] = 0
        for (c in charArrayOf('I', 'i', 'L', 'l')) table[c.code] = 1
    }

    /** Encodes [bytes] most-significant-bit first. Output length is `ceil(bytes.size * 8 / 5)`. */
    public fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                out.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            out.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return out.toString()
    }

    /**
     * Decodes [text], ignoring `-` separators.
     *
     * @throws IllegalArgumentException if [text] contains a character that is not a Crockford
     * symbol. Callers that handle untrusted input should use [decodeOrNull].
     */
    public fun decode(text: String): ByteArray =
        decodeOrNull(text) ?: throw IllegalArgumentException("not valid Crockford Base32")

    /** Null-returning counterpart of [decode], for untrusted input. */
    public fun decodeOrNull(text: String): ByteArray? {
        val symbols = text.filter { it != '-' }
        if (symbols.isEmpty()) return ByteArray(0)
        val out = ArrayList<Byte>(symbols.length * 5 / 8 + 1)
        var buffer = 0
        var bitsLeft = 0
        for (c in symbols) {
            val v = if (c.code < 128) DECODE[c.code] else -1
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        // Trailing bits below a full byte are padding and must be zero for a canonical encoding.
        if (bitsLeft > 0 && (buffer and ((1 shl bitsLeft) - 1)) != 0) return null
        return out.toByteArray()
    }
}

/** Lower-case hex, no separators. */
public fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = digits[v ushr 4]
        out[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(out)
}

/**
 * Upper-case hex grouped in pairs, e.g. `A7 4C 91 2E`.
 *
 * This is the only form a fingerprint should ever be shown in: grouping is what makes a human
 * comparison on two screens actually reliable (REQ-I05, REQ-U04).
 */
public fun ByteArray.toFingerprintString(): String =
    joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }

/** Parses lower- or upper-case hex. Returns `null` on any malformed input. */
public fun String.hexToBytesOrNull(): ByteArray? {
    val clean = filter { it != ' ' && it != ':' && it != '-' }
    if (clean.length % 2 != 0) return null
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(clean[i * 2], 16)
        val lo = Character.digit(clean[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
