package com.veyro.core.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.veyro.core.common.VeyroLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the companion session to the paired PC alive while the app is in the background
 * (ADR-036). Replaces the never-started `VeyroDiscoverableService`: one background service, not
 * two competing for the same job.
 *
 * ## Type
 *
 * `connectedDevice`, not `dataSync`. This keeps a link to an external device open for as long as
 * the user wants it, which is what connectedDevice describes; dataSync is for bounded uploads and
 * on Android 15 is capped at six hours a day and may not start from `BOOT_COMPLETED`. The
 * runtime prerequisite on API 34 (one of the Wi-Fi state permissions) is met by
 * `CHANGE_WIFI_MULTICAST_STATE`, a normal permission. Transfers keep `VeyroTransferService`
 * (dataSync) for their bounded lifetime.
 *
 * ## What it does not do
 *
 * No wake lock, no Wi-Fi lock, no polling. Reconnection lives in the host's `CompanionKeeper`
 * (bounded discovery windows, exponential backoff, woken by the NetworkCallback). This class only
 * holds the foreground state and renders the host's status.
 *
 * `START_STICKY`: if the process is killed while the user wants the connection kept, coming back
 * is correct; the host decides again whether there is anything to keep.
 */
public class VeyroBackgroundService : Service() {

    public companion object {
        private const val TAG = "BackgroundService"

        public const val ACTION_START: String = "com.veyro.action.BACKGROUND_START"
        public const val ACTION_DISCONNECT: String = "com.veyro.action.BACKGROUND_DISCONNECT"

        /**
         * Starts the service. Only allowed while the app is visible or from `BOOT_COMPLETED`;
         * from anywhere else Android 12+ refuses, which is reported as false, not thrown.
         */
        public fun start(context: Context): Boolean = try {
            context.startForegroundService(
                Intent(context, VeyroBackgroundService::class.java).apply { action = ACTION_START },
            )
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
            VeyroLog.w(TAG) { "Background service start refused (${e.javaClass.simpleName}); retried when the app is opened" }
            false
        } catch (e: SecurityException) {
            VeyroLog.w(TAG) { "Background service start refused (${e.javaClass.simpleName})" }
            false
        }

        public fun stop(context: Context) {
            context.stopService(Intent(context, VeyroBackgroundService::class.java))
        }
    }

    private lateinit var notifications: VeyroNotifications
    private var host: BackgroundHost? = null
    private var scope: CoroutineScope? = null
    private var lastStatus: BackgroundStatus? = null

    override fun onCreate() {
        super.onCreate()
        notifications = VeyroNotifications(this).also { it.ensureChannels() }
        host = BackgroundHost.from(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote first, within the window Android allows, whatever the action.
        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val host = host ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISCONNECT) {
            host.onDisconnectRequested()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (scope == null) {
            val started = CoroutineScope(SupervisorJob() + host.dispatchers.main)
            scope = started
            host.onBackgroundStarted()
            started.launch {
                host.backgroundStatus.distinctUntilChanged().collect { status ->
                    if (status == null) {
                        VeyroLog.i(TAG) { "Nothing to keep; stopping" }
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        lastStatus = status
                        notifications.show(VeyroNotifications.ID_BACKGROUND, buildNotification(status))
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(): Boolean {
        val notification = buildNotification(lastStatus)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    VeyroNotifications.ID_BACKGROUND,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(VeyroNotifications.ID_BACKGROUND, notification)
            }
            true
        } catch (e: RuntimeException) {
            // SecurityException for a missing prerequisite, or the start-not-allowed variant.
            VeyroLog.w(TAG) { "Could not enter the foreground (${e.javaClass.simpleName})" }
            false
        }
    }

    private fun buildNotification(status: BackgroundStatus?) = notifications.background(
        title = when {
            status == null -> "Veyro is running in the background"
            status.connected -> "Connected to ${status.peerName}"
            else -> "Looking for ${status.peerName}"
        },
        text = when {
            status == null -> "Keeping your PC connection ready."
            status.connected -> "Notifications, photos and files stay in sync."
            else -> "Reconnects when your PC is on the same network."
        },
        contentIntent = host?.let { activityIntent(it.openAppIntent(this, null), REQUEST_OPEN) },
        sendIntent = host?.takeIf { status != null }
            ?.let { activityIntent(it.openAppIntent(this, status?.peerDeviceId), REQUEST_SEND) },
        disconnectIntent = PendingIntent.getService(
            this,
            REQUEST_DISCONNECT,
            Intent(this, VeyroBackgroundService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    private fun activityIntent(intent: Intent, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        host?.onBackgroundStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private const val REQUEST_OPEN = 10
private const val REQUEST_SEND = 11
private const val REQUEST_DISCONNECT = 12
