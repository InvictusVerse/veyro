package com.veyro.core.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phone as speaker and photo capture for the PC (ADR-073, 04-PROTOCOL.md 6.10). Gated by
// PC_AUDIO (0x1000000) and PHOTO_CAPTURE (0x2000000), advertised by both sides, PCs only.
// The PC's sound travels on a VMS1 socket with source 3 (speaker), the photo as a normal
// transfer whose offer carries the request's id.

/**
 * PC to phone: play this PC's sound. The phone dials the PC's VMS1 listener at [host]:[port]
 * (the same dial-address rule as `streamStart`) and plays what arrives.
 */
@Serializable
@SerialName("speakerStart")
public data class SpeakerStart(val sid: String, val host: String, val port: Int) : PeerMessage

/** Phone to PC: `playing` once sound flows, `stopped` (final, with [reason]). */
@Serializable
@SerialName("speakerState")
public data class SpeakerState(val sid: String, val state: String, val reason: String? = null) : PeerMessage

/** Either direction: the session is over. */
@Serializable
@SerialName("speakerStop")
public data class SpeakerStop(val sid: String, val reason: String) : PeerMessage

/**
 * PC to phone: take a photo ([mode] `photo`) or scan a document (`scan`) and send it. The phone
 * asks its user with a notification; the image arrives as a transfer whose `offer.requestId` is
 * [rid]. The phone forgets the request after 2 minutes.
 */
@Serializable
@SerialName("captureReq")
public data class CaptureRequest(val rid: String, val mode: String) : PeerMessage

/** Phone to PC: the request ended without an image ([reason]: declined, cancelled, expired, permission, busy, error). */
@Serializable
@SerialName("captureResult")
public data class CaptureResult(val rid: String, val ok: Boolean, val reason: String? = null) : PeerMessage

/** Field rules of 04-PROTOCOL.md 6.10. */
public object SpeakerCaptureRules {
    public const val STATE_PLAYING: String = "playing"
    public const val STATE_STOPPED: String = "stopped"
    public const val MODE_PHOTO: String = "photo"
    public const val MODE_SCAN: String = "scan"

    public val SPEAKER_REASONS: Set<String> = setOf("user", "busy", "disabled", "network", "session-closed", "error")
    public val CAPTURE_REASONS: Set<String> = setOf("declined", "cancelled", "expired", "permission", "busy", "error")

    /** How long a capture request stays answerable on the phone. */
    public const val CAPTURE_EXPIRY_MS: Long = 120_000L

    public fun isValid(m: SpeakerStart): Boolean =
        StreamRules.sidBytes(m.sid) != null && StreamRules.isPrivateIpv4(m.host) && m.port in StreamRules.PORT

    public fun isValid(m: SpeakerStop): Boolean = StreamRules.sidBytes(m.sid) != null && m.reason in SPEAKER_REASONS

    public fun isValid(m: CaptureRequest): Boolean = StreamRules.sidBytes(m.rid) != null && (m.mode == MODE_PHOTO || m.mode == MODE_SCAN)
}
