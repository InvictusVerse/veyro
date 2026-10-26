package com.veyro.core.common

import java.security.MessageDigest

/**
 * Byte helpers used across the protocol and crypto layers.
 *
 * Pure Kotlin, no Android APIs, so this is usable from `:core:model` and `:core:protocol` and
 * mirrorable on the Windows side.
 */
public object Bytes {

    /** Concatenates without intermediate lists, which matters in per-chunk hot paths. */
    public fun concat(vararg parts: ByteArray): ByteArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var offset = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, offset, p.size)
            offset += p.size
        }
        return out
    }

    /** Big-endian u64. The VLP wire format is big-endian everywhere. */
    public fun u64be(value: Long): ByteArray = ByteArray(8) { i ->
        (value ushr (56 - i * 8)).toByte()
    }

    public fun u32be(value: Int): ByteArray = ByteArray(4) { i ->
        (value ushr (24 - i * 8)).toByte()
    }

    public fun readU32be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /**
     * Constant-time equality.
     *
     * `ByteArray.contentEquals` short-circuits on the first differing byte, which leaks how many
     * leading bytes of a MAC an attacker guessed correctly. That is enough to forge a tag one byte
     * at a time given enough attempts. Every comparison of a MAC, tag or secret must use this.
     *
     * The length check is not timing-sensitive: lengths here are fixed by the protocol, so the
     * length itself is public.
     */
    public fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    /** Wipes a buffer. Best-effort only: the JVM may already have copied it during a GC move. */
    public fun wipe(vararg buffers: ByteArray?) {
        for (b in buffers) b?.fill(0)
    }

    public fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (p in parts) digest.update(p)
        return digest.digest()
    }
}

/** Formats bytes as lower-case hex. Defined here so `:core:common` needs no dependency on `:core:model`. */
public fun ByteArray.hex(): String {
    val digits = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = digits[v ushr 4]
        out[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(out)
}
