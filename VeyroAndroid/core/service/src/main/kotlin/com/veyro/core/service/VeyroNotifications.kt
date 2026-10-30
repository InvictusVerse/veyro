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
        public const val CHANNEL_SCREEN_REQUESTS: String = "veyro.screen.requests"
        public const val CHANNEL_SCREEN: String = "veyro.screen"

        public const val ID_PROGRESS: Int = 1001
        public const val ID_OFFER: Int = 1002
        public const val ID_BACKGROUND: Int = 1003
        public const val ID_RESULT: Int = 1004
        public const val ID_SCREEN_REQUEST: Int = 1010
        public const val ID_SCREEN_SHARING: Int = 1011
        public const val ID_SCREEN_AUDIO: Int = 1012
        public const val ID_CAMERA_REQUEST: Int = 1013
        public const val ID_CAMERA: Int = 1014
        public const val ID_SPEAKER: Int = 1015
        public const val ID_CAPTURE_REQUEST: Int = 1016

        /** The default for "Clear notifications automatically": 5 minutes (ADR-044). */
        public const val AUTO_CLEAR_MS: Long = 5 * 60 * 1000L
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

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCREEN_REQUESTS,
                "Screen sharing requests",
                // High: a PC is waiting for an answer, and Android needs a tap to show its dialog.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Asks whether a PC may show your screen, or play your phone's sound."
                setShowBadge(true)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCREEN,
                "Screen sharing",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while a PC shows your screen, with Stop."
                setShowBadge(false)
            },
        )
    }

    /** "<PC> wants to show your screen" (ADR-066), with Share and Decline. */
    public fun screenRequest(
        title: String,
        text: String,
        shareLabel: String,
        shareIntent: PendingIntent,
        declineIntent: PendingIntent?,
        timeoutMs: Long,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_SCREEN_REQUESTS)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(shareIntent)
            .setTimeoutAfter(timeoutMs)
        NotificationCard.apply(
            context,
            builder,
            NotificationCard.Spec(
                title = title,
                text = text,
                pills = listOfNotNull(
                    NotificationCard.Pill(shareLabel, shareIntent, NotificationCard.Glyph.OPEN),
                    declineIntent?.let { NotificationCard.Pill("Decline", it, NotificationCard.Glyph.CLOSE) },
                ),
            ),
        )
        return builder.build()
    }

    /** The screen-sharing service's notification (ADR-066): who sees the screen, and Stop. */
    public fun screenSharing(title: String, text: String, stopIntent: PendingIntent, contentIntent: PendingIntent?): Notification {
        val builder = Notification.Builder(context, CHANNEL_SCREEN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        contentIntent?.let { builder.setContentIntent(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        NotificationCard.apply(
            context,
            builder,
            NotificationCard.Spec(
                title = title,
                text = text,
                pills = listOf(NotificationCard.Pill("Stop", stopIntent, NotificationCard.Glyph.CLOSE)),
            ),
        )
        return builder.build()
    }

    public fun progress(
        title: String,
        text: String,
        progressPercent: Int?,
        cancelIntent: PendingIntent?,
        /** "3.2 MB/s, 12 s left" on the expanded card. */
        meta: String? = null,
        contentIntent: PendingIntent? = null,
        /** The system header line next to "Veyro" (ADR-051). */
        subText: String? = null,
        /** A moving bar without a percentage even when [progressPercent] is null. */
        indeterminate: Boolean = progressPercent == null,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setShowWhen(false)
        contentIntent?.let { builder.setContentIntent(it) }
        NotificationCard.apply(
            context,
            builder,
            NotificationCard.Spec(
                title = title,
                text = text,
                meta = meta,
                progress = progressPercent?.coerceIn(0, 100),
                // Indeterminate while connecting or hashing: a 0% bar that sits still reads as stuck.
                indeterminate = indeterminate,
                subText = subText,
                // Secondary look (grey), not the gradient: Cancel is not the thing to invite.
                pills = listOfNotNull(cancelIntent?.let { NotificationCard.Pill("Cancel", it, NotificationCard.Glyph.CLOSE) }),
                secondaryOnly = true,
            ),
        )
        return builder.build()
    }

    /** A finished transfer or share; [success] sets the category (status or error). */
    public fun result(
        title: String,
        text: String,
        success: Boolean,
        meta: String? = null,
        thumbnail: android.graphics.Bitmap? = null,
        pills: List<NotificationCard.Pill> = emptyList(),
        contentIntent: PendingIntent? = null,
        /** Settings > "Clear notifications automatically": null keeps the card until dismissed. */
        autoClearMs: Long? = AUTO_CLEAR_MS,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_PROGRESS)
            .setAutoCancel(true)
            .setCategory(if (success) Notification.CATEGORY_STATUS else Notification.CATEGORY_ERROR)
        contentIntent?.let { builder.setContentIntent(it) }
        autoClearMs?.let { builder.setTimeoutAfter(it) }
        NotificationCard.apply(
            context,
            builder,
            NotificationCard.Spec(
                title = title,
                text = text,
                meta = meta,
                thumbnail = thumbnail,
                picture = thumbnail,
                pills = pills,
            ),
        )
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
        /**
         * Accepts this offer only. When set, [acceptIntent] is "Always accept", matching the in-app
         * prompt where "Always accept from this PC" is ticked by default (ADR-038).
         */
        acceptOnceIntent: PendingIntent? = null,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_OFFERS)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        contentIntent?.let { builder.setContentIntent(it) }
        val accept = NotificationCard.Pill(if (acceptOnceIntent != null) "Always accept" else "Accept", acceptIntent, NotificationCard.Glyph.CHECK)
        val decline = NotificationCard.Pill("Decline", declineIntent, NotificationCard.Glyph.CLOSE)
        NotificationCard.apply(
            context,
            builder,
            NotificationCard.Spec(
                title = "$peerName wants to send you $summary",
                text = totalBytesLabel.substringBefore(". ").ifBlank { totalBytesLabel },
                bigText = totalBytesLabel,
                // Collapsed: the short "Accept" when there is a "Just this once" choice to explain.
                pills = listOf(NotificationCard.Pill("Accept", acceptIntent, NotificationCard.Glyph.CHECK), decline),
                actions = listOfNotNull(
                    accept,
                    acceptOnceIntent?.let { NotificationCard.Pill("Just this once", it, NotificationCard.Glyph.CHECK) },
                    decline,
                ),
            ),
        )
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
        /** At least one PC is connected; false while only looking. */
        connected: Boolean = true,
        /** More than one device is connected: the button says "Disconnect all" (ADR-053). */
        disconnectsAll: Boolean = false,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_BACKGROUND)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        contentIntent?.let { builder.setContentIntent(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        NotificationCard.apply(
            context,
            builder,
            NotificationCard.Spec(
                title = title,
                text = text,
                pills = listOfNotNull(
                    sendIntent?.takeIf { connected }?.let { NotificationCard.Pill("Send files", it, NotificationCard.Glyph.UPLOAD) },
                    disconnectIntent?.let {
                        val label = when {
                            !connected -> "Stop"
                            disconnectsAll -> "Disconnect all"
                            else -> "Disconnect"
                        }
                        NotificationCard.Pill(label, it, NotificationCard.Glyph.DISCONNECT)
                    },
                ),
            ),
        )
        return builder.build()
    }

    public fun show(id: Int, notification: Notification) {
        manager?.notify(id, notification)
    }

    public fun cancel(id: Int) {
        manager?.cancel(id)
    }
}
