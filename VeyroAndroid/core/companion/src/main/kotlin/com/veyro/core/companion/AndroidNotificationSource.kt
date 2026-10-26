package com.veyro.core.companion

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.link.LinkMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext

/**
 * [NotificationSource] and [NotificationActionExecutor] backed by [VeyroNotificationListener].
 *
 * Every request re-resolves its key against the listener's *current* active notifications and
 * re-applies the mirror filter. A key the phone is not showing, or never offered, does nothing.
 * The PC never supplies an intent: it can only point at an action the notification already has.
 */
public class AndroidNotificationSource(
    context: Context,
    private val dispatchers: AppDispatchers,
) : NotificationSource, NotificationActionExecutor {

    private companion object {
        const val TAG = "NotificationSource"
    }

    private val appContext: Context = context.applicationContext
    private val mapper by lazy { NotificationMapper(appContext) }

    override val available: Flow<Boolean> =
        ListenerBridge.listener.map { it != null }.distinctUntilChanged()

    // Mapping runs in the collector's coroutine (withContext, not flowOn), so the bridge
    // subscription is in place as soon as collection starts.
    override val events: Flow<NotificationEvent> = ListenerBridge.events.mapNotNull { raw ->
        withContext(dispatchers.default) {
            when (raw) {
                is RawNotificationEvent.Posted ->
                    mapper.toWire(raw.sbn, LinkMessage.Notification.KIND_POSTED)
                        ?.let { NotificationEvent.Posted(it) }
                is RawNotificationEvent.Removed ->
                    if (mapper.isMirrorable(raw.sbn)) NotificationEvent.Removed(raw.sbn.key) else null
            }
        }
    }

    override suspend fun activeNotifications(): List<LinkMessage.Notification> =
        withContext(dispatchers.io) {
            val listener = ListenerBridge.listener.value ?: return@withContext emptyList()
            val all = try {
                listener.activeNotifications.orEmpty()
            } catch (e: SecurityException) {
                // Access was revoked between the bind and this call.
                VeyroLog.w(TAG) { "Active notifications unavailable: ${e.javaClass.simpleName}" }
                emptyArray()
            }
            all.mapNotNull { mapper.toWire(it, LinkMessage.Notification.KIND_ACTIVE) }
        }

    override suspend fun isActive(key: String): Boolean = resolve(key) != null

    override suspend fun invokeAction(key: String, index: Int) {
        val sbn = resolve(key) ?: return
        val action = sbn.notification.actions?.getOrNull(index) ?: return
        // A reply action is only reachable through notifReply, which supplies the RemoteInput.
        if (!action.remoteInputs.isNullOrEmpty()) return
        val intent = action.actionIntent ?: return
        withContext(dispatchers.io) { sendSafely { intent.send() } }
    }

    override suspend fun reply(key: String, text: String) {
        val sbn = resolve(key) ?: return
        val action = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return
        val pendingIntent = action.actionIntent ?: return
        val remoteInputs = action.remoteInputs
        val results = Bundle().apply {
            remoteInputs.forEach { putCharSequence(it.resultKey, text) }
        }
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)
        withContext(dispatchers.io) { sendSafely { pendingIntent.send(appContext, 0, fillIn) } }
    }

    override suspend fun dismiss(key: String) {
        if (resolve(key) == null) return
        val listener = ListenerBridge.listener.value ?: return
        withContext(dispatchers.io) {
            try {
                listener.cancelNotification(key)
            } catch (e: SecurityException) {
                VeyroLog.w(TAG) { "Dismiss refused: ${e.javaClass.simpleName}" }
            }
        }
    }

    /** The live notification for [key], if it is showing and mirrorable. */
    private suspend fun resolve(key: String): StatusBarNotification? = withContext(dispatchers.io) {
        val listener = ListenerBridge.listener.value ?: return@withContext null
        val found = try {
            listener.getActiveNotifications(arrayOf(key))?.firstOrNull { it.key == key }
        } catch (e: SecurityException) {
            null
        }
        found?.takeIf { mapper.isMirrorable(it) }
    }

    private inline fun sendSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: PendingIntent.CanceledException) {
            // The app withdrew the action after we looked it up. Nothing to do.
            VeyroLog.d(TAG) { "Notification action was already cancelled" }
        }
    }
}
