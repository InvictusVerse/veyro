package com.veyro.core.companion

import com.veyro.core.link.LinkMessage
import com.veyro.core.link.PeerConnection
import com.veyro.core.model.Capabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The seams [NotificationMirror] works through.
 *
 * Kept free of `android.*` so the mirror's decisions (when to send, what to ignore, what to
 * dispatch) are JVM-testable with fakes. The Android side is [AndroidNotificationSource].
 */

/** A change to the phone's notifications, already filtered and mapped to wire form. */
public sealed interface NotificationEvent {
    public data class Posted(val notification: LinkMessage.Notification) : NotificationEvent
    public data class Removed(val key: String) : NotificationEvent
}

/** Where notifications come from. On Android, the bound `NotificationListenerService`. */
public interface NotificationSource {

    /** True while notifications can be read (access granted and the listener is bound). */
    public val available: Flow<Boolean>

    /** Hot stream of posted/removed events. Only events after collection starts are seen. */
    public val events: Flow<NotificationEvent>

    /** Every mirrorable notification currently showing. Empty when [available] is false. */
    public suspend fun activeNotifications(): List<LinkMessage.Notification>

    /**
     * Whether [key] names a notification that is showing right now and passes the mirror filter.
     *
     * This is the check that stops the PC from acting on anything the phone did not offer it: a
     * stale key, a guessed key, or a notification that was never mirrored (ADR-025 point 3).
     */
    public suspend fun isActive(key: String): Boolean
}

/** Carries out a validated PC request. Implementations re-resolve [key] themselves too. */
public interface NotificationActionExecutor {
    public suspend fun invokeAction(key: String, index: Int)
    public suspend fun reply(key: String, text: String)
    public suspend fun dismiss(key: String)
}

/** The part of a paired session the mirror needs. */
public interface CompanionLink {
    public val peerCapabilities: Flow<Capabilities>
    public val incoming: Flow<LinkMessage>
    public suspend fun send(message: LinkMessage)

    /** The peer's device id as the session authenticated it, when known. Display lookups only. */
    public val peerDeviceId: String? get() = null
}

/**
 * Adapts a live [PeerConnection]. A data class so two adapters of one connection are equal.
 * [connection] is public so `:app` can route a photo original over this same session (ADR-034).
 */
public data class PeerConnectionLink(public val connection: PeerConnection) : CompanionLink {
    override val peerDeviceId: String? get() = connection.peerDeviceId
    override val peerCapabilities: Flow<Capabilities> get() = connection.peerCapabilities
    override val incoming: Flow<LinkMessage> get() = connection.incoming.map { it.payload }
    override suspend fun send(message: LinkMessage): Unit = connection.send(message)
}

public fun PeerConnection.asCompanionLink(): CompanionLink = PeerConnectionLink(this)

/** One optional link as the list every companion feature takes (a single PC, or tests). */
public fun Flow<CompanionLink?>.asLinkList(): Flow<List<CompanionLink>> = map { listOfNotNull(it) }

/**
 * Runs [block] once per live link, concurrently: started when a link appears in the latest list,
 * cancelled when it leaves (multiple PCs). A block that returns on its own is not restarted while
 * its link stays. Phone-side events therefore reach every connected PC, and a request is handled
 * by the block of the link it arrived on, so it is answered on that PC's session only.
 */
internal suspend fun Flow<List<CompanionLink>>.forEachLink(block: suspend (CompanionLink) -> Unit) {
    coroutineScope {
        val running = HashMap<CompanionLink, Job>()
        collect { links ->
            val current = links.toSet()
            for (gone in running.keys.filter { it !in current }) running.remove(gone)?.cancel()
            for (link in current) {
                if (link !in running) running[link] = launch { block(link) }
            }
        }
    }
}

/** Key for per-PC state received from [this] link. */
internal val CompanionLink.peerKey: String get() = peerDeviceId.orEmpty()
