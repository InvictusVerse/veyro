package com.veyro.core.service

import android.content.Context
import android.content.Intent
import com.veyro.core.common.AppDispatchers
import kotlinx.coroutines.flow.Flow

/** What the background notification shows about the paired PC. */
public data class BackgroundStatus(
    val peerDeviceId: String,
    val peerName: String,
    val connected: Boolean,
)

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
