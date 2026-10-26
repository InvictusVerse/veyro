package com.veyro.core.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import com.veyro.core.common.VeyroLog

/**
 * Keeps a transfer alive while the app is in the background (REQ-T09).
 *
 * ## Why a foreground service at all
 *
 * A 20 GB transfer takes minutes. Android will freeze a background process long before that, and
 * the socket dies with it. A foreground service with a visible notification is the only sanctioned
 * way to keep running, and the notification is not a tax - it is the honest signal that the app is
 * doing something.
 *
 * ## The locks, and why Invox not having them was a gap
 *
 * The reference project declared `WAKE_LOCK` and never acquired one. That works until the screen
 * goes off mid-transfer, at which point the CPU idles and the Wi-Fi radio drops to a power-saving
 * mode that adds latency and can stall a long transfer entirely.
 *
 *  - **Partial WakeLock**: keeps the CPU running with the screen off. Without it a transfer appears
 *    to "pause when I lock my phone", which is the single most likely user complaint.
 *  - **WifiLock** at `WIFI_MODE_FULL_LOW_LATENCY` (API 29+, which is our minSdk): keeps the radio
 *    out of power-save. Falls back to `WIFI_MODE_FULL_HIGH_PERF` conceptually, but since minSdk is
 *    29 the low-latency mode is always available.
 *
 * Both are acquired **only** while a transfer is running and released in `onDestroy` unconditionally.
 * A leaked wakelock is a battery complaint the user cannot diagnose, so release is in a `finally`-
 * equivalent position rather than on the success path.
 *
 * ## START_NOT_STICKY
 *
 * A transfer cannot be resurrected blind: the session is gone, and the peer has moved on. Restarting
 * the service with a null intent would leave it running with nothing to do. Resume is driven by the
 * receiver re-verifying its part files next time, which is the correct recovery.
 */
public class VeyroTransferService : Service() {

    public companion object {
        private const val TAG = "TransferService"

        public const val ACTION_START: String = "com.veyro.action.TRANSFER_START"
        public const val ACTION_STOP: String = "com.veyro.action.TRANSFER_STOP"
        public const val EXTRA_TRANSFER_ID: String = "transferId"

        private const val WAKE_LOCK_TAG = "veyro:transfer"
        private const val WIFI_LOCK_TAG = "veyro:transfer-wifi"

        /**
         * Safety valve on the wakelock.
         *
         * A timeout means a bug in release cannot hold the CPU awake forever. Ten minutes is longer
         * than any reasonable single-chunk operation but short enough to bound the damage; the lock
         * is re-acquired while a transfer is still progressing.
         */
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60 * 1000L

        public fun start(context: Context, transferId: String) {
            val intent = Intent(context, VeyroTransferService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TRANSFER_ID, transferId)
            }
            context.startForegroundService(intent)
        }

        public fun stop(context: Context) {
            context.startService(
                Intent(context, VeyroTransferService::class.java).apply { action = ACTION_STOP },
            )
        }
    }

    private lateinit var notifications: VeyroNotifications
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        notifications = VeyroNotifications(this).also { it.ensureChannels() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                // Promote within the 5-second window Android allows, before doing anything else.
                // Missing it is an ANR-class crash on API 31+.
                startForegroundCompat()
                acquireLocks()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = notifications.progress(
            title = "Transferring",
            text = "Starting",
            progressPercent = null,
            cancelIntent = null,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34 requires the type to be declared at call time as well as in the manifest.
            startForeground(
                VeyroNotifications.ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(VeyroNotifications.ID_PROGRESS, notification)
        }
    }

    public fun updateProgress(fileName: String, percent: Int, detail: String) {
        notifications.show(
            VeyroNotifications.ID_PROGRESS,
            notifications.progress(
                title = fileName,
                text = detail,
                progressPercent = percent,
                cancelIntent = null,
            ),
        )
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld == true) return

        runCatching {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            }
        }.onFailure { VeyroLog.w(TAG, it) { "Could not acquire the wake lock" } }

        runCatching {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            // minSdk is 29, so low-latency mode is always available. It keeps the radio out of
            // power-save, which is what stops a long transfer stalling with the screen off.
            wifiLock = wifi?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                WIFI_LOCK_TAG,
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { VeyroLog.w(TAG, it) { "Could not acquire the wifi lock" } }

        VeyroLog.d(TAG) { "Locks acquired: wake=${wakeLock?.isHeld}, wifi=${wifiLock?.isHeld}" }
    }

    private fun releaseLocks() {
        // Unconditional and tolerant. A leaked lock is a battery complaint the user cannot
        // diagnose, so this must run even if something above it already failed.
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { VeyroLog.w(TAG, it) { "Could not release the wake lock" } }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            .onFailure { VeyroLog.w(TAG, it) { "Could not release the wifi lock" } }
        wakeLock = null
        wifiLock = null
        VeyroLog.d(TAG) { "Locks released" }
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

