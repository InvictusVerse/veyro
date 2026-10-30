package com.veyro.core.companion

import com.veyro.core.common.VeyroLog
import com.veyro.core.link.StreamAskAudio
import com.veyro.core.link.StreamCaps
import com.veyro.core.link.StreamConfig
import com.veyro.core.link.StreamKeyframe
import com.veyro.core.link.StreamRules
import com.veyro.core.link.StreamStart
import com.veyro.core.link.StreamState
import com.veyro.core.link.StreamStop
import com.veyro.core.model.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** What the phone can share right now, for `streamCaps` (ADR-066). Built by the platform. */
public interface ScreenShareHost {

    /**
     * This phone's `streamCaps`; [allowed] false gives the empty screen part the PC shows as
     * "off", [cameraAllowed] false leaves out the cameras (ADR-067).
     */
    public suspend fun caps(allowed: Boolean, cameraAllowed: Boolean = false): StreamCaps

    /**
     * ADR-067: asks the phone's user to let [pcName] use the camera (a notification, then the
     * camera permission if needed). Suspends until answered; null when declined.
     */
    public suspend fun requestCameraConsent(sid: String, pcName: String): ScreenGrant? = null

    /** Fires when the caps may have changed: rotation, an audio-permission answer. */
    public val capsChanges: Flow<Unit>

    /**
     * Asks the phone's user to share the screen with [pcName]: a notification, then Android's
     * own dialog (and first its audio permission when [audio] and not decided yet). Suspends
     * until answered; null when declined. Cancelling withdraws the request.
     */
    public suspend fun requestConsent(sid: String, pcName: String, audio: Boolean): ScreenGrant?
}

/** A consent the user just gave, single-use (Android 14+). [start] it once or [release] it. */
public interface ScreenGrant {
    /**
     * Dials the PC's media socket on [link]'s keys and starts capturing as [start] says. Throws
     * [IOException] when the socket cannot be opened, anything else when capture cannot start.
     */
    public suspend fun start(link: CompanionLink, start: StreamStart): ScreenSession

    public fun release()
}

/** What a running capture reports; the bridge turns it into `streamState` / `streamStop`. */
public data class ScreenSessionState(
    /** The media socket is authenticated and frames flow. */
    val streaming: Boolean = false,
    /** The PC minimised its window or the phone's screen is off. */
    val paused: Boolean = false,
    val codec: String? = null,
    val w: Int? = null,
    val h: Int? = null,
    val fps: Int? = null,
    val bitrate: Int? = null,
    val hw: Boolean? = null,
    /** Phone sound is being sent. */
    val audio: Boolean = false,
    /** StreamRules.AUDIO_*; reported while the PC asked for sound and it is not playing. */
    val audioPermission: String? = null,
    val thermal: Int? = null,
    /** Camera sessions: the camera in use. */
    val camera: String? = null,
    /** Set once: why the capture ended (a 6.9 reason). */
    val stopped: String? = null,
)

/** One running capture. Every call is idempotent after [stop]. */
public interface ScreenSession {
    public val state: StateFlow<ScreenSessionState>

    /** The PC's new limits (already validated). */
    public fun configure(config: StreamConfig)

    /** Already rate-limited to one per 500 ms. */
    public fun requestKeyframe()

    /** The PC's "Ask again" for the sound, already rate-limited to one per 3 s. */
    public fun askAudio()

    public fun stop(reason: String)
}

/**
 * The phone's side of screen mirroring control (ADR-066, 04-PROTOCOL.md 6.9). Per link that
 * advertises [Capability.SCREEN_VIEW]:
 *  - `streamCaps` after the link appears and whenever the switch, rotation or audio permission
 *    changes ([allowed] off sends the empty form);
 *  - `streamStart` is validated and answered with `asking`, then the phone's user is asked
 *    ([CONSENT_TIMEOUT_MS] at most): declined or unanswered ends in `streamStop`; allowed, the
 *    media socket is dialled and `streamState` follows the capture until it ends;
 *  - one session at a time across every PC (`busy`); the switch turned off ends it (`disabled`);
 *  - `streamConfig`, `streamKeyframe` (one per 500 ms), `streamAskAudio` (one per 3 s) and
 *    `streamStop` act only on the live session of that PC with that `sid`.
 * The PC chooses every quality value; the phone only applies them. Nothing here logs a name.
 */
public class ScreenShareBridge(
    /** Every live paired session (multiple PCs). */
    private val links: Flow<List<CompanionLink>>,
    /** "Let PCs mirror this screen". */
    private val allowed: Flow<Boolean>,
    private val host: ScreenShareHost,
    /** The PC's shown name, for the request notification. */
    private val pcName: suspend (CompanionLink) -> String,
    private val scope: CoroutineScope,
    /** Monotonic milliseconds, for the rate limits. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    /** ADR-067: "Let PCs use this phone's camera". */
    private val cameraAllowed: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
) {

    public companion object {
        private const val TAG = "ScreenShare"

        /** Android's consent dialog plus the time to find the phone (04-PROTOCOL 6.9: 60 s). */
        public const val CONSENT_TIMEOUT_MS: Long = 60_000L
        public const val KEYFRAME_INTERVAL_MS: Long = 500L
    }

    /** The one session across every PC, from `streamStart` to the end. */
    private inner class Active(val link: CompanionLink, val sid: String, val audioRequested: Boolean, val source: String) {
        @Volatile var session: ScreenSession? = null
        @Volatile var job: Job? = null
        @Volatile var lastKeyframe: Long = Long.MIN_VALUE
        @Volatile var lastAskAudio: Long = Long.MIN_VALUE

        /** The PC ended it (streamStop), so nothing is sent back. */
        @Volatile var endedByPc: Boolean = false
    }

    private val lock = Any()
    private var active: Active? = null
    private var job: Job? = null

    /** Starts observing. Idempotent; returns the running job. */
    @Synchronized
    public fun start(): Job = job?.takeIf { it.isActive } ?: scope.launch { run() }.also { job = it }

    private suspend fun run() {
        links.forEachLink { link ->
            link.peerCapabilities
                .map { Capability.SCREEN_VIEW in it }
                .distinctUntilChanged()
                .collectLatest { capable -> if (capable) serve(link) }
        }
    }

    private suspend fun serve(link: CompanionLink) {
        try {
            coroutineScope {
                val on = MutableStateFlow(allowed.first())
                val cameraOn = MutableStateFlow(cameraAllowed.first())
                launch { allowed.collect { on.value = it } }
                launch { cameraAllowed.collect { cameraOn.value = it } }
                launch {
                    // First caps at once, then after every change that may alter them.
                    merge(kotlinx.coroutines.flow.combine(on, cameraOn) { _, _ -> }, host.capsChanges).conflate().collect {
                        link.send(host.caps(on.value, cameraOn.value))
                    }
                }
                launch {
                    on.collect { enabled -> if (!enabled) end(link, sid = null, reason = "disabled", tellPc = true, source = StreamRules.SOURCE_SCREEN) }
                }
                launch {
                    cameraOn.collect { enabled -> if (!enabled) end(link, sid = null, reason = "disabled", tellPc = true, source = StreamRules.SOURCE_CAMERA) }
                }
                link.incoming.collect { message ->
                    when (message) {
                        is StreamStart -> onStart(link, message, if (message.source == StreamRules.SOURCE_CAMERA) cameraOn.value else on.value)
                        is StreamConfig -> if (StreamRules.isValid(message)) sessionFor(link, message.sid)?.session?.configure(message)
                        is StreamKeyframe -> if (StreamRules.isValid(message)) onKeyframe(link, message.sid)
                        is StreamAskAudio -> if (StreamRules.isValid(message)) onAskAudio(link, message.sid)
                        is StreamStop -> if (StreamRules.isValid(message)) end(link, message.sid, "user", tellPc = false)
                        else -> Unit
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "Screen share link ended: ${t.javaClass.simpleName}" }
        } finally {
            // The PC's session closed or stopped advertising: its capture goes with it.
            end(link, sid = null, reason = "session-closed", tellPc = false)
        }
    }

    private fun sessionFor(link: CompanionLink, sid: String): Active? = synchronized(lock) {
        active?.takeIf { it.link == link && it.sid == sid }
    }

    private fun onKeyframe(link: CompanionLink, sid: String) {
        val current = sessionFor(link, sid) ?: return
        val now = clock()
        if (current.lastKeyframe != Long.MIN_VALUE && now - current.lastKeyframe < KEYFRAME_INTERVAL_MS) return
        current.lastKeyframe = now
        current.session?.requestKeyframe()
    }

    private fun onAskAudio(link: CompanionLink, sid: String) {
        val current = sessionFor(link, sid) ?: return
        val session = current.session ?: return
        if (!current.audioRequested || session.state.value.audio) return
        val now = clock()
        if (current.lastAskAudio != Long.MIN_VALUE && now - current.lastAskAudio < StreamRules.ASK_AUDIO_INTERVAL_MS) return
        current.lastAskAudio = now
        session.askAudio()
    }

    private suspend fun onStart(link: CompanionLink, start: StreamStart, enabled: Boolean) {
        if (!StreamRules.isValid(start)) {
            VeyroLog.w(TAG) { "streamStart dropped: a field broke a rule" }
            return
        }
        if (!enabled) {
            send(link, StreamStop(start.sid, "disabled"))
            return
        }
        val claim = Active(link, start.sid, start.audio, start.source)
        val busy = synchronized(lock) {
            if (active != null) true else { active = claim; false }
        }
        if (busy) {
            send(link, StreamStop(start.sid, "busy"))
            return
        }
        claim.job = scope.launch { runSession(claim, start) }
    }

    private suspend fun runSession(claim: Active, start: StreamStart) {
        val link = claim.link
        val sid = start.sid
        var stopReason: String? = null
        try {
            send(link, StreamState(sid, StreamRules.STATE_ASKING))
            val grant = try {
                withTimeout(CONSENT_TIMEOUT_MS) {
                    if (start.source == StreamRules.SOURCE_CAMERA) host.requestCameraConsent(sid, pcName(link))
                    else host.requestConsent(sid, pcName(link), start.audio)
                }
            } catch (e: TimeoutCancellationException) {
                stopReason = "timeout"
                return
            }
            if (grant == null) {
                stopReason = "declined"
                return
            }
            if (!isCurrent(claim)) {
                grant.release()
                return
            }
            send(link, StreamState(sid, StreamRules.STATE_STARTING))
            val session = try {
                grant.start(link, start)
            } catch (e: CancellationException) {
                grant.release()
                throw e
            } catch (e: IOException) {
                VeyroLog.w(TAG) { "Media socket not opened: ${e.javaClass.simpleName}" }
                stopReason = "network"
                return
            } catch (e: Exception) {
                VeyroLog.w(TAG) { "Capture not started: ${e.javaClass.simpleName}" }
                stopReason = "encoder"
                return
            }
            claim.session = session
            if (!isCurrent(claim)) {
                session.stop(if (claim.endedByPc) "user" else "session-closed")
                return
            }
            VeyroLog.i(TAG) { "Screen sharing started" }
            var sent: StreamState? = null
            session.state.transformWhile { emit(it); it.stopped == null }.collect { state ->
                val reason = state.stopped
                if (reason != null) {
                    stopReason = reason
                } else if (state.streaming) {
                    val message = stateMessage(sid, state, start.audio)
                    if (message != sent) {
                        sent = message
                        send(link, message)
                    }
                }
            }
        } finally {
            val ended = synchronized(lock) {
                if (active === claim) { active = null; true } else false
            }
            claim.session?.stop(stopReason ?: "session-closed")
            if (ended && !claim.endedByPc && stopReason != null) send(link, StreamStop(sid, stopReason!!))
            VeyroLog.i(TAG) { "Screen sharing ended: ${stopReason ?: "cancelled"}" }
        }
    }

    private fun stateMessage(sid: String, s: ScreenSessionState, audioRequested: Boolean): StreamState = StreamState(
        sid = sid,
        state = if (s.paused) StreamRules.STATE_PAUSED else StreamRules.STATE_STREAMING,
        codec = s.codec,
        w = s.w,
        h = s.h,
        fps = s.fps,
        bitrate = s.bitrate,
        hw = s.hw,
        audio = if (audioRequested) s.audio else null,
        thermal = s.thermal,
        audioPermission = if (audioRequested && !s.audio) s.audioPermission else null,
        camera = s.camera,
    )

    private fun isCurrent(claim: Active): Boolean = synchronized(lock) { active === claim }

    /**
     * Ends the live session of [link] ([sid], or any when null) for [reason]. [tellPc]: send
     * `streamStop` (the phone ended it); false when the PC ended it or its session is gone.
     */
    private suspend fun end(link: CompanionLink, sid: String?, reason: String, tellPc: Boolean, source: String? = null) {
        val current = synchronized(lock) {
            active?.takeIf { it.link == link && (sid == null || it.sid == sid) && (source == null || it.source == source) }
                ?.also { if (!tellPc) it.endedByPc = true }
        } ?: return
        val session = current.session
        if (session != null) {
            // The session's state reports the reason; runSession sends streamStop unless told not to.
            session.stop(reason)
        } else {
            // Still asking: withdraw the request.
            current.job?.cancel()
            if (tellPc) send(link, StreamStop(current.sid, reason))
        }
    }

    private suspend fun send(link: CompanionLink, message: com.veyro.core.link.PeerMessage) {
        try {
            link.send(message)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "Stream message not sent: ${t.javaClass.simpleName}" }
        }
    }
}
