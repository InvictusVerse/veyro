package com.veyro.core.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.projection.MediaProjection
import android.os.Process
import com.veyro.core.common.VeyroLog
import com.veyro.core.protocol.media.MediaSink
import com.veyro.core.protocol.media.Vms1

/**
 * The phone's own sound while mirroring (ADR-066): what apps play, through Android's playback
 * capture on the session's [MediaProjection]. Never the microphone: the recorder is built only
 * with an [AudioPlaybackCaptureConfiguration], for media, game and unknown usages, with Veyro's
 * own sound excluded; calls and apps that opt out stay silent (Android's rules). 48 kHz stereo
 * PCM in 10 ms packets, stamped on the same monotonic clock as the video. Samples are user data
 * and are never logged.
 */
internal class PlaybackAudioCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val sink: MediaSink,
) {

    companion object {
        private const val TAG = "PlaybackAudio"
        const val RATE = 48_000
        const val CHANNELS = 2
        private const val FRAME_BYTES = 2 * CHANNELS
        private const val PACKET_FRAMES = RATE / 100
        private const val PACKET_BYTES = PACKET_FRAMES * FRAME_BYTES
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    /** Starts capturing; false without the audio permission or when Android refuses. */
    @SuppressLint("MissingPermission") // checked just below
    fun start(): Boolean {
        if (running) return true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val recorder = try {
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid())
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, PACKET_BYTES * 20))
                .setAudioPlaybackCaptureConfig(capture)
                .build()
        } catch (e: Exception) {
            VeyroLog.w(TAG) { "Playback capture refused: ${e.javaClass.simpleName}" }
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        try {
            recorder.startRecording()
        } catch (e: IllegalStateException) {
            recorder.release()
            return false
        }
        record = recorder
        running = true
        sink.audioConfig(Vms1.AudioConfig(CHANNELS, RATE))
        thread = Thread({ loop(recorder) }, "VeyroAudio").apply { start() }
        VeyroLog.i(TAG) { "Phone sound capture started" }
        return true
    }

    private fun loop(recorder: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val stamp = AudioTimestamp()
        var frames = 0L
        while (running) {
            val packet = ByteArray(PACKET_BYTES)
            val read = recorder.read(packet, 0, PACKET_BYTES, AudioRecord.READ_BLOCKING)
            if (read < 0) {
                VeyroLog.w(TAG) { "Phone sound read failed: $read" }
                break
            }
            val whole = read - read % FRAME_BYTES
            if (whole <= 0) continue
            val now = System.nanoTime()
            // The packet's first frame on the monotonic clock the video uses.
            val ptsNs = if (recorder.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                stamp.nanoTime + (frames - stamp.framePosition) * 1_000_000_000L / RATE
            } else {
                now - whole / FRAME_BYTES * 1_000_000_000L / RATE
            }
            frames += whole / FRAME_BYTES
            sink.audio(maxOf(0L, ptsNs / 1_000L), packet, whole)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { record?.stop() }
        thread?.join(500)
        runCatching { record?.release() }
        record = null
        thread = null
        VeyroLog.i(TAG) { "Phone sound capture stopped" }
    }
}
