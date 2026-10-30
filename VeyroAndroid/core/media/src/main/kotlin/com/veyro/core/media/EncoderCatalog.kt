package com.veyro.core.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.veyro.core.common.VeyroLog

/** One video encoder on this phone. [codec] is the wire name, `h264` or `h265`. */
public data class EncoderInfo(
    val name: String,
    val codec: String,
    val mime: String,
    val hw: Boolean,
    val maxW: Int,
    val maxH: Int,
    val fps: Int,
)

/**
 * The phone's H.264 and H.265 encoders (ADR-066), read once from `MediaCodecList`. Hardware
 * means `isHardwareAccelerated`; aliases are skipped. Software H.265 is never offered (too slow
 * on a phone), as 04-PROTOCOL.md 6.9 says.
 */
public class EncoderCatalog {

    public companion object {
        private const val TAG = "EncoderCatalog"
        public const val CODEC_H264: String = "h264"
        public const val CODEC_H265: String = "h265"

        public fun mimeOf(codec: String): String =
            if (codec == CODEC_H265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
    }

    /** Every usable encoder, hardware first. */
    public val all: List<EncoderInfo> by lazy { load() }

    /** The best encoder per codec and kind, for `streamCaps.encoders`. */
    public fun summary(): List<EncoderInfo> =
        all.groupBy { it.codec to it.hw }
            .map { (_, list) -> list.maxBy { it.maxW.toLong() * it.maxH } }

    /**
     * The encoders to try for [codec] with [hw], in order (04-PROTOCOL.md 6.9): hardware ones of
     * that codec when [hw], then software H.264, never software H.265.
     */
    public fun candidates(codec: String, hw: Boolean): List<EncoderInfo> {
        val hardware = if (hw) all.filter { it.hw && it.codec == codec } else emptyList()
        val softwareAvc = all.filter { !it.hw && it.codec == CODEC_H264 }
        // No software H.264 on this phone (unusual): its hardware H.264 is better than nothing.
        val fallback = if (softwareAvc.isEmpty()) all.filter { it.hw && it.codec == CODEC_H264 } else softwareAvc
        return (hardware + fallback).distinct()
    }

    private fun load(): List<EncoderInfo> {
        val found = mutableListOf<EncoderInfo>()
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrDefault(emptyArray())
        for (info in infos) {
            if (!info.isEncoder || info.isAlias) continue
            for (type in info.supportedTypes) {
                val codec = when {
                    type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) -> CODEC_H264
                    type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) -> CODEC_H265
                    else -> continue
                }
                val hw = info.isHardwareAccelerated && !info.isSoftwareOnly
                if (codec == CODEC_H265 && !hw) continue
                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) continue
                val video = caps.videoCapabilities ?: continue
                val maxW = video.supportedWidths.upper
                val maxH = video.supportedHeights.upper
                if (maxW < 16 || maxH < 16) continue
                val fps = video.supportedFrameRates.upper.coerceIn(1, 240)
                found += EncoderInfo(info.name, codec, type, hw, minOf(maxW, 8192), minOf(maxH, 8192), fps)
            }
        }
        val sorted = found.sortedWith(compareByDescending<EncoderInfo> { it.hw }.thenByDescending { it.maxW.toLong() * it.maxH })
        VeyroLog.i(TAG) { "Video encoders: " + sorted.joinToString { "${it.codec} ${if (it.hw) "hw" else "sw"} ${it.maxW}x${it.maxH}" } }
        return sorted
    }
}
