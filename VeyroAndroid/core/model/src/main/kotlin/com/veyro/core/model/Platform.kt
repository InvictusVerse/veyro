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
            Capability.CLIPBOARD,
            Capability.RESUME,
            Capability.NOTIFICATION_MIRROR,
            Capability.MEDIA_CONTROL,
            Capability.DEVICE_STATUS,
            Capability.MESSAGING,
            Capability.CALLS,
            Capability.PHOTOS,
            Capability.WALLPAPER,
            Capability.PHONE_CONTROLS,
            Capability.DEVICE_INFO,
            // ADR-066: the screen goes to a PC only after the user's switch and Android's consent.
            Capability.SCREEN_SOURCE,
            // ADR-067: the camera likewise, after the switch and a tap on the phone each time.
            Capability.CAMERA_SOURCE,
            // ADR-073: the PC's sound on this phone, and photos or scans taken for the PC.
            Capability.PC_AUDIO,
            Capability.PHOTO_CAPTURE,
        )

        /**
         * What one phone uses with another (ADR-052): files, media control in both directions,
         * battery status, and text and links shared with "Send with Veyro", which arrive as a
         * clipboard notification (ADR-054), and each phone's own name (ADR-059). Everything else
         * Android advertises exists to serve a PC (notifications, messages, calls, photos,
         * wallpaper, flashlight / volume / brightness control) and must not flow from phone to
         * phone.
         */
        public val PHONE_TO_PHONE: Capabilities = of(
            Capability.FILE_TRANSFER,
            Capability.FOLDER_TRANSFER,
            Capability.RESUME,
            Capability.MEDIA_CONTROL,
            Capability.DEVICE_STATUS,
            Capability.CLIPBOARD,
            Capability.DEVICE_INFO,
        )

        /** The part of [advertised] this phone uses with a peer running on [platform]. */
        public fun usedWith(platform: Platform, advertised: Capabilities): Capabilities =
            if (platform == Platform.ANDROID) advertised intersect PHONE_TO_PHONE else advertised
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

    /** Media sessions shared and remote-controlled, in both directions (ADR-026). */
    MEDIA_CONTROL(0x0200),

    /** Battery / ringer / DND status, plus ring-my-phone and ringer/DND control (ADR-026). */
    DEVICE_STATUS(0x0400),

    /** SMS threads, history and sending from the PC (ADR-027). */
    MESSAGING(0x0800),

    /** Call state, call log and call actions from the PC (ADR-027). */
    CALLS(0x1000),

    /** Recent photos browsed on the PC, originals fetched on demand (ADR-034). */
    PHOTOS(0x2000),

    /** Wallpaper exchanged so each side can draw the other device (ADR-038). */
    WALLPAPER(0x4000),

    /**
     * This phone can share its screen: `streamCaps`, `streamStart` and the VMS1 socket (ADR-066).
     * PC only, never phone to phone.
     */
    SCREEN_SOURCE(0x8000),

    /** This PC can show a phone's screen (ADR-066). PC only. */
    SCREEN_VIEW(0x10000),

    /** Touch and keys from the PC to the phone while mirroring (ADR-066, reserved). */
    REMOTE_INPUT(0x20000),

    /** The PC's sound played on the phone (ADR-073): `speakerStart` and a VMS1 socket with source 3. PCs only. */
    PC_AUDIO(0x1000000),

    /** Take a photo or scan a document on the phone for the PC (ADR-073): `captureReq`. PCs only. */
    PHOTO_CAPTURE(0x2000000),

    /**
     * This phone streams a camera to a PC (ADR-067): `streamStart{source: "camera"}` over the
     * same VMS1 socket as the screen. Phone only, PCs only.
     */
    CAMERA_SOURCE(0x80000),

    /**
     * Flashlight, media volume and screen brightness in `devStatus` / `devControl` (ADR-060).
     * Only meaningful together with [DEVICE_STATUS]. PC to phone only, never phone to phone.
     */
    PHONE_CONTROLS(0x40000),

    /** `deviceInfo`: the sender's own name, sent on connect and when the user changes it (ADR-059). */
    DEVICE_INFO(0x200000),
}
