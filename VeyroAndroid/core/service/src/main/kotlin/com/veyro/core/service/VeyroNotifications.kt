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
        public const val ID_RESULT: Int = 1004

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
    }

    public fun progress(
        title: String,
        text: String,
        progressPercent: Int?,
        cancelIntent: PendingIntent?,
        /** True while receiving: teal badge with a download arrow; false: sending. */
        incoming: Boolean = false,
        /** "3.2 MB/s, 12 s left" on the expanded card. */
        meta: String? = null,
        contentIntent: PendingIntent? = null,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setShowWhen(false)
        contentIntent?.let { builder.setContentIntent(it) }
        VeyroNotificationCard.apply(
            context,
            builder,
            VeyroNotificationCard.Spec(
                title = title,
                text = text,
                tone = if (incoming) VeyroNotificationCard.Tone.INCOMING else VeyroNotificationCard.Tone.OUTGOING,
                glyph = if (incoming) VeyroNotificationCard.Glyph.DOWNLOAD else VeyroNotificationCard.Glyph.UPLOAD,
                meta = meta,
                progress = progressPercent?.coerceIn(0, 100),
                // Indeterminate while connecting or hashing: a 0% bar that sits still reads as stuck.
                indeterminate = progressPercent == null,
                // Secondary look (grey), not the gradient: Cancel is not the thing to invite.
                pills = listOfNotNull(cancelIntent?.let { VeyroNotificationCard.Pill("Cancel", it, VeyroNotificationCard.Glyph.CLOSE) }),
                secondaryOnly = true,
            ),
        )
        return builder.build()
    }

    /** A finished transfer or share: green check, or a red badge when it failed. */
    public fun result(
        title: String,
        text: String,
        success: Boolean,
        meta: String? = null,
        thumbnail: android.graphics.Bitmap? = null,
        pills: List<VeyroNotificationCard.Pill> = emptyList(),
        contentIntent: PendingIntent? = null,
        /** Settings > "Clear notifications automatically": null keeps the card until dismissed. */
        autoClearMs: Long? = AUTO_CLEAR_MS,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_PROGRESS)
            .setAutoCancel(true)
            .setCategory(if (success) Notification.CATEGORY_STATUS else Notification.CATEGORY_ERROR)
        contentIntent?.let { builder.setContentIntent(it) }
        autoClearMs?.let { builder.setTimeoutAfter(it) }
        VeyroNotificationCard.apply(
            context,
            builder,
            VeyroNotificationCard.Spec(
                title = title,
                text = text,
                tone = if (success) VeyroNotificationCard.Tone.SUCCESS else VeyroNotificationCard.Tone.ERROR,
                glyph = if (success) VeyroNotificationCard.Glyph.CHECK else VeyroNotificationCard.Glyph.ERROR,
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
        val accept = VeyroNotificationCard.Pill(if (acceptOnceIntent != null) "Always accept" else "Accept", acceptIntent, VeyroNotificationCard.Glyph.CHECK)
        val decline = VeyroNotificationCard.Pill("Decline", declineIntent, VeyroNotificationCard.Glyph.CLOSE)
        VeyroNotificationCard.apply(
            context,
            builder,
            VeyroNotificationCard.Spec(
                title = "$peerName wants to send you $summary",
                text = totalBytesLabel.substringBefore(". ").ifBlank { totalBytesLabel },
                bigText = totalBytesLabel,
                tone = VeyroNotificationCard.Tone.INCOMING,
                glyph = VeyroNotificationCard.Glyph.DOWNLOAD,
                // Collapsed: the short "Accept" when there is a "Just this once" choice to explain.
                pills = listOf(VeyroNotificationCard.Pill("Accept", acceptIntent, VeyroNotificationCard.Glyph.CHECK), decline),
                actions = listOfNotNull(
                    accept,
                    acceptOnceIntent?.let { VeyroNotificationCard.Pill("Just this once", it, VeyroNotificationCard.Glyph.CHECK) },
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
        VeyroNotificationCard.apply(
            context,
            builder,
            VeyroNotificationCard.Spec(
                title = title,
                text = text,
                // Connected: the brand badge with the PC. Looking: the same badge, a search glyph.
                tone = VeyroNotificationCard.Tone.BRAND,
                glyph = if (connected) VeyroNotificationCard.Glyph.DESKTOP else VeyroNotificationCard.Glyph.SEARCH,
                pills = listOfNotNull(
                    sendIntent?.takeIf { connected }?.let { VeyroNotificationCard.Pill("Send files", it, VeyroNotificationCard.Glyph.UPLOAD) },
                    disconnectIntent?.let {
                        VeyroNotificationCard.Pill(if (connected) "Disconnect" else "Stop", it, VeyroNotificationCard.Glyph.DISCONNECT)
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
