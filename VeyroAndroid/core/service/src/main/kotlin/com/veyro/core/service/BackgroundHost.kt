package com.veyro.core.service

import android.content.Context
import android.content.Intent
import com.veyro.core.common.AppDispatchers
import kotlinx.coroutines.flow.Flow

/**
 * What the background notification shows about the kept PCs (multiple PCs). [peerDeviceId] and
 * [peerName] name the PC the notification's Send files goes to; [connected] is true while at
 * least one kept PC is connected.
 */
public data class BackgroundStatus(
    val peerDeviceId: String,
    val peerName: String,
    val connected: Boolean,
    /** Every kept PC connected right now. */
    val connectedNames: List<String> = if (connected) listOf(peerName) else emptyList(),
    /** Every kept PC not connected right now, being looked for. */
    val lookingNames: List<String> = if (connected) emptyList() else listOf(peerName),
    /**
     * Something stops Veyro reaching any device (ADR-061: Android 17's local network permission is
     * not granted). Shown instead of the status, so a start from boot never fails silently.
     */
    val problemTitle: String? = null,
    val problemText: String? = null,
) {
    /** "Connected to Office PC", "Connected to 2 devices", "Looking for Office PC". PCs and phones. */
    val title: String
        get() = when {
            problemTitle != null -> problemTitle
            connectedNames.size > 1 -> "Connected to ${connectedNames.size} devices"
            connectedNames.size == 1 -> "Connected to ${connectedNames.single()}"
            lookingNames.size > 1 -> "Looking for ${lookingNames.size} devices"
            else -> "Looking for ${lookingNames.firstOrNull() ?: peerName}"
        }

    /** The names behind a count, and what is still being looked for. */
    val text: String
        get() = when {
            problemText != null -> problemText
            connectedNames.size > 1 && lookingNames.isEmpty() -> connectedNames.joinToString(", ")
            connectedNames.size > 1 -> connectedNames.joinToString(", ") + ". Looking for " + lookingNames.joinToString(", ")
            connectedNames.size == 1 && lookingNames.isNotEmpty() -> "Looking for " + lookingNames.joinToString(", ")
            connectedNames.size == 1 -> "Notifications, photos and files stay in sync."
            lookingNames.size > 1 -> lookingNames.joinToString(", ") + ". Reconnects when they are on the same network."
            else -> "Reconnects when it is on the same network."
        }
}

/**
 * What [VeyroBackgroundService] and [VeyroBootReceiver] need from the app, implemented in `:app`
 * (ADR-005: only `:app` wires the graph). The service holds no connection logic of its own; it
 * keeps the process in the foreground and shows what this host reports.
 */
public interface BackgroundHost {

    public val dispatchers: AppDispatchers

    /** The notification's content; null means there is nothing to keep and the service stops. */
    public val backgroundStatus: Flow<BackgroundStatus?>

    /** The service is in the foreground: start keeping the session alive. */
    public fun onBackgroundStarted()

    /** The service stopped: stop reconnecting. Does not close a live session. */
    public fun onBackgroundStopped()

    /** The notification's Disconnect: close the session and stay off until the user reconnects. */
    public fun onDisconnectRequested()

    /**
     * After boot: decide whether the user asked for the background connection to start, and
     * start it if so. [done] must be called when finished (it ends the receiver's `goAsync`).
     */
    public fun onBoot(context: Context, done: () -> Unit)

    /** After Veyro was updated (ADR-061): the same rules as [onBoot]. */
    public fun onAppUpdated(context: Context, done: () -> Unit) {
        onBoot(context, done)
    }

    /** Opens the app; with [sendToDeviceId], on the Send screen for that device. */
    public fun openAppIntent(context: Context, sendToDeviceId: String?): Intent

    /** Implemented by the Application. */
    public interface Provider {
        public val backgroundHost: BackgroundHost
    }

    public companion object {
        public fun from(context: Context): BackgroundHost? =
            (context.applicationContext as? Provider)?.backgroundHost
    }
}
