package com.veyro.core.link

import com.veyro.core.protocol.Base64Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Screen mirroring control messages (ADR-066, 04-PROTOCOL.md 6.9). The PC decides every quality
// setting (codec, hardware encoding, size, frame rate, bitrate, audio); the phone only reports
// what it can do and whether its user allows it, asks Android for capture consent, and encodes
// what the PC asked for. Named `stream*` because `mediaState` is ADR-026's media-session message.
// Gated by SCREEN_SOURCE on the phone and SCREEN_VIEW on the PC. Frames, audio and codec bytes
// travel on the VMS1 socket (7.5), never here.

/** Phone to PC, after hello and whenever a value changes. */
@Serializable
@SerialName("streamCaps")
public data class StreamCaps(
    /** The phone's user lets this PC mirror its screen (still asks Android each time). */
    val allowed: Boolean = false,
    /** The display in its current rotation; absent when [allowed] is false. */
    val screen: StreamScreen? = null,
    /** Every usable encoder, hardware and software; the PC picks one. */
    val encoders: List<StreamEncoder> = emptyList(),
    /**
     * This phone can capture its internal audio, what apps play (Android 10+ playback capture).
     * Never the microphone. Whether to send it is the PC's choice ([StreamStart.audio]).
     */
    val audio: Boolean = false,
) : PeerMessage

@Serializable
public data class StreamScreen(val w: Int, val h: Int, val maxFps: Int)

@Serializable
public data class StreamEncoder(val codec: String, val hw: Boolean, val maxW: Int, val maxH: Int, val fps: Int)

/** PC to phone: start mirroring with exactly these settings. */
@Serializable
@SerialName("streamStart")
public data class StreamStart(
    /** 22-character unpadded base64url of 16 random bytes, never reused on a connection. */
    val sid: String,
    val source: String,
    val codec: String,
    /** Use a hardware encoder. The phone falls back to software only for H.264 and says so in [StreamState.hw]. */
    val hw: Boolean,
    val maxLong: Int,
    val fps: Int,
    val bitrate: Int,
    /**
     * The PC asks for the phone's internal audio (playback capture only, never the microphone).
     * Android grants playback capture only with its audio permission, which it labels
     * "Microphone"; the phone asks for it the first time the PC requests audio, and streams
     * video alone (reporting `audio: false`) if the user refuses.
     */
    val audio: Boolean = false,
    /** Where the PC listens for this session's VMS1 socket. */
    val host: String,
    val port: Int,
) : PeerMessage

/** Phone to PC: what the session is doing and the values in use. */
@Serializable
@SerialName("streamState")
public data class StreamState(
    val sid: String,
    val state: String,
    val reason: String? = null,
    val codec: String? = null,
    val w: Int? = null,
    val h: Int? = null,
    val fps: Int? = null,
    val bitrate: Int? = null,
    val hw: Boolean? = null,
    val audio: Boolean? = null,
    /** Android thermal status 0..6 while streaming. */
    val thermal: Int? = null,
) : PeerMessage

/** PC to phone: new limits mid-session (the PC's bitrate control, the user's changes, pause). */
@Serializable
@SerialName("streamConfig")
public data class StreamConfig(
    val sid: String,
    val maxLong: Int? = null,
    val fps: Int? = null,
    val bitrate: Int? = null,
    val audio: Boolean? = null,
    val paused: Boolean? = null,
) : PeerMessage

/** PC to phone: send a keyframe (decode error, overflow). At most one per 500 ms is honoured. */
@Serializable
@SerialName("streamKeyframe")
public data class StreamKeyframe(val sid: String) : PeerMessage

/** Either direction: the session is over. */
@Serializable
@SerialName("streamStop")
public data class StreamStop(val sid: String, val reason: String) : PeerMessage

/** Values and field rules of 04-PROTOCOL.md 6.9; a message that breaks one is dropped. */
public object StreamRules {

    public const val SOURCE_SCREEN: String = "screen"
    public const val SOURCE_CAMERA: String = "camera"
    public const val CODEC_H264: String = "h264"
    public const val CODEC_H265: String = "h265"

    public const val STATE_ASKING: String = "asking"
    public const val STATE_STARTING: String = "starting"
    public const val STATE_STREAMING: String = "streaming"
    public const val STATE_PAUSED: String = "paused"
    public const val STATE_STOPPED: String = "stopped"

    public val REASONS: Set<String> = setOf(
        "user", "declined", "timeout", "busy", "disabled", "permission", "unsupported",
        "projection-ended", "locked", "thermal", "encoder", "network", "session-closed", "error",
    )

    public val MAX_LONG: IntRange = 160..3840
    public val FPS: IntRange = 1..120
    public val BITRATE: IntRange = 250_000..80_000_000
    public val PORT: IntRange = 1024..65535

    /** The 16 sid bytes, or null when [sid] is not 22 characters of canonical base64url. */
    public fun sidBytes(sid: String?): ByteArray? {
        if (sid == null || sid.length != 22 || sid.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }) return null
        return Base64Url.decodeOrNull(sid)?.takeIf { it.size == 16 }
    }

    public fun isCodec(codec: String?): Boolean = codec == CODEC_H264 || codec == CODEC_H265

    /** Dotted IPv4 in 10/8, 172.16/12, 192.168/16 or 169.254/16. */
    public fun isPrivateIpv4(host: String?): Boolean {
        val parts = host?.split('.') ?: return false
        if (parts.size != 4) return false
        val b = parts.map { p -> if (p.isEmpty() || p.length > 3 || p.any { !it.isDigit() } || (p.length > 1 && p[0] == '0')) return false else p.toInt() }
        if (b.any { it > 255 }) return false
        return b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
    }

    /** A `streamStart` the phone may act on. Camera is reserved (ADR-067) and not accepted yet. */
    public fun isValid(m: StreamStart): Boolean =
        sidBytes(m.sid) != null && m.source == SOURCE_SCREEN && isCodec(m.codec) &&
            m.maxLong in MAX_LONG && m.fps in FPS && m.bitrate in BITRATE &&
            isPrivateIpv4(m.host) && m.port in PORT

    public fun isValid(m: StreamConfig): Boolean =
        sidBytes(m.sid) != null && (m.maxLong == null || m.maxLong in MAX_LONG) &&
            (m.fps == null || m.fps in FPS) && (m.bitrate == null || m.bitrate in BITRATE)

    public fun isValid(m: StreamKeyframe): Boolean = sidBytes(m.sid) != null

    public fun isValid(m: StreamStop): Boolean = sidBytes(m.sid) != null && m.reason in REASONS
}
