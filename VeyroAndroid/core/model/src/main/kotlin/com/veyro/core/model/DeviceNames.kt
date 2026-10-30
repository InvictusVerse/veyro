package com.veyro.core.model

/**
 * The one rule for a device name that came from somewhere else (ADR-059): a peer's `hello`,
 * `helloAck` or `deviceInfo`, or a nickname typed for a peer. Mirrored by Windows `DeviceNames`.
 *
 * Names are user data: never log them.
 */
public object DeviceNames {

    /** Longest accepted name, in UTF-16 code units (Kotlin and C# `length` agree on this). */
    public const val MAX_CHARS: Int = 64

    /**
     * [raw] trimmed, or null when it is empty, longer than [MAX_CHARS], or holds a control
     * character: C0/C1 controls, line and paragraph separators, and the bidirectional embedding,
     * override and isolate marks that can make one name read like another. A rejected name is
     * dropped, never repaired, so what a peer sees is exactly what its owner typed.
     */
    public fun clean(raw: String?): String? {
        val name = raw?.trim() ?: return null
        if (name.isEmpty() || name.length > MAX_CHARS) return null
        if (name.any(::isForbidden)) return null
        return name
    }

    private fun isForbidden(c: Char): Boolean =
        Character.isISOControl(c) ||
            c == '\u2028' || c == '\u2029' ||
            c in '\u202A'..'\u202E' ||
            c in '\u2066'..'\u2069'
}
