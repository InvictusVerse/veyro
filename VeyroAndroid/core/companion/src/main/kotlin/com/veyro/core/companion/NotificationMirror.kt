package com.veyro.core.companion

import com.veyro.core.common.VeyroLog
import com.veyro.core.link.LinkMessage
import com.veyro.core.model.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val links: Flow<CompanionLink?>,
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run() {
        enabled
            .distinctUntilChanged()
            .flatMapLatest { on ->
                if (!on) {
                    flowOf(null)
                } else {
                    combine(links, source.available) { link, available -> link.takeIf { available } }
                }
            }
            .flatMapLatest { link ->
                link?.peerCapabilities?.map { caps ->
                    link.takeIf { Capability.NOTIFICATION_MIRROR in caps }
                } ?: flowOf(null)
            }
            .distinctUntilChanged()
            .collectLatest { link -> if (link != null) mirrorTo(link) }
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
                    link.send(notification.copy(kind = LinkMessage.Notification.KIND_ACTIVE))
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

    private suspend fun handleRequest(message: LinkMessage) {
        val key = when (message) {
            is LinkMessage.NotificationActionRequest -> message.key
            is LinkMessage.NotificationReplyRequest -> message.key
            is LinkMessage.NotificationDismissRequest -> message.key
            else -> return
        }
        // Re-resolved against live state every time: the PC can only name what is showing now.
        if (!source.isActive(key)) {
            VeyroLog.d(TAG) { "Ignored ${message.javaClass.simpleName} for an unknown notification" }
            return
        }
        try {
            when (message) {
                is LinkMessage.NotificationActionRequest -> executor.invokeAction(key, message.index)
                is LinkMessage.NotificationReplyRequest -> {
                    if (message.text.isEmpty() || message.text.length > MAX_REPLY_CHARS) {
                        VeyroLog.w(TAG) { "Ignored reply of ${message.text.length} chars" }
                        return
                    }
                    executor.reply(key, message.text)
                }
                is LinkMessage.NotificationDismissRequest -> executor.dismiss(key)
                else -> Unit
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "${message.javaClass.simpleName} failed: ${t.javaClass.simpleName}" }
        }
    }

    private fun NotificationEvent.toMessage(): LinkMessage.Notification = when (this) {
        is NotificationEvent.Posted -> notification.copy(kind = LinkMessage.Notification.KIND_POSTED)
        is NotificationEvent.Removed -> LinkMessage.Notification(
            key = key,
            kind = LinkMessage.Notification.KIND_REMOVED,
        )
    }
}
