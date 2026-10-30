package com.veyro.core.protocol.media

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Where a capture hands its output to a media socket (ADR-066). The sink takes ownership of
 * every array it is given. Calls never block: a slow network drops, it does not stall capture.
 */
public interface MediaSink {
    /** Sent before the first access unit and before the first one after any change; never dropped. */
    public fun videoConfig(config: Vms1.VideoConfig)

    /**
     * One encoded frame, Annex B, a keyframe with its parameter sets in front. Returns false when
     * it was dropped (the network is behind): the capture should ask its encoder for a keyframe.
     */
    public fun accessUnit(ptsUs: Long, data: ByteArray, length: Int, key: Boolean): Boolean

    public fun audioConfig(config: Vms1.AudioConfig)

    /** Whole s16le sample frames, after [audioConfig]. */
    public fun audio(ptsUs: Long, samples: ByteArray, length: Int)
}

/**
 * The phone's send queue (04-PROTOCOL.md 7.5.6). Video: at most [maxUnits] access units or
 * [maxBytes] waiting, not counting the one being written. A new unit that does not fit is
 * dropped, and so is every later non-key unit until a keyframe is queued; the next unit sent
 * then carries `DISCONTINUITY`. Configs are never dropped. Audio queues apart, at most
 * [maxAudioUs] of it, oldest dropped. One video writer and one audio writer take from it, so
 * audio goes out between video fragments. Thread-safe; pure, so it is JVM-tested.
 */
public class MediaSendQueue(
    private val maxUnits: Int = 3,
    private val maxBytes: Int = 2 * 1024 * 1024,
    private val maxAudioUs: Long = 200_000L,
) {

    public sealed interface VideoItem {
        public class Config(public val config: Vms1.VideoConfig) : VideoItem
        public class Frame(
            public val ptsUs: Long,
            public val data: ByteArray,
            public val length: Int,
            public val key: Boolean,
            public val discontinuity: Boolean,
        ) : VideoItem
    }

    public sealed interface AudioItem {
        public class Config(public val config: Vms1.AudioConfig) : AudioItem
        public class Samples(public val ptsUs: Long, public val data: ByteArray, public val length: Int) : AudioItem
    }

    private val lock = ReentrantLock()
    private val videoReady = lock.newCondition()
    private val audioReady = lock.newCondition()
    private val video = ArrayDeque<VideoItem>()
    private val audio = ArrayDeque<AudioItem>()
    private var queuedUnits = 0
    private var queuedBytes = 0L
    private var waitingForKey = false
    private var discontinuity = false
    private var audioFrameBytes = 0
    private var audioRate = 0
    private var queuedAudioBytes = 0L
    private var closed = false

    /** Access units dropped so far. */
    @Volatile
    public var droppedUnits: Long = 0L
        private set

    public fun offerConfig(config: Vms1.VideoConfig): Unit = lock.withLock {
        if (closed) return
        video.addLast(VideoItem.Config(config))
        videoReady.signal()
    }

    /** False when [data] was dropped. */
    public fun offerUnit(ptsUs: Long, data: ByteArray, length: Int, key: Boolean): Boolean = lock.withLock {
        if (closed) return false
        require(length in 0..data.size) { "access unit bounds" }
        val fits = queuedUnits == 0 || (queuedUnits < maxUnits && queuedBytes + length <= maxBytes)
        if (!fits || (waitingForKey && !key)) {
            droppedUnits++
            waitingForKey = true
            discontinuity = true
            return false
        }
        waitingForKey = false
        video.addLast(VideoItem.Frame(ptsUs, data, length, key, discontinuity))
        discontinuity = false
        queuedUnits++
        queuedBytes += length
        videoReady.signal()
        true
    }

    public fun offerAudioConfig(config: Vms1.AudioConfig): Unit = lock.withLock {
        if (closed) return
        audioFrameBytes = config.frameBytes
        audioRate = config.rate
        audio.addLast(AudioItem.Config(config))
        audioReady.signal()
    }

    public fun offerAudio(ptsUs: Long, data: ByteArray, length: Int): Unit = lock.withLock {
        if (closed || audioRate <= 0) return
        audio.addLast(AudioItem.Samples(ptsUs, data, length))
        queuedAudioBytes += length
        val limit = maxAudioUs * audioRate / 1_000_000L * audioFrameBytes
        while (queuedAudioBytes > limit) {
            val oldest = audio.firstOrNull { it is AudioItem.Samples } as? AudioItem.Samples ?: break
            audio.remove(oldest)
            queuedAudioBytes -= oldest.length
        }
        audioReady.signal()
    }

    /** The next video item, or null after [timeoutMs] or once closed. */
    public fun takeVideo(timeoutMs: Long): VideoItem? = lock.withLock {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (video.isEmpty()) {
            if (closed || nanos <= 0L) return null
            nanos = videoReady.awaitNanos(nanos)
        }
        if (closed) return null
        val item = video.removeFirst()
        if (item is VideoItem.Frame) {
            queuedUnits--
            queuedBytes -= item.length
        }
        item
    }

    /** The next audio item, or null after [timeoutMs] or once closed. */
    public fun takeAudio(timeoutMs: Long): AudioItem? = lock.withLock {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (audio.isEmpty()) {
            if (closed || nanos <= 0L) return null
            nanos = audioReady.awaitNanos(nanos)
        }
        if (closed) return null
        val item = audio.removeFirst()
        if (item is AudioItem.Samples) queuedAudioBytes -= item.length
        item
    }

    /** Drops everything and wakes the writers, which then get null. */
    public fun close(): Unit = lock.withLock {
        closed = true
        video.clear()
        audio.clear()
        queuedUnits = 0
        queuedBytes = 0
        queuedAudioBytes = 0
        videoReady.signalAll()
        audioReady.signalAll()
    }
}
