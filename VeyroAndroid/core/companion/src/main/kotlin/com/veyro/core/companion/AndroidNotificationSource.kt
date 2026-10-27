package com.veyro.core.companion

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
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

    /**
     * Which app and conversation each key sent to the PC belonged to. Chat apps often re-post a
     * conversation's notification under a new key when a message arrives, so a reply typed on the
     * PC can name a key that is gone; it is then sent to the live notification of the same app and
     * conversation instead (never to another conversation). Held in memory, newest 500 keys.
     */
    private data class Origin(val pkg: String, val title: String)

    private val origins = object : LinkedHashMap<String, Origin>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Origin>?): Boolean = size > 500
    }

    private fun remember(wire: LinkMessage.Notification): LinkMessage.Notification {
        val pkg = wire.pkg
        val title = wire.title
        if (pkg != null && title != null) synchronized(origins) { origins[wire.key] = Origin(pkg, title) }
        return wire
    }

    override val available: Flow<Boolean> =
        ListenerBridge.listener.map { it != null }.distinctUntilChanged()

    // Mapping runs in the collector's coroutine (withContext, not flowOn), so the bridge
    // subscription is in place as soon as collection starts.
    override val events: Flow<NotificationEvent> = ListenerBridge.events.mapNotNull { raw ->
        withContext(dispatchers.default) {
            when (raw) {
                is RawNotificationEvent.Posted ->
                    mapper.toWire(raw.sbn, LinkMessage.Notification.KIND_POSTED)
                        ?.let { NotificationEvent.Posted(remember(it)) }
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
            all.mapNotNull { mapper.toWire(it, LinkMessage.Notification.KIND_ACTIVE)?.let(::remember) }
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
        // Each step logs its outcome (never the text, the key or the app's content), so a reply
        // that does not arrive can be traced in logcat: tag Veyro.NotificationSource, "[reply]".
        val sbn = resolve(key) ?: run {
            VeyroLog.i(TAG) { "[reply] dropped: the notification is no longer showing" }
            return
        }
        val actions = sbn.notification.actions.orEmpty()
        // The free-text input, not a choices-only one (some apps add canned replies as their own action).
        val action = actions.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
            ?: actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            ?: run {
                VeyroLog.i(TAG) { "[reply] dropped: the notification has no reply action (${actions.size} action(s))" }
                return
            }
        val pendingIntent = action.actionIntent ?: run {
            VeyroLog.i(TAG) { "[reply] dropped: the reply action has no intent" }
            return
        }
        val remoteInputs = action.remoteInputs
        val results = Bundle().apply {
            remoteInputs.forEach { putCharSequence(it.resultKey, text) }
        }
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)
        // What a reply typed in the shade reports: free-form text from the user.
        RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        withContext(dispatchers.io) {
            try {
                pendingIntent.send(appContext, 0, fillIn, null, null, null, sendOptions())
                VeyroLog.i(TAG) { "[reply] sent to the app's reply action (${kindOf(pendingIntent)})" }
            } catch (e: PendingIntent.CanceledException) {
                VeyroLog.i(TAG) { "[reply] dropped: the app withdrew its reply action" }
            } catch (t: Throwable) {
                VeyroLog.w(TAG) { "[reply] failed: ${t.javaClass.simpleName}" }
            }
        }
    }

    /**
     * Android 14+ blocks an activity start from a PendingIntent sent by a background app unless
     * the sender opts in. A few chat apps' reply actions open a quick-reply activity; Veyro holds a
     * foreground service while connected, so it may opt in. Broadcasts and services ignore this.
     */
    private fun sendOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
    }

    private fun kindOf(intent: PendingIntent): String = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "unknown type"
        intent.isBroadcast -> "broadcast"
        intent.isForegroundService -> "foreground service"
        intent.isService -> "service"
        intent.isActivity -> "activity"
        else -> "unknown type"
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

    /**
     * The live notification for [key], if it is showing and mirrorable; otherwise the newest live
     * notification of the same app with the same title (the same conversation, re-posted under a
     * new key). Never a different app or conversation.
     */
    private suspend fun resolve(key: String): StatusBarNotification? = withContext(dispatchers.io) {
        val listener = ListenerBridge.listener.value ?: return@withContext null
        val exact = try {
            listener.getActiveNotifications(arrayOf(key))?.firstOrNull { it.key == key }
        } catch (e: SecurityException) {
            null
        }
        exact?.takeIf { mapper.isMirrorable(it) }?.let { return@withContext it }

        val origin = synchronized(origins) { origins[key] } ?: return@withContext null
        val all = try {
            listener.activeNotifications.orEmpty()
        } catch (e: SecurityException) {
            emptyArray()
        }
        all.filter { it.packageName == origin.pkg && titleOf(it) == origin.title && mapper.isMirrorable(it) }
            .maxByOrNull { it.postTime }
            ?.also { VeyroLog.i(TAG) { "Request matched the same conversation under a newer key" } }
    }

    private fun titleOf(sbn: StatusBarNotification): String? {
        val extras = sbn.notification?.extras ?: return null
        return (extras.getCharSequence(Notification.EXTRA_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))
            ?.toString()?.takeIf { it.isNotBlank() }
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
