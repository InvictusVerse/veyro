package com.veyro.core.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.veyro.core.common.VeyroLog
import com.veyro.core.protocol.media.Vms1
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Plays the PC's sound on the phone (ADR-073) through a normal media AudioTrack, so the phone's
 * own volume, headphones and sound effects apply. A small jitter buffer: playback starts (and
 * restarts after a gap) once [PREBUFFER_MS] is queued; more than [MAX_QUEUE_MS] queued drops the
 * oldest, so the delay stays short. Sound is user data and is never logged.
 */
public class PcAudioPlayer {

    public companion object {
        private const val TAG = "PcAudioPlayer"
        public const val PREBUFFER_MS: Int = 40
        public const val MAX_QUEUE_MS: Int = 200
    }

    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val queue = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var config: Vms1.AudioConfig? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Called for each AUDIO_CONFIG; a new format rebuilds the track. */
    public fun configure(next: Vms1.AudioConfig) {
        if (lock.withLock { next == config && running }) return
        // The old track's thread ends before the new one starts, so they never share the queue.
        stop()
        lock.withLock {
            config = next
            running = true
            thread = Thread({ play(next) }, "VeyroPcAudio").apply { start() }
        }
    }

    public fun push(samples: ByteArray) {
        lock.withLock {
            val c = config ?: return
            if (!running) return
            queue.addLast(samples)
            queuedBytes += samples.size
            val max = c.rate * c.frameBytes * MAX_QUEUE_MS / 1000
            while (queuedBytes > max && queue.size > 1) queuedBytes -= queue.removeFirst().size
            ready.signal()
        }
    }

    private fun play(c: Vms1.AudioConfig) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val channelMask = if (c.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(c.rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val audio = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(c.rate).setChannelMask(channelMask).build(),
                )
                .setBufferSizeInBytes(maxOf(minimum, c.rate * c.frameBytes * PREBUFFER_MS / 1000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            VeyroLog.w(TAG) { "PC sound not played: ${e.javaClass.simpleName}" }
            return
        }
        lock.withLock { track = audio }
        val prebuffer = c.rate * c.frameBytes * PREBUFFER_MS / 1000
        var buffering = true
        try {
            audio.play()
            while (running) {
                val packet = lock.withLock {
                    while (running && (queue.isEmpty() || (buffering && queuedBytes < prebuffer))) {
                        if (queue.isEmpty() && !buffering) buffering = true
                        ready.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    if (!running) return@withLock null
                    buffering = false
                    queue.removeFirst().also { queuedBytes -= it.size }
                } ?: break
                if (audio.write(packet, 0, packet.size, AudioTrack.WRITE_BLOCKING) < 0) break
            }
        } catch (e: Exception) {
            VeyroLog.w(TAG) { "PC sound stopped: ${e.javaClass.simpleName}" }
        } finally {
            runCatching { audio.stop() }
            audio.release()
            lock.withLock { if (track === audio) track = null }
        }
    }

    private fun stopLocked() {
        running = false
        ready.signalAll()
        queue.clear()
        queuedBytes = 0
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    public fun stop() {
        val t = lock.withLock {
            stopLocked()
            config = null
            thread.also { thread = null }
        }
        t?.join(500)
    }
}
