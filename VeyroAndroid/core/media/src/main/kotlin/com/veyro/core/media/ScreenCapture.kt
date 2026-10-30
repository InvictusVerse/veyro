package com.veyro.core.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.util.DisplayMetrics
import android.view.Display
import com.veyro.core.common.VeyroLog
import com.veyro.core.protocol.media.MediaSink
import com.veyro.core.protocol.media.StreamGeometry
import com.veyro.core.protocol.media.Vms1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the PC asked for (ADR-066: the PC owns quality); the phone applies it as it can. */
public data class CaptureSettings(
    val codec: String,
    val hw: Boolean,
    val maxLong: Int,
    val fps: Int,
    val bitrate: Int,
    val audio: Boolean,
)

/** The encoder actually in use, for `streamState`. */
public data class VideoInfo(val codec: String, val hw: Boolean, val width: Int, val height: Int, val fps: Int, val bitrate: Int)

/** Called on the capture thread. */
public interface CaptureListener {
    public fun onVideo(info: VideoInfo)
    public fun onAudio(playing: Boolean)
    public fun onPaused(paused: Boolean)
    public fun onThermal(status: Int)

    /** The capture ended by itself: `projection-ended`, `thermal`, `encoder` or `error`. */
    public fun onStopped(reason: String)
}

/**
 * One screen-sharing session's capture (ADR-066): the [MediaProjection] (one consent, one
 * virtual display, Android 14's rules), the video encoder behind it, and the phone's sound. It
 * rebuilds the encoder on rotation and on the PC's codec, size or frame-rate changes, always on
 * the same virtual display, and sends a new `VIDEO_CONFIG` before the new encoder's first
 * keyframe. Pauses while the PC's window is minimised or the phone's screen is off; stops at
 * thermal `CRITICAL`. Everything runs on one capture thread. Captures and encodes only: output
 * goes to [sink]; nothing here opens a socket or logs a frame.
 */
public class ScreenCapture(
    context: Context,
    private val projection: MediaProjection,
    private val catalog: EncoderCatalog,
    private val sink: MediaSink,
    private val listener: CaptureListener,
) {

    private companion object {
        const val TAG = "ScreenCapture"
        const val START_TIMEOUT_S = 10L
        const val RESIZE_DEBOUNCE_MS = 150L
        const val KEYFRAME_INTERVAL_MS = 500L
        const val MIN_LONG = 320
        const val MAX_REBUILDS_AFTER_ERROR = 2
    }

    private data class Screen(val width: Int, val height: Int, val dpi: Int)

    private val app = context.applicationContext
    private val thread = HandlerThread("VeyroCapture", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val stopped = AtomicBoolean(false)

    // Capture-thread state.
    private var settings: CaptureSettings? = null
    private var screen: Screen? = null
    private var display: VirtualDisplay? = null
    private var encoder: ScreenEncoder? = null
    private var generation = 0L
    private var pendingConfig: Vms1.VideoConfig? = null
    private var audio: PlaybackAudioCapture? = null
    private var pcPaused = false
    private var screenOff = false
    private var paused = false
    private var lastKeyRequest = Long.MIN_VALUE
    private var errors = 0
    private var receiverRegistered = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post { stopInternal("projection-ended") }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            scheduleResize()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) scheduleResize()
        }
    }

    private val resize = Runnable {
        if (stopped.get()) return@Runnable
        val now = readScreen() ?: return@Runnable
        if (now != screen) {
            screen = now
            if (!buildEncoder()) stopInternal("encoder")
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            applyPause()
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        handler.post {
            if (stopped.get()) return@post
            if (status >= PowerManager.THERMAL_STATUS_CRITICAL) stopInternal("thermal") else listener.onThermal(status)
        }
    }

    private val output = ScreenEncoder.Output { enc, ptsUs, data, key ->
        if (enc !== encoder || stopped.get()) return@Output
        pendingConfig?.let { config ->
            // The access unit after a config is a keyframe (7.5.3).
            if (!key) {
                requestKeyframe(force = false)
                return@Output
            }
            sink.videoConfig(config)
            pendingConfig = null
        }
        if (!sink.accessUnit(ptsUs, data, data.size, key)) requestKeyframe(force = false)
    }

    private val failure = ScreenEncoder.Failure { enc, error ->
        if (enc !== encoder || stopped.get()) return@Failure
        VeyroLog.w(TAG) { "Encoder failed: ${error.javaClass.simpleName}" }
        errors++
        if (errors > MAX_REBUILDS_AFTER_ERROR || !buildEncoder()) stopInternal("encoder")
    }

    /**
     * Starts capturing with [initial]. Blocks up to 10 s for the encoder; false when no encoder
     * could start (the session ends with `encoder`). Call off the main thread.
     */
    public fun start(initial: CaptureSettings): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        handler.post {
            try {
                projection.registerCallback(projectionCallback, handler)
                settings = initial
                screen = readScreen()
                ok = screen != null && buildEncoder()
                if (ok) {
                    app.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, handler)
                    val filter = IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_SCREEN_ON)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // System broadcasts still arrive; no other app can send to it.
                        app.registerReceiver(screenReceiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        app.registerReceiver(screenReceiver, filter, null, handler)
                    }
                    receiverRegistered = true
                    app.getSystemService(PowerManager::class.java)?.addThermalStatusListener({ handler.post(it) }, thermalListener)
                    if (initial.audio) setAudioNow(true)
                } else {
                    stopInternal(null)
                }
            } catch (e: Exception) {
                VeyroLog.w(TAG) { "Capture did not start: ${e.javaClass.simpleName}" }
                ok = false
                stopInternal(null)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(START_TIMEOUT_S, TimeUnit.SECONDS)) {
            stop()
            return false
        }
        return ok
    }

    /** The PC's new values (`streamConfig`). Codec, encoder, size or frame-rate changes rebuild the encoder. */
    public fun reconfigure(next: CaptureSettings) {
        handler.post {
            val current = settings ?: return@post
            if (stopped.get()) return@post
            settings = next
            val rebuild = next.codec != current.codec || next.hw != current.hw || next.maxLong != current.maxLong || next.fps != current.fps
            if (rebuild) {
                if (!buildEncoder()) stopInternal("encoder")
            } else if (next.bitrate != current.bitrate) {
                encoder?.let {
                    it.updateBitrate(next.bitrate)
                    listener.onVideo(VideoInfo(it.info.codec, it.info.hw, it.width, it.height, it.fps, next.bitrate))
                }
            }
            if (next.audio != current.audio) setAudioNow(next.audio)
        }
    }

    /** Turns the phone's sound on (needs the audio permission) or off. */
    public fun setAudio(on: Boolean) {
        handler.post {
            val current = settings ?: return@post
            settings = current.copy(audio = on)
            setAudioNow(on)
        }
    }

    public fun setPaused(paused: Boolean) {
        handler.post {
            pcPaused = paused
            applyPause()
        }
    }

    public fun requestKeyframe() {
        handler.post { requestKeyframe(force = true) }
    }

    /** Ends the capture and gives back the projection. Idempotent; [CaptureListener.onStopped] is not called. */
    public fun stop() {
        handler.post { stopInternal(null) }
    }

    private fun requestKeyframe(force: Boolean) {
        val now = System.nanoTime() / 1_000_000L
        if (!force && lastKeyRequest != Long.MIN_VALUE && now - lastKeyRequest < KEYFRAME_INTERVAL_MS) return
        lastKeyRequest = now
        encoder?.requestKeyframe()
    }

    private fun scheduleResize() {
        handler.removeCallbacks(resize)
        handler.postDelayed(resize, RESIZE_DEBOUNCE_MS)
    }

    private fun applyPause() {
        val now = pcPaused || screenOff
        if (now == paused || stopped.get()) return
        paused = now
        encoder?.setSuspended(now)
        listener.onPaused(now)
    }

    private fun setAudioNow(on: Boolean) {
        if (stopped.get()) return
        if (on) {
            if (audio != null) return
            val capture = PlaybackAudioCapture(app, projection, sink)
            if (capture.start()) {
                audio = capture
                listener.onAudio(true)
            } else {
                listener.onAudio(false)
            }
        } else {
            audio?.stop()
            audio = null
            listener.onAudio(false)
        }
    }

    /** A new encoder for the current screen and settings, swapped onto the one virtual display. */
    private fun buildEncoder(): Boolean {
        val s = settings ?: return false
        val shown = screen ?: return false
        val fit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) StreamGeometry.Fit.FIT else StreamGeometry.Fit.STRETCH
        val portrait = shown.height > shown.width
        for (info in catalog.candidates(s.codec, s.hw)) {
            // The encoder's limit for the long side in this orientation, then smaller on refusal.
            var maxLong = minOf(s.maxLong, if (portrait) info.maxH else info.maxW)
            repeat(3) {
                val geometry = StreamGeometry.compute(shown.width, shown.height, maxLong, info.maxW, info.maxH, fit) ?: return@repeat
                val fps = minOf(s.fps, info.fps).coerceAtLeast(1)
                val bitrate = if (info.hw) s.bitrate else minOf(s.bitrate, 4_000_000)
                val built = ScreenEncoder.create(info, geometry.width, geometry.height, fps, bitrate, handler, output, failure)
                if (built != null && swapIn(built, geometry, shown.dpi)) return true
                maxLong = (maxLong * 3 / 4).coerceAtLeast(MIN_LONG)
            }
        }
        return false
    }

    private fun swapIn(next: ScreenEncoder, geometry: StreamGeometry.Result, dpi: Int): Boolean {
        try {
            next.start()
            val current = display
            if (current == null) {
                display = projection.createVirtualDisplay(
                    "Veyro mirror",
                    geometry.width,
                    geometry.height,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    next.surface,
                    null,
                    handler,
                ) ?: throw IllegalStateException("no virtual display")
            } else {
                current.resize(geometry.width, geometry.height, dpi)
                current.surface = next.surface
            }
        } catch (e: Exception) {
            VeyroLog.w(TAG) { "Encoder not attached: ${e.javaClass.simpleName}" }
            next.release()
            return false
        }
        val old = encoder
        encoder = next
        old?.release()
        generation++
        pendingConfig = Vms1.VideoConfig(
            codec = if (next.info.codec == EncoderCatalog.CODEC_H265) Vms1.VIDEO_CODEC_H265 else Vms1.VIDEO_CODEC_H264,
            width = geometry.width,
            height = geometry.height,
            visX = geometry.visX,
            visY = geometry.visY,
            visW = geometry.visW,
            visH = geometry.visH,
            rotation = 0,
            mirror = false,
            generation = generation,
        )
        if (paused) next.setSuspended(true)
        next.requestKeyframe()
        VeyroLog.i(TAG) { "Encoding ${next.info.codec} ${if (next.info.hw) "hw" else "sw"} ${geometry.width}x${geometry.height} at ${next.fps} fps" }
        listener.onVideo(VideoInfo(next.info.codec, next.info.hw, geometry.width, geometry.height, next.fps, next.bitrate))
        return true
    }

    @Suppress("DEPRECATION") // getRealMetrics: the only size that follows rotation without a visual context.
    private fun readScreen(): Screen? {
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        if (metrics.widthPixels < 16 || metrics.heightPixels < 16) return null
        return Screen(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi.coerceAtLeast(1))
    }

    private fun stopInternal(reason: String?) {
        if (!stopped.compareAndSet(false, true)) return
        handler.removeCallbacks(resize)
        runCatching { app.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener) }
        if (receiverRegistered) runCatching { app.unregisterReceiver(screenReceiver) }
        runCatching { app.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(thermalListener) }
        audio?.stop()
        audio = null
        encoder?.release()
        encoder = null
        runCatching { display?.release() }
        display = null
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
        if (reason != null) {
            VeyroLog.i(TAG) { "Capture ended: $reason" }
            listener.onStopped(reason)
        }
        thread.quitSafely()
    }
}
