package com.veyro.mirror

import android.content.Context
import com.veyro.core.common.AppDispatchers
import com.veyro.core.companion.ScreenSession
import com.veyro.core.companion.ScreenSessionState
import com.veyro.core.link.StreamConfig
import com.veyro.core.link.StreamStart
import com.veyro.core.link.media.Vms1Uplink
import com.veyro.core.media.CameraCapture
import com.veyro.core.media.CameraCatalog
import com.veyro.core.media.CameraSettings
import com.veyro.core.media.CaptureListener
import com.veyro.core.media.EncoderCatalog
import com.veyro.core.media.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One PC's use of the phone's camera (ADR-067): [CameraCapture] feeding the media socket,
 * applying the PC's lens and size choices. A size is applied only when the chosen camera lists
 * it, so a PC cannot ask the camera for something it never offered. No sound.
 */
internal class PhoneCameraSession(
    context: Context,
    encoders: EncoderCatalog,
    private val cameras: CameraCatalog,
    private val uplink: Vms1Uplink,
    start: StreamStart,
    private val owner: ScreenShareCoordinator,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
) : ScreenSession, CaptureListener {

    private val ended = AtomicBoolean(false)
    private val capture = CameraCapture(context, encoders, cameras, uplink, this)

    @Volatile private var settings = CameraSettings(
        cameraId = start.camera.orEmpty(),
        width = start.w ?: 1280,
        height = start.h ?: 720,
        fps = start.fps,
        bitrate = start.bitrate,
        codec = start.codec,
        hw = start.hw,
    )

    private val _state = MutableStateFlow(ScreenSessionState(camera = settings.cameraId))
    override val state: StateFlow<ScreenSessionState> = _state.asStateFlow()

    /** The camera and size the PC asked for exist in the caps. */
    fun isOffered(id: String, w: Int, h: Int): Boolean =
        cameras.find(id)?.sizes?.any { it.w == w && it.h == h } == true

    val pcFacing: String? get() = cameras.find(settings.cameraId)?.facing

    suspend fun begin(): Boolean = withContext(dispatchers.io) {
        if (!isOffered(settings.cameraId, settings.width, settings.height)) return@withContext false
        if (!capture.start(settings)) return@withContext false
        _state.update { it.copy(streaming = true) }
        scope.launch { stop(uplink.closed.await()) }
        true
    }

    override fun configure(config: StreamConfig) {
        if (ended.get()) return
        val id = config.camera ?: settings.cameraId
        var w = config.w ?: settings.width
        var h = config.h ?: settings.height
        if (!isOffered(id, w, h)) {
            // Another lens without this size: its nearest size at or below, else its smallest.
            val sizes = cameras.find(id)?.sizes ?: return
            val pick = sizes.filter { it.w.toLong() * it.h <= w.toLong() * h }.maxByOrNull { it.w.toLong() * it.h } ?: sizes.minByOrNull { it.w.toLong() * it.h } ?: return
            w = pick.w
            h = pick.h
        }
        settings = settings.copy(
            cameraId = id,
            width = w,
            height = h,
            fps = config.fps ?: settings.fps,
            bitrate = config.bitrate ?: settings.bitrate,
            codec = config.codec ?: settings.codec,
            hw = config.hw ?: settings.hw,
            torch = config.torch ?: settings.torch,
        )
        capture.reconfigure(settings)
        config.paused?.let { capture.setPaused(it) }
        _state.update { it.copy(camera = id) }
        owner.refreshCameraNotice()
    }

    override fun requestKeyframe() {
        capture.requestKeyframe()
    }

    override fun askAudio() = Unit

    override fun stop(reason: String) {
        if (!ended.compareAndSet(false, true)) return
        capture.stop()
        uplink.close(reason.takeUnless { it == "network" })
        _state.update { it.copy(stopped = reason) }
        owner.onCameraSessionEnded(this)
    }

    override fun onVideo(info: VideoInfo) {
        _state.update { it.copy(codec = info.codec, hw = info.hw, w = info.width, h = info.height, fps = info.fps, bitrate = info.bitrate) }
    }

    override fun onAudio(playing: Boolean) = Unit

    override fun onPaused(paused: Boolean) {
        _state.update { it.copy(paused = paused) }
    }

    override fun onThermal(status: Int) {
        _state.update { it.copy(thermal = status) }
    }

    override fun onStopped(reason: String) {
        stop(reason)
    }
}
