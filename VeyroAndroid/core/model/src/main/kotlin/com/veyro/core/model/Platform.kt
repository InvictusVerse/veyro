package com.veyro.core.model

import kotlinx.serialization.Serializable

/** Which implementation of Veyro a peer is running. Display and capability hints only. */
@Serializable
public enum class Platform {
    ANDROID,
    WINDOWS,
    UNKNOWN,
    ;

    public companion object {
        public fun fromWire(value: String?): Platform = when (value?.lowercase()) {
            "android" -> ANDROID
            "windows" -> WINDOWS
            else -> UNKNOWN
        }
    }

    public fun toWire(): String = when (this) {
        ANDROID -> "android"
        WINDOWS -> "windows"
        UNKNOWN -> "unknown"
    }
}

/**
 * Capability bits advertised in discovery and confirmed in `Hello` (04-PROTOCOL.md section 3.3).
 *
 * A capability absent from either side must not be used. Bits are append-only: never reuse or
 * renumber one, because an old peer will interpret it with the old meaning.
 */
@JvmInline
public value class Capabilities(public val bits: Int) {

    public operator fun contains(capability: Capability): Boolean = (bits and capability.bit) != 0

    /** The set both peers support. */
    public infix fun intersect(other: Capabilities): Capabilities = Capabilities(bits and other.bits)

    public fun toList(): List<Capability> = Capability.entries.filter { it in this }

    override fun toString(): String = "0x%04X".format(bits)

    public companion object {
        public val NONE: Capabilities = Capabilities(0)

        public fun of(vararg caps: Capability): Capabilities =
            Capabilities(caps.fold(0) { acc, c -> acc or c.bit })

        /** What this build of the Android client actually supports today. */
        public val ANDROID_CURRENT: Capabilities = of(
            Capability.FILE_TRANSFER,
            Capability.FOLDER_TRANSFER,
            Capability.RESUME,
            Capability.NOTIFICATION_MIRROR,
        )
    }
}

public enum class Capability(public val bit: Int) {
    FILE_TRANSFER(0x0001),
    FOLDER_TRANSFER(0x0002),
    RESUME(0x0004),
    CLIPBOARD(0x0008),
    SYNC(0x0010),
    HANDOFF(0x0020),
    MULTI_STREAM(0x0040),
    COMPRESSION(0x0080),

    /** Phone notifications mirrored to the peer, with actions and inline reply (ADR-025). */
    NOTIFICATION_MIRROR(0x0100),
}
