package com.veyro.core.media

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.util.Range
import android.view.Display
import android.view.Surface
import com.veyro.core.common.VeyroLog
import com.veyro.core.protocol.media.MediaSink
import com.veyro.core.protocol.media.Vms1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the PC asked for (ADR-067: the PC owns quality); [width] x [height] is a size from the caps. */
public data class CameraSettings(
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val codec: String,
    val hw: Boolean,
)

/**
 * One camera session's capture (ADR-067): Camera2 straight into the encoder's input surface, no
 * GPU pass. The camera's sensor orientation and the phone's rotation go to the PC as the
 * `VIDEO_CONFIG` rotation, which the PC applies; a turn of the phone sends a new config and a
 * keyframe on the same encoder. Lens, size or frame-rate changes from the PC rebuild the encoder
 * and the capture session; no new consent is needed. Everything runs on one capture thread.
 * Captures and encodes only; frames go to [sink] and are never logged.
 */
public class CameraCapture(
    context: Context,
    private val encoders: EncoderCatalog,
    private val cameras: CameraCatalog,
    private val sink: MediaSink,
    private val listener: CaptureListener,
) {

    private companion object {
        const val TAG = "CameraCapture"
        const val START_TIMEOUT_S = 10L
        const val KEYFRAME_INTERVAL_MS = 500L
    }

    private val app = context.applicationContext
    private val manager = app.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("VeyroCamera", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val stopped = AtomicBoolean(false)

    // Capture-thread state.
    private var settings: CameraSettings? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: ScreenEncoder? = null
    private var generation = 0L
    private var pendingConfig: Vms1.VideoConfig? = null
    private var rotation = 0
    private var paused = false
    private var lastKeyRequest = Long.MIN_VALUE
    private var onReady: ((Boolean) -> Unit)? = null

    private val output = ScreenEncoder.Output { enc, ptsUs, data, key ->
        if (enc !== encoder || stopped.get()) return@Output
        pendingConfig?.let { config ->
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
        stopInternal("encoder")
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || stopped.get()) return
            val next = currentRotation()
            if (next != rotation) {
                rotation = next
                encoder?.let { queueConfig(it) }
            }
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        handler.post {
            if (stopped.get()) return@post
            if (status >= PowerManager.THERMAL_STATUS_CRITICAL) stopInternal("thermal") else listener.onThermal(status)
        }
    }

    /** Opens the camera and starts encoding. Blocks up to 10 s; false when it could not start. Call off the main thread. */
    public fun start(initial: CameraSettings): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        handler.post {
            settings = initial
            onReady = { success ->
                ok = success
                latch.countDown()
            }
            app.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, handler)
            app.getSystemService(PowerManager::class.java)?.addThermalStatusListener(executor, thermalListener)
            rebuild(cameraChanged = true)
        }
        if (!latch.await(START_TIMEOUT_S, TimeUnit.SECONDS) || !ok) {
            stop()
            return false
        }
        return true
    }

    /** The PC's new values (`streamConfig`). */
    public fun reconfigure(next: CameraSettings) {
        handler.post {
            val current = settings ?: return@post
            if (stopped.get()) return@post
            settings = next
            val cameraChanged = next.cameraId != current.cameraId
            val rebuild = cameraChanged || next.width != current.width || next.height != current.height ||
                next.fps != current.fps || next.codec != current.codec || next.hw != current.hw
            when {
                rebuild -> rebuild(cameraChanged)
                next.bitrate != current.bitrate -> encoder?.let {
                    it.updateBitrate(next.bitrate)
                    report(it)
                }
            }
        }
    }

    public fun setPaused(paused: Boolean) {
        handler.post {
            if (this.paused == paused || stopped.get()) return@post
            this.paused = paused
            if (paused) runCatching { session?.stopRepeating() } else startRepeating()
            encoder?.setSuspended(paused)
            listener.onPaused(paused)
        }
    }

    public fun requestKeyframe() {
        handler.post { requestKeyframe(force = true) }
    }

    /** Closes the camera. Idempotent; [CaptureListener.onStopped] is not called. */
    public fun stop() {
        handler.post { stopInternal(null) }
    }

    private fun requestKeyframe(force: Boolean) {
        val now = System.nanoTime() / 1_000_000L
        if (!force && lastKeyRequest != Long.MIN_VALUE && now - lastKeyRequest < KEYFRAME_INTERVAL_MS) return
        lastKeyRequest = now
        encoder?.requestKeyframe()
    }

    /** A new encoder for the current settings; the camera is (re)opened when [cameraChanged]. */
    private fun rebuild(cameraChanged: Boolean) {
        val s = settings ?: return finishStart(false)
        runCatching { session?.close() }
        session = null
        if (cameraChanged) {
            runCatching { device?.close() }
            device = null
        }
        val next = createEncoder(s) ?: run {
            stopInternal("encoder")
            return finishStart(false)
        }
        encoder?.release()
        encoder = next
        rotation = currentRotation()
        queueConfig(next)
        val open = device
        if (open != null) createSession(open, next) else openCamera(s.cameraId, next)
    }

    private fun createEncoder(s: CameraSettings): ScreenEncoder? {
        for (info in encoders.candidates(s.codec, s.hw)) {
            val fits = (info.maxW >= s.width && info.maxH >= s.height) || (info.maxW >= s.height && info.maxH >= s.width)
            if (!fits) continue
            val fps = minOf(s.fps, info.fps).coerceAtLeast(1)
            val bitrate = if (info.hw) s.bitrate else minOf(s.bitrate, 4_000_000)
            ScreenEncoder.create(info, s.width, s.height, fps, bitrate, handler, output, failure)?.let {
                it.start()
                return it
            }
        }
        return null
    }

    @SuppressLint("MissingPermission") // the consent activity asked for CAMERA; a SecurityException ends the session
    private fun openCamera(id: String, @Suppress("UNUSED_PARAMETER") target: ScreenEncoder) {
        val cameraManager = manager ?: return failStart("error")
        try {
            cameraManager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (stopped.get()) {
                        camera.close()
                        return
                    }
                    device = camera
                    val current = encoder
                    if (current != null) createSession(camera, current) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    // Another app with higher priority took the camera.
                    if (device === camera || device == null) failStart("busy")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    val reason = when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE, CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "busy"
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "permission"
                        else -> "error"
                    }
                    VeyroLog.w(TAG) { "Camera error $error" }
                    if (device === camera || device == null) failStart(reason)
                }
            })
        } catch (e: SecurityException) {
            failStart("permission")
        } catch (e: CameraAccessException) {
            failStart(if (e.reason == CameraAccessException.CAMERA_DISABLED) "permission" else "busy")
        } catch (e: IllegalArgumentException) {
            failStart("error")
        }
    }

    private fun createSession(camera: CameraDevice, target: ScreenEncoder) {
        try {
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(target.surface)),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(created: CameraCaptureSession) {
                        if (stopped.get() || target !== encoder) {
                            created.close()
                            return
                        }
                        session = created
                        if (!paused) startRepeating()
                        report(target)
                        VeyroLog.i(TAG) { "Camera streaming ${target.width}x${target.height} at ${target.fps} fps" }
                        finishStart(true)
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        VeyroLog.w(TAG) { "Camera session not configured" }
                        failStart("unsupported")
                    }
                },
            )
            camera.createCaptureSession(config)
        } catch (e: CameraAccessException) {
            failStart("busy")
        } catch (e: IllegalArgumentException) {
            failStart("unsupported")
        } catch (e: IllegalStateException) {
            failStart("error")
        }
    }

    private fun startRepeating() {
        val camera = device ?: return
        val active = session ?: return
        val target = encoder ?: return
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(target.surface)
                fpsRange(camera.id, target.fps)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                // Stabilisation adds delay; a webcam stands still.
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }.build()
            active.setRepeatingRequest(request, null, handler)
        } catch (e: Exception) {
            VeyroLog.w(TAG) { "Camera request refused: ${e.javaClass.simpleName}" }
            failStart("error")
        }
    }

    /** The fixed range at [fps] if the camera has one, else the narrowest that reaches it. */
    private fun fpsRange(id: String, fps: Int): Range<Int>? {
        val ranges = runCatching { manager?.getCameraCharacteristics(id)?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) }
            .getOrNull() ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper >= fps }.minByOrNull { it.upper - it.lower + (it.upper - fps) * 100 }
            ?: ranges.maxByOrNull { it.upper }
    }

    /** Sensor orientation and the phone's rotation as the clockwise turn the PC applies. */
    private fun currentRotation(): Int {
        val s = settings ?: return 0
        val info = cameras.find(s.cameraId) ?: return 0
        val display = when (app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (info.facing == "front") (info.sensorOrientation + display) % 360 else (info.sensorOrientation - display + 360) % 360
    }

    private fun queueConfig(target: ScreenEncoder) {
        generation++
        pendingConfig = Vms1.VideoConfig(
            codec = if (target.info.codec == EncoderCatalog.CODEC_H265) Vms1.VIDEO_CODEC_H265 else Vms1.VIDEO_CODEC_H264,
            width = target.width,
            height = target.height,
            visX = 0,
            visY = 0,
            visW = target.width,
            visH = target.height,
            rotation = rotation,
            mirror = false,
            generation = generation,
        )
        requestKeyframe(force = true)
    }

    private fun report(target: ScreenEncoder) {
        listener.onVideo(VideoInfo(target.info.codec, target.info.hw, target.width, target.height, target.fps, target.bitrate))
    }

    private fun finishStart(ok: Boolean) {
        val callback = onReady ?: return
        onReady = null
        callback(ok)
    }

    /** A failure: during start it fails the start, later it ends the session. */
    private fun failStart(reason: String) {
        if (onReady != null) {
            finishStart(false)
            stopInternal(null)
        } else {
            stopInternal(reason)
        }
    }

    private fun stopInternal(reason: String?) {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { app.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener) }
        runCatching { app.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(thermalListener) }
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        encoder?.release()
        encoder = null
        finishStart(false)
        if (reason != null) {
            VeyroLog.i(TAG) { "Camera ended: $reason" }
            listener.onStopped(reason)
        }
        thread.quitSafely()
    }
}
