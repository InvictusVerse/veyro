package com.veyro.core.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.Surface
import com.veyro.core.common.VeyroLog

/**
 * One configured video encoder with a surface input (ADR-066). Low-latency settings: VBR,
 * no B-frames, realtime priority, a frame repeated after 100 ms of a still screen, keyframes
 * every 10 s and on demand. Output is Annex B; every keyframe goes out with its parameter
 * sets in front (copied from the codec-config buffer when the encoder does not prepend them).
 * Callbacks run on the given [Handler]'s thread. Frames are user data: nothing logs them.
 */
internal class ScreenEncoder private constructor(
    val info: EncoderInfo,
    private val codec: MediaCodec,
    val surface: Surface,
    val width: Int,
    val height: Int,
    val fps: Int,
    var bitrate: Int,
) {

    /** An encoded frame. [data] is owned by the receiver. */
    fun interface Output {
        fun onFrame(encoder: ScreenEncoder, ptsUs: Long, data: ByteArray, key: Boolean)
    }

    fun interface Failure {
        fun onError(encoder: ScreenEncoder, error: Exception)
    }

    private var parameterSets: ByteArray? = null
    @Volatile private var released = false

    fun start() {
        codec.start()
    }

    fun requestKeyframe() {
        setParameter(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
    }

    fun updateBitrate(bps: Int) {
        bitrate = bps
        setParameter(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
    }

    /** No frames while suspended; resume with a keyframe. */
    fun setSuspended(suspended: Boolean) {
        setParameter(MediaCodec.PARAMETER_KEY_SUSPEND, if (suspended) 1 else 0)
        if (!suspended) requestKeyframe()
    }

    private fun setParameter(key: String, value: Int) {
        if (released) return
        try {
            codec.setParameters(Bundle().apply { putInt(key, value) })
        } catch (e: IllegalStateException) {
            VeyroLog.w(TAG) { "Encoder parameter not applied: ${e.javaClass.simpleName}" }
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
    }

    private fun onOutput(index: Int, info: MediaCodec.BufferInfo, output: Output) {
        if (released) return
        try {
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    parameterSets = ByteArray(info.size).also { buffer.get(it) }
                } else {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val sets = parameterSets
                    val data = if (key && sets != null && !startsWithParameterSets(buffer.duplicate(), this.info.codec)) {
                        ByteArray(sets.size + info.size).also {
                            System.arraycopy(sets, 0, it, 0, sets.size)
                            buffer.get(it, sets.size, info.size)
                        }
                    } else {
                        ByteArray(info.size).also { buffer.get(it) }
                    }
                    output.onFrame(this, info.presentationTimeUs, data, key)
                }
            }
            codec.releaseOutputBuffer(index, false)
        } catch (e: IllegalStateException) {
            // Released under us (rebuild or stop).
        }
    }

    companion object {
        private const val TAG = "ScreenEncoder"
        private const val KEYFRAME_INTERVAL_S = 10
        private const val REPEAT_AFTER_US = 100_000L

        /**
         * Configures [info] for [width] x [height]. Tries the low-latency format first, then
         * without the header and profile keys some encoders refuse. Null when nothing works.
         */
        fun create(
            info: EncoderInfo,
            width: Int,
            height: Int,
            fps: Int,
            bitrate: Int,
            handler: Handler,
            output: Output,
            failure: Failure,
        ): ScreenEncoder? {
            val attempts = listOf(true to true, false to true, true to false, false to false)
            for ((prepend, profile) in attempts) {
                val codec = runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull() ?: return null
                try {
                    val format = format(codec, info, width, height, fps, bitrate, prepend, profile)
                    var encoder: ScreenEncoder? = null
                    codec.setCallback(object : MediaCodec.Callback() {
                        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, bufferInfo: MediaCodec.BufferInfo) {
                            val self = encoder
                            if (self == null) runCatching { codec.releaseOutputBuffer(index, false) } else self.onOutput(index, bufferInfo, output)
                        }

                        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                            encoder?.let { if (!it.released) failure.onError(it, e) }
                        }

                        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
                    }, handler)
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val surface = codec.createInputSurface()
                    return ScreenEncoder(info, codec, surface, width, height, fps, bitrate).also { encoder = it }
                } catch (e: Exception) {
                    // IllegalArgumentException, CodecException (an IllegalStateException), ...
                    runCatching { codec.release() }
                }
            }
            VeyroLog.w(TAG) { "Encoder ${info.name} refused ${width}x$height at $fps fps" }
            return null
        }

        private fun format(
            codec: MediaCodec,
            info: EncoderInfo,
            width: Int,
            height: Int,
            fps: Int,
            bitrate: Int,
            prepend: Boolean,
            profile: Boolean,
        ): MediaFormat = MediaFormat.createVideoFormat(info.mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_S)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            val caps = runCatching { codec.codecInfo.getCapabilitiesForType(info.mime) }.getOrNull()
            if (caps?.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            if (prepend) setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            if (profile && caps != null) {
                // AVC High (better pictures at the same bitrate) or HEVC Main; the PC decodes both.
                val wanted = if (info.codec == EncoderCatalog.CODEC_H265) {
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                } else {
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                }
                caps.profileLevels.filter { it.profile == wanted }.maxByOrNull { it.level }?.let {
                    setInteger(MediaFormat.KEY_PROFILE, it.profile)
                    setInteger(MediaFormat.KEY_LEVEL, it.level)
                }
            }
        }

        /** The first NAL unit is a parameter set: SPS (H.264), VPS or SPS (H.265). */
        internal fun startsWithParameterSets(buffer: java.nio.ByteBuffer, codec: String): Boolean {
            var i = buffer.position()
            val end = buffer.limit()
            while (i + 3 < end) {
                if (buffer.get(i).toInt() == 0 && buffer.get(i + 1).toInt() == 0) {
                    val third = buffer.get(i + 2).toInt()
                    val header = when {
                        third == 1 -> i + 3
                        third == 0 && i + 4 < end && buffer.get(i + 3).toInt() == 1 -> i + 4
                        else -> -1
                    }
                    if (header in 0 until end) {
                        val b = buffer.get(header).toInt() and 0xFF
                        return if (codec == EncoderCatalog.CODEC_H265) (b shr 1) and 0x3F in 32..33 else b and 0x1F == 7
                    }
                }
                i++
            }
            return false
        }
    }
}
