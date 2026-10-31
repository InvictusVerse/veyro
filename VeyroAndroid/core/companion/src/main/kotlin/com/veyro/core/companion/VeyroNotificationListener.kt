package com.veyro.core.companion

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The system-bound notification listener.
 *
 * Deliberately thin: it only forwards raw callbacks into [ListenerBridge]. Filtering, mapping and
 * request execution live in [AndroidNotificationSource], which the app graph owns. The system
 * creates this service itself, so a process-wide bridge is the only way to hand it over without
 * reaching into the Application class from a library module.
 *
 * With nobody collecting (mirroring off, or no PC connected) every callback is a dropped
 * `tryEmit` and costs nothing further.
 */
public class VeyroNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        ListenerBridge.listener.value = this
    }

    override fun onListenerDisconnected() {
        ListenerBridge.listener.compareAndSet(this, null)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        ListenerBridge.listener.compareAndSet(this, null)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        ListenerBridge.events.tryEmit(RawNotificationEvent.Posted(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        ListenerBridge.events.tryEmit(RawNotificationEvent.Removed(sbn))
    }

    public companion object {
        /** Whether the user has granted this app notification access in system settings. */
        public fun isAccessGranted(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            return manager.isNotificationListenerAccessGranted(
                ComponentName(context, VeyroNotificationListener::class.java),
            )
        }

        /**
         * Access is granted but the system has not bound the listener (seen on HyperOS after an
         * app update: notifications and media sessions then stay empty until a reboot). Asks the
         * system to bind it again; harmless when it is already bound or access is off.
         */
        public fun rebindIfNeeded(context: Context) {
            if (ListenerBridge.listener.value != null || !isAccessGranted(context)) return
            runCatching {
                requestRebind(ComponentName(context, VeyroNotificationListener::class.java))
            }.onFailure { com.veyro.core.common.VeyroLog.w("NotificationListener") { "Rebind refused: ${it.javaClass.simpleName}" } }
        }

        /** Opens the system screen where notification access is granted. */
        public fun accessSettingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}

internal sealed interface RawNotificationEvent {
    val sbn: StatusBarNotification

    data class Posted(override val sbn: StatusBarNotification) : RawNotificationEvent
    data class Removed(override val sbn: StatusBarNotification) : RawNotificationEvent
}

/** Hand-off between the system-created listener and the app-owned source. */
internal object ListenerBridge {
    val listener = MutableStateFlow<VeyroNotificationListener?>(null)

    val events = MutableSharedFlow<RawNotificationEvent>(
        extraBufferCapacity = 128,
        // Mirroring is best effort; a burst must never block the listener's main-thread callback.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}
