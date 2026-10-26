package com.veyro.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build

/**
 * Notification channels and builders for the transfer services.
 *
 * Two channels on purpose, because they want different behaviour:
 *
 *  - **Progress** is `IMPORTANCE_LOW`: no sound, no vibration, no heads-up. A transfer that pings
 *    every time it updates would be intolerable, and the user already knows they started it.
 *  - **Offers** is `IMPORTANCE_HIGH`: an incoming transfer needs a decision, and a silent
 *    notification for a decision is a decision the user never makes. REQ-T02 requires an explicit
 *    answer, so the prompt has to be able to interrupt.
 *
 * Channel creation is idempotent: `createNotificationChannel` on an existing id updates the name but
 * does not reset user-chosen settings, so calling it on every service start is safe and means a
 * channel is never missing after an upgrade.
 */
public class VeyroNotifications(private val context: Context) {

    public companion object {
        public const val CHANNEL_PROGRESS: String = "veyro.transfer.progress"
        public const val CHANNEL_OFFERS: String = "veyro.transfer.offers"
        public const val CHANNEL_BACKGROUND: String = "veyro.background"

        public const val ID_PROGRESS: Int = 1001
        public const val ID_OFFER: Int = 1002
        public const val ID_BACKGROUND: Int = 1003
    }

    private val manager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    /** Creates every channel. Safe to call repeatedly. */
    public fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = manager ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                "Transfer progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows a transfer while it is running."
                setShowBadge(false)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OFFERS,
                "Incoming transfers",
                // High, deliberately: this notification asks a question, and a question the user
                // never sees is not an explicit decision (REQ-T02).
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Asks whether to accept files from one of your devices."
                setShowBadge(true)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BACKGROUND,
                "Connection to your PC",
                // Low: always present while running in the background, never makes a sound.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description =
                    "Shown while Veyro keeps the connection to your PC open in the background, " +
                        "so it never runs without you knowing."
                setShowBadge(false)
            },
        )
    }

    public fun progress(
        title: String,
        text: String,
        progressPercent: Int?,
        cancelIntent: PendingIntent?,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_PROGRESS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (progressPercent != null) {
            builder.setProgress(100, progressPercent.coerceIn(0, 100), false)
        } else {
            // Indeterminate while connecting or hashing: a 0% bar that sits still reads as stuck.
            builder.setProgress(0, 0, true)
        }

        cancelIntent?.let {
            builder.addAction(
                Notification.Action.Builder(null, "Cancel", it).build(),
            )
        }
        return builder.build()
    }

    /**
     * The incoming-offer notification, with Accept and Decline as actions.
     *
     * Actionable rather than tap-to-open, because the whole point is that the user can answer
     * without the app being in front of them. REQ-T02 is satisfied either way, but a notification
     * that requires opening the app to answer means a transfer stalls whenever the phone is in a
     * pocket.
     */
    public fun offer(
        peerName: String,
        summary: String,
        totalBytesLabel: String,
        acceptIntent: PendingIntent,
        declineIntent: PendingIntent,
        /** Opens the full prompt in the app. */
        contentIntent: PendingIntent? = null,
        /** When the offer is declined automatically; shown as a countdown and used as the timeout. */
        expiresAtEpochMillis: Long = 0L,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_OFFERS)
            .setContentTitle("$peerName wants to send you $summary")
            .setContentText(totalBytesLabel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Accept", acceptIntent).build())
            .addAction(Notification.Action.Builder(null, "Decline", declineIntent).build())
        contentIntent?.let { builder.setContentIntent(it) }
        val remaining = expiresAtEpochMillis - System.currentTimeMillis()
        if (remaining > 0) {
            // Counts down to the same moment the prompt in the app declines on its own.
            builder.setWhen(expiresAtEpochMillis)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(remaining)
        }
        return builder.build()
    }

    /** The background service's persistent notification (ADR-036). */
    public fun background(
        title: String,
        text: String,
        contentIntent: PendingIntent?,
        sendIntent: PendingIntent?,
        disconnectIntent: PendingIntent?,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_BACKGROUND)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        contentIntent?.let { builder.setContentIntent(it) }
        sendIntent?.let { builder.addAction(Notification.Action.Builder(null, "Send files", it).build()) }
        disconnectIntent?.let { builder.addAction(Notification.Action.Builder(null, "Disconnect", it).build()) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    public fun completed(title: String, text: String): Notification =
        Notification.Builder(context, CHANNEL_PROGRESS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()

    public fun show(id: Int, notification: Notification) {
        manager?.notify(id, notification)
    }

    public fun cancel(id: Int) {
        manager?.cancel(id)
    }
}
