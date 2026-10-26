package com.veyro.core.companion

import com.veyro.core.link.LinkMessage
import com.veyro.core.link.PeerConnection
import com.veyro.core.model.Capabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
}

/** Adapts a live [PeerConnection]. A data class so two adapters of one connection are equal. */
public data class PeerConnectionLink(private val connection: PeerConnection) : CompanionLink {
    override val peerCapabilities: Flow<Capabilities> get() = connection.peerCapabilities
    override val incoming: Flow<LinkMessage> get() = connection.incoming.map { it.payload }
    override suspend fun send(message: LinkMessage): Unit = connection.send(message)
}

public fun PeerConnection.asCompanionLink(): CompanionLink = PeerConnectionLink(this)
