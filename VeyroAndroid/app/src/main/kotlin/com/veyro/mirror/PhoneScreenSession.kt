package com.veyro.mirror

import android.content.Context
import android.media.projection.MediaProjection
import com.veyro.core.common.AppDispatchers
import com.veyro.core.companion.ScreenSession
import com.veyro.core.companion.ScreenSessionState
import com.veyro.core.link.StreamConfig
import com.veyro.core.link.StreamStart
import com.veyro.core.link.media.Vms1Uplink
import com.veyro.core.media.CaptureListener
import com.veyro.core.media.CaptureSettings
import com.veyro.core.media.EncoderCatalog
import com.veyro.core.media.ScreenCapture
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
 * One screen-sharing session on the phone (ADR-066): the capture of `:core:media` feeding the
 * media socket of `:core:link`, applying what the PC asks for. The phone's sound is captured
 * only while the PC wants it and Android's audio permission is granted; otherwise its state is
 * reported so the PC can show "denied" or "blocked" with Ask again.
 */
internal class PhoneScreenSession(
    context: Context,
    projection: MediaProjection,
    catalog: EncoderCatalog,
    private val uplink: Vms1Uplink,
    start: StreamStart,
    private val audioState: AudioPermissionState,
    private val owner: ScreenShareCoordinator,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
) : ScreenSession, CaptureListener {

    private val ended = AtomicBoolean(false)
    private val capture = ScreenCapture(context, projection, catalog, uplink, this)

    @Volatile private var audioWanted = start.audio
    @Volatile private var settings = CaptureSettings(
        codec = start.codec,
        hw = start.hw,
        maxLong = start.maxLong,
        fps = start.fps,
        bitrate = start.bitrate,
        audio = start.audio && audioState.granted(),
    )

    private val _state = MutableStateFlow(ScreenSessionState(audioPermission = audioState.current()))
    override val state: StateFlow<ScreenSessionState> = _state.asStateFlow()

    /** The PC asked for sound and it is not playing. */
    val wantsAudio: Boolean get() = audioWanted && !_state.value.audio && !ended.get()

    /** Starts capturing; false when no encoder works. */
    suspend fun begin(): Boolean = withContext(dispatchers.io) {
        if (!capture.start(settings)) return@withContext false
        _state.update { it.copy(streaming = true) }
        scope.launch { stop(uplink.closed.await()) }
        true
    }

    override fun configure(config: StreamConfig) {
        if (ended.get()) return
        var next = settings.copy(
            codec = config.codec ?: settings.codec,
            hw = config.hw ?: settings.hw,
            maxLong = config.maxLong ?: settings.maxLong,
            fps = config.fps ?: settings.fps,
            bitrate = config.bitrate ?: settings.bitrate,
        )
        config.audio?.let { on ->
            audioWanted = on
            next = next.copy(audio = on && audioState.granted())
            // Sound back on but not allowed: ask the phone's user again (a notification).
            if (on && !audioState.granted()) owner.askAudio(this)
            if (on && audioState.granted()) owner.ensureAudioService()
        }
        settings = next
        capture.reconfigure(next)
        config.paused?.let { capture.setPaused(it) }
        _state.update { it.copy(audioPermission = audioState.current()) }
    }

    override fun requestKeyframe() {
        capture.requestKeyframe()
    }

    override fun askAudio() {
        if (!ended.get()) owner.askAudio(this)
    }

    /** The audio permission was answered (dialog or App info). */
    fun onAudioAnswered() {
        if (ended.get()) return
        if (audioWanted && audioState.granted() && !_state.value.audio) {
            settings = settings.copy(audio = true)
            capture.setAudio(true)
        }
        _state.update { it.copy(audioPermission = audioState.current()) }
    }

    override fun stop(reason: String) {
        if (!ended.compareAndSet(false, true)) return
        capture.stop()
        // A socket that already failed gets no CLOSE.
        uplink.close(reason.takeUnless { it == "network" })
        _state.update { it.copy(stopped = reason) }
        owner.onSessionEnded(this)
    }

    override fun onVideo(info: VideoInfo) {
        _state.update { it.copy(codec = info.codec, hw = info.hw, w = info.width, h = info.height, fps = info.fps, bitrate = info.bitrate) }
    }

    override fun onAudio(playing: Boolean) {
        _state.update { it.copy(audio = playing, audioPermission = audioState.current()) }
        owner.refreshNotice()
    }

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
