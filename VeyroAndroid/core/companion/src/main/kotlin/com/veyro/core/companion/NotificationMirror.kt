package com.veyro.core.companion

import com.veyro.core.common.VeyroLog
import com.veyro.core.link.PeerMessage
import com.veyro.core.model.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Mirrors phone notifications to the connected PC and executes the PC's requests (ADR-025).
 *
 * Active only while all of these hold, and torn down the moment any stops holding:
 *  - the user switched mirroring on ([enabled]),
 *  - a paired session is live ([links]),
 *  - the peer advertised [Capability.NOTIFICATION_MIRROR] in its `hello`/`helloAck`,
 *  - notifications are readable ([NotificationSource.available]).
 *
 * On activation every current notification is sent with `kind = active`, then posted/removed
 * changes follow as they happen. [links] is only collected while [enabled] is true, so with
 * mirroring off this class never touches the connection layer at all.
 *
 * Notification contents are user data: nothing here logs a title, text, message or key.
 */
public class NotificationMirror(
    /** Every live paired session (multiple PCs). */
    private val links: Flow<List<CompanionLink>>,
    private val enabled: Flow<Boolean>,
    private val source: NotificationSource,
    private val executor: NotificationActionExecutor,
    private val scope: CoroutineScope,
) {

    public companion object {
        private const val TAG = "NotificationMirror"

        /** Longest reply accepted from the PC, in UTF-16 chars. */
        public const val MAX_REPLY_CHARS: Int = 4096

        /** Events buffered while the initial active set is being sent. Oldest dropped beyond. */
        private const val PENDING_EVENTS = 128
    }

    private var job: Job? = null

    /** Starts observing. Idempotent; returns the running job. */
    @Synchronized
    public fun start(): Job = job?.takeIf { it.isActive } ?: scope.launch { run() }.also { job = it }

    private suspend fun run() {
        enabled
            .distinctUntilChanged()
            .collectLatest { on ->
                // One mirror per connected PC; each answers requests on its own session only.
                if (on) links.forEachLink { link -> mirrorWhileCapable(link) }
            }
    }

    private suspend fun mirrorWhileCapable(link: CompanionLink) {
        combine(link.peerCapabilities, source.available) { caps, available ->
            available && Capability.NOTIFICATION_MIRROR in caps
        }
            .distinctUntilChanged()
            .collectLatest { active -> if (active) mirrorTo(link) }
    }

    private suspend fun mirrorTo(link: CompanionLink) {
        try {
            coroutineScope {
                launch { link.incoming.collect { handleRequest(it) } }

                // Subscribe before taking the snapshot, so nothing posted in between is lost. A
                // notification caught by both is sent twice, which the PC treats as an update.
                val pending = Channel<NotificationEvent>(PENDING_EVENTS, BufferOverflow.DROP_OLDEST)
                launch(start = CoroutineStart.UNDISPATCHED) {
                    source.events.collect { pending.send(it) }
                }

                val active = source.activeNotifications()
                for (notification in active) {
                    link.send(notification.copy(kind = PeerMessage.Notification.KIND_ACTIVE))
                }
                VeyroLog.i(TAG) { "Mirroring started; sent ${active.size} active notification(s)" }

                for (event in pending) {
                    link.send(event.toMessage())
                }
            }
        } catch (c: CancellationException) {
            VeyroLog.i(TAG) { "Mirroring stopped" }
            throw c
        } catch (t: Throwable) {
            // Usually the socket closing under a send. The next session starts afresh.
            VeyroLog.w(TAG) { "Mirroring ended: ${t.javaClass.simpleName}" }
        }
    }

    private suspend fun handleRequest(message: PeerMessage) {
        val key = when (message) {
            is PeerMessage.NotificationActionRequest -> message.key
            is PeerMessage.NotificationReplyRequest -> message.key
            is PeerMessage.NotificationDismissRequest -> message.key
            else -> return
        }
        // Re-resolved against live state every time: the PC can only name what is showing now.
        VeyroLog.i(TAG) { "PC request: ${message.javaClass.simpleName}" }
        if (!source.isActive(key)) {
            VeyroLog.i(TAG) { "Ignored ${message.javaClass.simpleName}: that notification is no longer showing" }
            return
        }
        try {
            when (message) {
                is PeerMessage.NotificationActionRequest -> executor.invokeAction(key, message.index)
                is PeerMessage.NotificationReplyRequest -> {
                    if (message.text.isEmpty() || message.text.length > MAX_REPLY_CHARS) {
                        VeyroLog.w(TAG) { "Ignored reply of ${message.text.length} chars" }
                        return
                    }
                    executor.reply(key, message.text)
                }
                is PeerMessage.NotificationDismissRequest -> executor.dismiss(key)
                else -> Unit
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "${message.javaClass.simpleName} failed: ${t.javaClass.simpleName}" }
        }
    }

    private fun NotificationEvent.toMessage(): PeerMessage.Notification = when (this) {
        is NotificationEvent.Posted -> notification.copy(kind = PeerMessage.Notification.KIND_POSTED)
        is NotificationEvent.Removed -> PeerMessage.Notification(
            key = key,
            kind = PeerMessage.Notification.KIND_REMOVED,
        )
    }
}
