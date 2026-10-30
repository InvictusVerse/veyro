package com.veyro.core.protocol.media

import com.veyro.core.protocol.AeadStreamException
import com.veyro.core.protocol.ProtocolConstants
import java.nio.charset.StandardCharsets

/**
 * VMS1: the sealed media socket of screen mirroring (ADR-066, 04-PROTOCOL.md 7.5).
 *
 * One TCP connection per stream session. The phone dials the PC. Each side first sends a
 * cleartext 40-byte preface with a fresh 16-byte salt (the VTC3 pattern of ADR-058), then every
 * byte is a sealed record:
 *
 * ```
 * okm   = exportKey(label(source), "VMS1" || u8 source || sid || saltPhone || saltPc, 64)
 * kUp   = okm[0..32]   stream id 1, phone seals, PC opens
 * kDown = okm[32..64]  stream id 2, PC seals, phone opens
 * record = u8 kind || u8 flags || u32 len || ChaCha20-Poly1305(k, nonce(stream id, counter), aad = first 6 bytes)
 * ```
 *
 * The counter is implicit (one per direction), so a reordered, repeated or dropped record fails
 * to open and closes the connection. Video and audio share the one upward counter; one writer
 * thread serialises them. Both carry `ptsUs` from the phone's one monotonic clock, which is what
 * lets the PC keep them in sync.
 *
 * Pure: no `android.*`, mirrored byte for byte by `Veyro.Core/Protocol/Media/Vms1.cs`.
 */
public object Vms1 {

    public val MAGIC: ByteArray = byteArrayOf(0x56, 0x4D, 0x53, 0x31) // "VMS1"
    public const val VERSION: Int = 1

    public const val SOURCE_SCREEN: Int = 0x01
    public const val SOURCE_CAMERA: Int = 0x02

    /** ADR-073: the PC's sound to the phone. The only source whose media the PC sends. */
    public const val SOURCE_SPEAKER: Int = 0x03

    public const val SID_SIZE: Int = 16
    public const val SALT_SIZE: Int = 16
    public const val PREFACE_SIZE: Int = 40
    public const val OKM_SIZE: Int = 64
    public const val STREAM_UP: Int = 1
    public const val STREAM_DOWN: Int = 2

    /** Clear record header: kind, flags, u32 sealed length. Also the AAD. */
    public const val HEADER_SIZE: Int = 6
    public const val TAG_SIZE: Int = 16

    public const val KIND_AUTH: Int = 0x01
    public const val KIND_AUTH_ACK: Int = 0x02
    public const val KIND_CLOSE: Int = 0x03
    public const val KIND_VIDEO_CONFIG: Int = 0x10
    public const val KIND_VIDEO: Int = 0x11
    public const val KIND_AUDIO_CONFIG: Int = 0x20
    public const val KIND_AUDIO: Int = 0x21

    /** `VIDEO` flags. */
    public const val FLAG_KEY: Int = 0x01
    public const val FLAG_START: Int = 0x02
    public const val FLAG_END: Int = 0x04
    public const val FLAG_DISCONTINUITY: Int = 0x08
    private const val VIDEO_FLAGS = FLAG_KEY or FLAG_START or FLAG_END or FLAG_DISCONTINUITY

    /** Data bytes in one `VIDEO` record. */
    public const val MAX_FRAGMENT: Int = 65_536

    /** `u64 ptsUs || u32 auLength` in front of a `START` fragment's data. */
    public const val START_PREFIX: Int = 12

    /** Largest plaintext of any record: a `START` fragment. */
    public const val MAX_PLAINTEXT: Int = START_PREFIX + MAX_FRAGMENT

    /** One access unit (a 4K keyframe is about 1-2 MiB). */
    public const val MAX_ACCESS_UNIT: Int = 8 * 1024 * 1024

    public const val MAX_AUDIO_BYTES: Int = 16_384
    public const val MAX_ID_BYTES: Int = 64
    public const val MAX_REASON_BYTES: Int = 64

    public const val VIDEO_CODEC_H264: Int = 1
    public const val VIDEO_CODEC_H265: Int = 2
    public const val COLOUR_BT709_LIMITED: Int = 1
    public const val VIDEO_CONFIG_SIZE: Int = 22

    public const val AUDIO_CODEC_PCM_S16LE: Int = 1
    public const val AUDIO_CONFIG_SIZE: Int = 6

    /** The exporter label of [source] (ADR-058 reserved them). */
    public fun exportLabel(source: Int): String = when (source) {
        SOURCE_SCREEN -> ProtocolConstants.EXPORT_LABEL_SCREEN
        SOURCE_CAMERA -> ProtocolConstants.EXPORT_LABEL_CAMERA
        SOURCE_SPEAKER -> ProtocolConstants.EXPORT_LABEL_SPEAKER
        else -> throw IllegalArgumentException("unknown VMS1 source")
    }

    /** `"VMS1" || u8 source || sid || saltPhone || saltPc` (53 bytes). */
    public fun exportContext(source: Int, sid: ByteArray, saltPhone: ByteArray, saltPc: ByteArray): ByteArray {
        require(sid.size == SID_SIZE && saltPhone.size == SALT_SIZE && saltPc.size == SALT_SIZE) { "VMS1 sid and salts are 16 bytes" }
        val out = ByteArray(4 + 1 + SID_SIZE + 2 * SALT_SIZE)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = source.toByte()
        System.arraycopy(sid, 0, out, 5, SID_SIZE)
        System.arraycopy(saltPhone, 0, out, 5 + SID_SIZE, SALT_SIZE)
        System.arraycopy(saltPc, 0, out, 5 + SID_SIZE + SALT_SIZE, SALT_SIZE)
        return out
    }

    // ── Preface ───────────────────────────────────────────────────────────────────────────────

    public class Preface(public val source: Int, public val sid: ByteArray, public val salt: ByteArray)

    /** `"VMS1" | u8 1 | u8 source | u16 0 | sid | salt`. */
    public fun encodePreface(source: Int, sid: ByteArray, salt: ByteArray): ByteArray {
        require(isSource(source)) { "unknown VMS1 source" }
        require(sid.size == SID_SIZE && salt.size == SALT_SIZE) { "VMS1 sid and salt are 16 bytes" }
        val out = ByteArray(PREFACE_SIZE)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = VERSION.toByte()
        out[5] = source.toByte()
        System.arraycopy(sid, 0, out, 8, SID_SIZE)
        System.arraycopy(salt, 0, out, 24, SALT_SIZE)
        return out
    }

    public fun parsePreface(bytes: ByteArray): Preface {
        if (bytes.size != PREFACE_SIZE) throw AeadStreamException("VMS1 preface has a bad length")
        for (i in 0 until 4) if (bytes[i] != MAGIC[i]) throw AeadStreamException("VMS1 preface has a bad magic")
        if (bytes[4].toInt() != VERSION) throw AeadStreamException("VMS1 version not supported")
        val source = bytes[5].toInt() and 0xFF
        if (!isSource(source)) throw AeadStreamException("VMS1 preface has an unknown source")
        if (bytes[6].toInt() != 0 || bytes[7].toInt() != 0) throw AeadStreamException("VMS1 preface reserved bytes set")
        return Preface(source, bytes.copyOfRange(8, 24), bytes.copyOfRange(24, 40))
    }

    // ── Records ───────────────────────────────────────────────────────────────────────────────

    public fun isSource(source: Int): Boolean = source == SOURCE_SCREEN || source == SOURCE_CAMERA || source == SOURCE_SPEAKER

    /**
     * True when [kind] may be sent by the phone ([fromPhone]) or by the PC on a [source] socket.
     * Media flows from the phone, except on a speaker socket, where only the PC sends sound.
     */
    public fun allowedFrom(kind: Int, fromPhone: Boolean, source: Int = SOURCE_SCREEN): Boolean = when (kind) {
        KIND_CLOSE -> true
        KIND_AUTH -> fromPhone
        KIND_AUTH_ACK -> !fromPhone
        KIND_VIDEO_CONFIG, KIND_VIDEO -> fromPhone && source != SOURCE_SPEAKER
        KIND_AUDIO_CONFIG, KIND_AUDIO -> fromPhone == (source != SOURCE_SPEAKER)
        else -> false
    }

    /** Header check before any buffer is allocated. Throws on anything outside the rules. */
    public fun checkHeader(kind: Int, flags: Int, sealedLength: Int, fromPhone: Boolean, source: Int = SOURCE_SCREEN) {
        if (!allowedFrom(kind, fromPhone, source)) throw AeadStreamException("VMS1 record kind not allowed")
        if (kind == KIND_VIDEO) {
            if (flags and VIDEO_FLAGS.inv() != 0) throw AeadStreamException("VMS1 video flags not allowed")
        } else if (flags != 0) {
            throw AeadStreamException("VMS1 record flags not allowed")
        }
        if (sealedLength < TAG_SIZE + 1 || sealedLength > MAX_PLAINTEXT + TAG_SIZE) {
            throw AeadStreamException("VMS1 record has a bad length")
        }
    }

    public fun writeHeader(dst: ByteArray, kind: Int, flags: Int, sealedLength: Int) {
        dst[0] = kind.toByte()
        dst[1] = flags.toByte()
        writeInt(dst, 2, sealedLength)
    }

    /** The plaintext of `AUTH`: `u16 len | phoneId | u16 len | pcId | u8 source | sid`. */
    public fun encodeAuth(phoneId: String, pcId: String, source: Int, sid: ByteArray): ByteArray {
        val phone = phoneId.toByteArray(StandardCharsets.UTF_8)
        val pc = pcId.toByteArray(StandardCharsets.UTF_8)
        require(phone.size in 1..MAX_ID_BYTES && pc.size in 1..MAX_ID_BYTES) { "VMS1 ids are 1..64 bytes" }
        require(sid.size == SID_SIZE) { "VMS1 sid is 16 bytes" }
        val out = ByteArray(2 + phone.size + 2 + pc.size + 1 + SID_SIZE)
        var p = writeShort(out, 0, phone.size)
        System.arraycopy(phone, 0, out, p, phone.size); p += phone.size
        p = writeShort(out, p, pc.size)
        System.arraycopy(pc, 0, out, p, pc.size); p += pc.size
        out[p++] = source.toByte()
        System.arraycopy(sid, 0, out, p, SID_SIZE)
        return out
    }

    public class Auth(public val phoneId: String, public val pcId: String, public val source: Int, public val sid: ByteArray)

    public fun decodeAuth(src: ByteArray, len: Int): Auth {
        var p = 0
        fun need(n: Int) { if (p + n > len) throw AeadStreamException("VMS1 AUTH is truncated") }
        need(2)
        val phoneLen = readShort(src, p); p += 2
        if (phoneLen !in 1..MAX_ID_BYTES) throw AeadStreamException("VMS1 AUTH id length")
        need(phoneLen)
        val phone = String(src, p, phoneLen, StandardCharsets.UTF_8); p += phoneLen
        need(2)
        val pcLen = readShort(src, p); p += 2
        if (pcLen !in 1..MAX_ID_BYTES) throw AeadStreamException("VMS1 AUTH id length")
        need(pcLen)
        val pc = String(src, p, pcLen, StandardCharsets.UTF_8); p += pcLen
        need(1 + SID_SIZE)
        val source = src[p++].toInt() and 0xFF
        val sid = src.copyOfRange(p, p + SID_SIZE); p += SID_SIZE
        if (p != len) throw AeadStreamException("VMS1 AUTH has trailing bytes")
        return Auth(phone, pc, source, sid)
    }

    /** `u8 code || u16 len || reason` (ASCII, at most 64 bytes). */
    public fun encodeClose(code: Int, reason: String): ByteArray {
        val r = reason.toByteArray(StandardCharsets.US_ASCII)
        require(r.size <= MAX_REASON_BYTES && reason.all { it.code in 0x21..0x7E }) { "VMS1 close reason" }
        val out = ByteArray(3 + r.size)
        out[0] = code.toByte()
        writeShort(out, 1, r.size)
        System.arraycopy(r, 0, out, 3, r.size)
        return out
    }

    public class Close(public val code: Int, public val reason: String)

    public fun decodeClose(src: ByteArray, len: Int): Close {
        if (len < 3) throw AeadStreamException("VMS1 CLOSE is truncated")
        val n = readShort(src, 1)
        if (n > MAX_REASON_BYTES || 3 + n != len) throw AeadStreamException("VMS1 CLOSE reason length")
        for (i in 3 until 3 + n) if ((src[i].toInt() and 0xFF) !in 0x21..0x7E) throw AeadStreamException("VMS1 CLOSE reason not ASCII")
        return Close(src[0].toInt() and 0xFF, String(src, 3, n, StandardCharsets.US_ASCII))
    }

    /** The 22-byte `VIDEO_CONFIG` (04-PROTOCOL.md 7.5.4). */
    public data class VideoConfig(
        val codec: Int,
        val width: Int,
        val height: Int,
        val visX: Int,
        val visY: Int,
        val visW: Int,
        val visH: Int,
        /** Clockwise rotation the receiver applies before display: 0, 90, 180 or 270. */
        val rotation: Int,
        val mirror: Boolean,
        /** +1 with every new config in the session; the first is 1. */
        val generation: Long,
        val colour: Int = COLOUR_BT709_LIMITED,
    ) {
        init {
            require(codec == VIDEO_CODEC_H264 || codec == VIDEO_CODEC_H265) { "VMS1 video codec" }
            require(colour == COLOUR_BT709_LIMITED) { "VMS1 colour" }
            require(width in 16..8192 && height in 16..8192) { "VMS1 coded size" }
            require(visW >= 1 && visH >= 1 && visX >= 0 && visY >= 0 && visX + visW <= width && visY + visH <= height) { "VMS1 visible rectangle" }
            require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) { "VMS1 rotation" }
            require(generation in 1..0xFFFF_FFFFL) { "VMS1 generation" }
        }
    }

    public fun encodeVideoConfig(c: VideoConfig): ByteArray {
        val out = ByteArray(VIDEO_CONFIG_SIZE)
        out[0] = c.codec.toByte()
        out[1] = c.colour.toByte()
        writeShort(out, 2, c.width)
        writeShort(out, 4, c.height)
        writeShort(out, 6, c.visX)
        writeShort(out, 8, c.visY)
        writeShort(out, 10, c.visW)
        writeShort(out, 12, c.visH)
        writeShort(out, 14, c.rotation)
        out[16] = if (c.mirror) 1 else 0
        out[17] = 0
        writeInt(out, 18, c.generation.toInt())
        return out
    }

    public fun decodeVideoConfig(src: ByteArray, len: Int): VideoConfig {
        if (len != VIDEO_CONFIG_SIZE) throw AeadStreamException("VMS1 VIDEO_CONFIG has a bad length")
        val flags = src[16].toInt() and 0xFF
        if (flags and 0x01.inv() != 0 || src[17].toInt() != 0) throw AeadStreamException("VMS1 VIDEO_CONFIG reserved bits set")
        return try {
            VideoConfig(
                codec = src[0].toInt() and 0xFF,
                colour = src[1].toInt() and 0xFF,
                width = readShort(src, 2),
                height = readShort(src, 4),
                visX = readShort(src, 6),
                visY = readShort(src, 8),
                visW = readShort(src, 10),
                visH = readShort(src, 12),
                rotation = readShort(src, 14),
                mirror = flags == 1,
                generation = readInt(src, 18).toLong() and 0xFFFF_FFFFL,
            )
        } catch (e: IllegalArgumentException) {
            throw AeadStreamException("VMS1 VIDEO_CONFIG out of range", e)
        }
    }

    /** `u8 codec || u8 channels || u32 rate`. */
    public data class AudioConfig(val channels: Int, val rate: Int, val codec: Int = AUDIO_CODEC_PCM_S16LE) {
        init {
            require(codec == AUDIO_CODEC_PCM_S16LE) { "VMS1 audio codec" }
            require(channels in 1..2) { "VMS1 audio channels" }
            require(rate in 8_000..96_000) { "VMS1 audio rate" }
        }

        /** Bytes per sample frame (all channels). */
        public val frameBytes: Int get() = 2 * channels
    }

    public fun encodeAudioConfig(c: AudioConfig): ByteArray {
        val out = ByteArray(AUDIO_CONFIG_SIZE)
        out[0] = c.codec.toByte()
        out[1] = c.channels.toByte()
        writeInt(out, 2, c.rate)
        return out
    }

    public fun decodeAudioConfig(src: ByteArray, len: Int): AudioConfig {
        if (len != AUDIO_CONFIG_SIZE) throw AeadStreamException("VMS1 AUDIO_CONFIG has a bad length")
        return try {
            AudioConfig(codec = src[0].toInt() and 0xFF, channels = src[1].toInt() and 0xFF, rate = readInt(src, 2))
        } catch (e: IllegalArgumentException) {
            throw AeadStreamException("VMS1 AUDIO_CONFIG out of range", e)
        }
    }

    // ── Big-endian helpers ────────────────────────────────────────────────────────────────────

    internal fun writeShort(dst: ByteArray, off: Int, v: Int): Int {
        dst[off] = (v ushr 8).toByte()
        dst[off + 1] = v.toByte()
        return off + 2
    }

    internal fun writeInt(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v ushr 24).toByte()
        dst[off + 1] = (v ushr 16).toByte()
        dst[off + 2] = (v ushr 8).toByte()
        dst[off + 3] = v.toByte()
    }

    internal fun writeLong(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) dst[off + i] = (v ushr (56 - 8 * i)).toByte()
    }

    internal fun readShort(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 8) or (src[off + 1].toInt() and 0xFF)

    internal fun readInt(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 24) or ((src[off + 1].toInt() and 0xFF) shl 16) or
            ((src[off + 2].toInt() and 0xFF) shl 8) or (src[off + 3].toInt() and 0xFF)

    internal fun readLong(src: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (src[off + i].toLong() and 0xFF)
        return v
    }
}
