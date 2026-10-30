package com.veyro.core.companion

import com.veyro.core.common.VeyroLog
import com.veyro.core.link.PeerMessage
import com.veyro.core.model.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Where the phone's own media sessions come from. On Android, `MediaSessionManager`. */
public interface MediaSource {

    /** True while sessions can be read (notification-listener access granted and bound). */
    public val available: Flow<Boolean>

    /** Hot "something changed" signal: sessions added/removed, metadata, playback state, volume. */
    public val changes: Flow<Unit>

    /** The current full snapshot. Empty sessions when [available] is false. */
    public suspend fun snapshot(): PeerMessage.MediaState
}

/** Carries out a validated `mediaCmd` against the phone. */
public interface MediaCommandExecutor {
    /** [action] is one of the per-session `MediaCommand` actions; [positionMs] only for `seek`. */
    public suspend fun transport(id: String, action: String, positionMs: Long?)
    public suspend fun setVolume(percent: Int)
    public suspend fun setMuted(muted: Boolean)
}

/**
 * Media control in both directions over the paired session (ADR-026, 04-PROTOCOL 6.2).
 *
 * Everything is gated per link on the peer advertising [Capability.MEDIA_CONTROL].
 *
 *  - **PC media on the phone** is always available on such a link: the latest `mediaState` the
 *    PC sent is [peerMedia] (null when disconnected), and [sendMediaCommand] drives it.
 *  - **Phone media to the PC** additionally needs the user's explicit opt-in ([enabled]) and a
 *    readable [MediaSource]. A full snapshot is sent on activation and on every change, at most
 *    once per [MIN_INTERVAL_MS]; changes inside that window coalesce into one send. Incoming
 *    `mediaCmd` is only executed while publishing, and only against ids in the snapshot the PC
 *    was last sent.
 *
 * Track metadata is user data: nothing here logs a title, artist, album or id.
 */
public class MediaMirror(
    /** Every live paired session (multiple PCs). */
    private val links: Flow<List<CompanionLink>>,
    private val enabled: Flow<Boolean>,
    private val source: MediaSource,
    private val executor: MediaCommandExecutor,
    private val scope: CoroutineScope,
) {

    public companion object {
        private const val TAG = "MediaMirror"

        /** Minimum spacing between two `mediaState` sends. */
        public const val MIN_INTERVAL_MS: Long = 500L

        /** Position drift from the predicted position that counts as a seek (same as Windows). */
        public const val POSITION_TOLERANCE_MS: Long = 1_500L

        /**
         * Whether [next] differs from [previous] in a way the PC must hear about. Position moving
         * as playback predicts (positionMs + elapsed ts while playing) is not a change; a seek,
         * pause, track, art, capability or volume change is. Mirrors Windows
         * `MediaSnapshots.IsSignificantChange`.
         */
        public fun isSignificantChange(previous: PeerMessage.MediaState?, next: PeerMessage.MediaState): Boolean {
            if (previous == null) return true
            if (previous.volume != next.volume || previous.muted != next.muted) return true
            if (previous.sessions.size != next.sessions.size) return true
            for (i in next.sessions.indices) {
                val before = previous.sessions[i]
                val after = next.sessions[i]
                if (before.copy(positionMs = null, ts = null) != after.copy(positionMs = null, ts = null)) return true
                if ((before.positionMs == null) != (after.positionMs == null)) return true
                val oldPosition = before.positionMs ?: continue
                val newPosition = after.positionMs ?: continue
                val oldTs = before.ts
                val newTs = after.ts
                val elapsed = if (before.playing && oldTs != null && newTs != null) maxOf(0L, newTs - oldTs) else 0L
                if (kotlin.math.abs(newPosition - (oldPosition + elapsed)) > POSITION_TOLERANCE_MS) return true
            }
            return false
        }

        private val SESSION_ACTIONS = setOf(
            PeerMessage.MediaCommand.PLAY,
            PeerMessage.MediaCommand.PAUSE,
            PeerMessage.MediaCommand.TOGGLE,
            PeerMessage.MediaCommand.NEXT,
            PeerMessage.MediaCommand.PREV,
            PeerMessage.MediaCommand.SEEK,
        )
    }

    private val _peerMedia = MutableStateFlow<Map<String, PeerMessage.MediaState>>(emptyMap())

    /** Each connected capable PC's media sessions, by device id. A PC that left is absent. */
    public val peerMedia: StateFlow<Map<String, PeerMessage.MediaState>> = _peerMedia.asStateFlow()

    /** Capable links by device id, for [sendMediaCommand]. */
    private val activeLinks = ConcurrentHashMap<String, CompanionLink>()

    /** One PC's session: the snapshot last sent to it while publishing; its commands are checked against it. */
    private class Peer {
        @Volatile var published: PeerMessage.MediaState? = null
    }

    private var job: Job? = null

    /** Starts observing. Idempotent; returns the running job. */
    @Synchronized
    public fun start(): Job = job?.takeIf { it.isActive } ?: scope.launch { run() }.also { job = it }

    /** Sends [command] to the PC [peerId]. False when that PC is not connected or not capable. */
    public suspend fun sendMediaCommand(peerId: String, command: PeerMessage.MediaCommand): Boolean {
        val link = activeLinks[peerId] ?: return false
        return try {
            link.send(command)
            true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "mediaCmd send failed: ${t.javaClass.simpleName}" }
            false
        }
    }

    private suspend fun run() {
        // One session per connected PC: phone media goes to every capable PC, each PC's own
        // media is kept under its id, and a PC's commands are checked against what it was sent.
        links.forEachLink { link ->
            link.peerCapabilities
                .map { Capability.MEDIA_CONTROL in it }
                .distinctUntilChanged()
                .collectLatest { capable -> if (capable) serve(link) }
        }
    }

    private suspend fun serve(link: CompanionLink) {
        val key = link.peerKey
        val peer = Peer()
        activeLinks[key] = link
        try {
            coroutineScope {
                launch {
                    link.incoming.collect { message ->
                        when (message) {
                            is PeerMessage.MediaState -> _peerMedia.update { it + (key to message) }
                            is PeerMessage.MediaCommand -> handleCommand(peer, message)
                            else -> Unit
                        }
                    }
                }

                combine(enabled.distinctUntilChanged(), source.available) { on, available -> on && available }
                    .distinctUntilChanged()
                    .collectLatest { sharing ->
                        if (sharing) {
                            publish(link, peer)
                        } else if (peer.published != null) {
                            // Sharing was switched off mid-session: tell the PC to drop our sessions.
                            peer.published = null
                            link.send(PeerMessage.MediaState(sessions = emptyList()))
                        }
                    }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Usually the socket closing under a send. The next session starts afresh.
            VeyroLog.w(TAG) { "Media session ended: ${t.javaClass.simpleName}" }
        } finally {
            activeLinks.remove(key, link)
            _peerMedia.update { it - key }
            peer.published = null
        }
    }

    /** Snapshot now, then one coalesced snapshot per burst of changes, spaced [MIN_INTERVAL_MS]. */
    private suspend fun publish(link: CompanionLink, peer: Peer) {
        try {
            coroutineScope {
                val signals = Channel<Unit>(Channel.CONFLATED)
                // Subscribed before the first snapshot, so a change in between is not lost.
                launch(start = CoroutineStart.UNDISPATCHED) {
                    source.changes.collect { signals.trySend(Unit) }
                }
                VeyroLog.i(TAG) { "Media sharing started" }
                while (true) {
                    val snapshot = source.snapshot()
                    // Many players (YouTube among them) report their position about once a
                    // second. Progress the PC can extrapolate from positionMs + ts is not sent;
                    // the last sent sample stays, as on Windows (MediaSnapshots.IsSignificantChange).
                    if (isSignificantChange(peer.published, snapshot)) {
                        link.send(snapshot)
                        peer.published = snapshot
                    }
                    delay(MIN_INTERVAL_MS)
                    signals.receive()
                }
            }
        } catch (c: CancellationException) {
            VeyroLog.i(TAG) { "Media sharing stopped" }
            throw c
        }
    }

    private suspend fun handleCommand(peer: Peer, command: PeerMessage.MediaCommand) {
        val snapshot = peer.published
        if (snapshot == null) {
            VeyroLog.d(TAG) { "Ignored mediaCmd: phone media is not being shared" }
            return
        }
        try {
            when (command.action) {
                PeerMessage.MediaCommand.VOLUME -> {
                    val value = command.value ?: return
                    executor.setVolume(value.coerceIn(0L, 100L).toInt())
                }
                PeerMessage.MediaCommand.MUTE -> {
                    val value = command.value ?: return
                    executor.setMuted(value != 0L)
                }
                in SESSION_ACTIONS -> {
                    val id = command.id
                    val session = snapshot.sessions.firstOrNull { it.id == id }
                    if (id == null || session == null) {
                        VeyroLog.d(TAG) { "Ignored mediaCmd for an unknown session" }
                        return
                    }
                    var position: Long? = null
                    if (command.action == PeerMessage.MediaCommand.SEEK) {
                        val target = command.value ?: return
                        if (!session.canSeek) return
                        val max = session.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
                        position = target.coerceIn(0L, max)
                    }
                    executor.transport(id, command.action, position)
                }
                else -> VeyroLog.d(TAG) { "Ignored unknown mediaCmd action" }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "mediaCmd failed: ${t.javaClass.simpleName}" }
        }
    }
}
