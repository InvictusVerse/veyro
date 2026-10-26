package com.veyro.core.companion

import com.veyro.core.common.VeyroLog
import com.veyro.core.link.LinkMessage
import com.veyro.core.model.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Where the phone's own media sessions come from. On Android, `MediaSessionManager`. */
public interface MediaSource {

    /** True while sessions can be read (notification-listener access granted and bound). */
    public val available: Flow<Boolean>

    /** Hot "something changed" signal: sessions added/removed, metadata, playback state, volume. */
    public val changes: Flow<Unit>

    /** The current full snapshot. Empty sessions when [available] is false. */
    public suspend fun snapshot(): LinkMessage.MediaState
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
    private val links: Flow<CompanionLink?>,
    private val enabled: Flow<Boolean>,
    private val source: MediaSource,
    private val executor: MediaCommandExecutor,
    private val scope: CoroutineScope,
) {

    public companion object {
        private const val TAG = "MediaMirror"

        /** Minimum spacing between two `mediaState` sends. */
        public const val MIN_INTERVAL_MS: Long = 500L

        private val SESSION_ACTIONS = setOf(
            LinkMessage.MediaCommand.PLAY,
            LinkMessage.MediaCommand.PAUSE,
            LinkMessage.MediaCommand.TOGGLE,
            LinkMessage.MediaCommand.NEXT,
            LinkMessage.MediaCommand.PREV,
            LinkMessage.MediaCommand.SEEK,
        )
    }

    private val _peerMedia = MutableStateFlow<LinkMessage.MediaState?>(null)

    /** The connected PC's media sessions, or null when no capable PC is connected. */
    public val peerMedia: StateFlow<LinkMessage.MediaState?> = _peerMedia.asStateFlow()

    private val activeLink = MutableStateFlow<CompanionLink?>(null)

    /** The snapshot last sent to the PC while publishing; commands are checked against it. */
    @Volatile private var published: LinkMessage.MediaState? = null

    private var job: Job? = null

    /** Starts observing. Idempotent; returns the running job. */
    @Synchronized
    public fun start(): Job = job?.takeIf { it.isActive } ?: scope.launch { run() }.also { job = it }

    /** Sends [command] to the connected PC. False when no capable PC is connected. */
    public suspend fun sendMediaCommand(command: LinkMessage.MediaCommand): Boolean {
        val link = activeLink.value ?: return false
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run() {
        links
            .flatMapLatest { link ->
                link?.peerCapabilities?.map { caps ->
                    link.takeIf { Capability.MEDIA_CONTROL in caps }
                } ?: flowOf(null)
            }
            .distinctUntilChanged()
            .collectLatest { link -> if (link != null) serve(link) }
    }

    private suspend fun serve(link: CompanionLink) {
        activeLink.value = link
        try {
            coroutineScope {
                launch {
                    link.incoming.collect { message ->
                        when (message) {
                            is LinkMessage.MediaState -> _peerMedia.value = message
                            is LinkMessage.MediaCommand -> handleCommand(message)
                            else -> Unit
                        }
                    }
                }

                combine(enabled.distinctUntilChanged(), source.available) { on, available -> on && available }
                    .distinctUntilChanged()
                    .collectLatest { sharing ->
                        if (sharing) {
                            publish(link)
                        } else if (published != null) {
                            // Sharing was switched off mid-session: tell the PC to drop our sessions.
                            published = null
                            link.send(LinkMessage.MediaState(sessions = emptyList()))
                        }
                    }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Usually the socket closing under a send. The next session starts afresh.
            VeyroLog.w(TAG) { "Media session ended: ${t.javaClass.simpleName}" }
        } finally {
            activeLink.compareAndSet(link, null)
            _peerMedia.value = null
            published = null
        }
    }

    /** Snapshot now, then one coalesced snapshot per burst of changes, spaced [MIN_INTERVAL_MS]. */
    private suspend fun publish(link: CompanionLink) {
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
                    if (snapshot != published) {
                        link.send(snapshot)
                        published = snapshot
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

    private suspend fun handleCommand(command: LinkMessage.MediaCommand) {
        val snapshot = published
        if (snapshot == null) {
            VeyroLog.d(TAG) { "Ignored mediaCmd: phone media is not being shared" }
            return
        }
        try {
            when (command.action) {
                LinkMessage.MediaCommand.VOLUME -> {
                    val value = command.value ?: return
                    executor.setVolume(value.coerceIn(0L, 100L).toInt())
                }
                LinkMessage.MediaCommand.MUTE -> {
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
                    if (command.action == LinkMessage.MediaCommand.SEEK) {
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
