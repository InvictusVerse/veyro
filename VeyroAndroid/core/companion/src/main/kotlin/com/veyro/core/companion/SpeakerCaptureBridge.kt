package com.veyro.core.companion

import com.veyro.core.common.VeyroLog
import com.veyro.core.link.CaptureRequest
import com.veyro.core.link.CaptureResult
import com.veyro.core.link.OutgoingFile
import com.veyro.core.link.PeerMessage
import com.veyro.core.link.SpeakerCaptureRules
import com.veyro.core.link.SpeakerStart
import com.veyro.core.link.SpeakerState
import com.veyro.core.link.SpeakerStop
import com.veyro.core.model.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** One playing PC sound session on the phone (ADR-073). */
public interface SpeakerSession {
    /** Completes with a reason when the sound ends by itself (the PC's CLOSE, the network, Stop on the phone). */
    public val ended: Deferred<String>

    /** Stops playing; [reason] goes to the PC on the socket. Idempotent. */
    public fun stop(reason: String)
}

/** What the speaker and capture features need from the platform. Implemented in `:app`. */
public interface SpeakerCaptureHost {
    /** Dials the PC's speaker socket on [link]'s keys and starts playing. Throws [IOException] when it cannot. */
    public suspend fun startSpeaker(link: CompanionLink, start: SpeakerStart, pcName: String): SpeakerSession

    /**
     * Asks the phone's user to take a photo or scan for [pcName] (a notification, then the
     * capture screen). The image file, or null with the reason (`declined`, `cancelled`,
     * `permission`, `error`). Cancelling withdraws the request.
     */
    public suspend fun capture(rid: String, mode: String, pcName: String): CaptureOutcome
}

public sealed interface CaptureOutcome {
    public data class Taken(val file: OutgoingFile) : CaptureOutcome
    public data class Failed(val reason: String) : CaptureOutcome
}

/**
 * The PC's sound on this phone, and photos taken for the PC (ADR-073, 04-PROTOCOL.md 6.10).
 * Per link advertising [Capability.PC_AUDIO] / [Capability.PHOTO_CAPTURE]:
 *  - `speakerStart` (valid, switch on): dial and play, answer `speakerState{playing}`; the end is
 *    `speakerState{stopped, reason}` unless the PC ended it. One speaker session across PCs (`busy`).
 *  - `captureReq` (valid, switch on): ask the user, at most [SpeakerCaptureRules.CAPTURE_EXPIRY_MS];
 *    the image goes as a transfer with `requestId = rid`, anything else as `captureResult{ok: false}`.
 *    One request at a time (`busy`).
 * Sound and images are user data and are never logged.
 */
public class SpeakerCaptureBridge(
    private val links: Flow<List<CompanionLink>>,
    private val speakerAllowed: Flow<Boolean>,
    private val captureAllowed: Flow<Boolean>,
    private val host: SpeakerCaptureHost,
    private val sender: PhotoSender,
    private val pcName: suspend (CompanionLink) -> String,
    private val scope: CoroutineScope,
) {

    private companion object {
        const val TAG = "SpeakerCapture"
    }

    private class Speaker(val link: CompanionLink, val sid: String) {
        @Volatile var session: SpeakerSession? = null
        @Volatile var endedByPc = false
        @Volatile var job: Job? = null
    }

    private val lock = Any()
    private var speaker: Speaker? = null
    private var capturing: String? = null
    private var job: Job? = null

    @Synchronized
    public fun start(): Job = job?.takeIf { it.isActive } ?: scope.launch { run() }.also { job = it }

    private suspend fun run() {
        links.forEachLink { link ->
            link.peerCapabilities
                .map { (Capability.PC_AUDIO in it) to (Capability.PHOTO_CAPTURE in it) }
                .distinctUntilChanged()
                .collectLatest { (audio, photo) -> if (audio || photo) serve(link, audio, photo) }
        }
    }

    private suspend fun serve(link: CompanionLink, audio: Boolean, photo: Boolean) {
        try {
            coroutineScope {
                if (audio) launch {
                    speakerAllowed.collect { on -> if (!on) endSpeaker(link, null, "disabled", tellPc = true) }
                }
                link.incoming.collect { message ->
                    when (message) {
                        is SpeakerStart -> if (audio) onSpeakerStart(link, message)
                        is SpeakerStop -> if (audio && SpeakerCaptureRules.isValid(message)) endSpeaker(link, message.sid, message.reason, tellPc = false)
                        is CaptureRequest -> if (photo) onCapture(link, message)
                        else -> Unit
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "Link ended: ${t.javaClass.simpleName}" }
        } finally {
            endSpeaker(link, null, "session-closed", tellPc = false)
        }
    }

    // ── Speaker ──────────────────────────────────────────────────────────────────────────

    private suspend fun onSpeakerStart(link: CompanionLink, start: SpeakerStart) {
        if (!SpeakerCaptureRules.isValid(start)) {
            VeyroLog.w(TAG) { "speakerStart dropped: a field broke a rule" }
            return
        }
        if (!speakerAllowed.first()) {
            send(link, SpeakerStop(start.sid, "disabled"))
            return
        }
        val claim = Speaker(link, start.sid)
        val busy = synchronized(lock) { if (speaker != null) true else { speaker = claim; false } }
        if (busy) {
            send(link, SpeakerStop(start.sid, "busy"))
            return
        }
        claim.job = scope.launch { runSpeaker(claim, start) }
    }

    private suspend fun runSpeaker(claim: Speaker, start: SpeakerStart) {
        var reason = "error"
        try {
            val session = try {
                host.startSpeaker(claim.link, start, pcName(claim.link))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                reason = "network"
                return
            } catch (e: Exception) {
                VeyroLog.w(TAG) { "PC sound not started: ${e.javaClass.simpleName}" }
                return
            }
            claim.session = session
            if (synchronized(lock) { speaker !== claim }) {
                session.stop(if (claim.endedByPc) "user" else "session-closed")
                return
            }
            send(claim.link, SpeakerState(start.sid, SpeakerCaptureRules.STATE_PLAYING))
            VeyroLog.i(TAG) { "Playing PC sound" }
            reason = session.ended.await()
        } finally {
            val mine = synchronized(lock) { if (speaker === claim) { speaker = null; true } else false }
            claim.session?.stop(reason)
            if (mine && !claim.endedByPc) send(claim.link, SpeakerState(start.sid, SpeakerCaptureRules.STATE_STOPPED, reason))
            VeyroLog.i(TAG) { "PC sound ended: $reason" }
        }
    }

    private suspend fun endSpeaker(link: CompanionLink, sid: String?, reason: String, tellPc: Boolean) {
        val current = synchronized(lock) {
            speaker?.takeIf { it.link == link && (sid == null || it.sid == sid) }?.also { if (!tellPc) it.endedByPc = true }
        } ?: return
        val session = current.session
        if (session != null) session.stop(reason) else current.job?.cancel()
        if (session == null && tellPc) send(link, SpeakerStop(current.sid, reason))
    }

    // ── Capture ──────────────────────────────────────────────────────────────────────────

    private suspend fun onCapture(link: CompanionLink, request: CaptureRequest) {
        if (!SpeakerCaptureRules.isValid(request)) {
            VeyroLog.w(TAG) { "captureReq dropped: a field broke a rule" }
            return
        }
        if (!captureAllowed.first()) {
            send(link, CaptureResult(request.rid, false, "declined"))
            return
        }
        val busy = synchronized(lock) { if (capturing != null) true else { capturing = request.rid; false } }
        if (busy) {
            send(link, CaptureResult(request.rid, false, "busy"))
            return
        }
        scope.launch {
            try {
                val outcome = try {
                    withTimeout(SpeakerCaptureRules.CAPTURE_EXPIRY_MS) { host.capture(request.rid, request.mode, pcName(link)) }
                } catch (e: TimeoutCancellationException) {
                    CaptureOutcome.Failed("expired")
                }
                when (outcome) {
                    is CaptureOutcome.Taken -> {
                        val sent = try {
                            sender.send(link, outcome.file, request.rid)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            false
                        }
                        VeyroLog.i(TAG) { "Capture sent: ok=$sent" }
                        if (!sent) send(link, CaptureResult(request.rid, false, "error"))
                    }
                    is CaptureOutcome.Failed -> {
                        VeyroLog.i(TAG) { "Capture ended: ${outcome.reason}" }
                        send(link, CaptureResult(request.rid, false, outcome.reason.takeIf { it in SpeakerCaptureRules.CAPTURE_REASONS } ?: "error"))
                    }
                }
            } finally {
                synchronized(lock) { if (capturing == request.rid) capturing = null }
            }
        }
    }

    private suspend fun send(link: CompanionLink, message: PeerMessage) {
        try {
            link.send(message)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "Message not sent: ${t.javaClass.simpleName}" }
        }
    }
}
