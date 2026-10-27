package com.veyro.core.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews

/**
 * One look for every Veyro notification (ADR-044): a gradient badge with a glyph (or a thumbnail),
 * a title and line, a slim progress bar when something is moving, and up to two pill buttons that
 * work on the collapsed notification. Expanded, it shows the full text, a detail line, the progress
 * with a large percentage, and a picture; the system draws every action underneath, which is also
 * what the lock screen and watches show.
 *
 * Built on [Notification.DecoratedCustomViewStyle], so the system header (app name, time, expand
 * arrow) and the shade's own background stay in charge of light and dark.
 */
public object NotificationCard {

    /** The badge colour, by what the notification is about. */
    public enum class Tone(internal val badge: Int) {
        BRAND(R.drawable.notif_badge_brand),
        INCOMING(R.drawable.notif_badge_incoming),
        OUTGOING(R.drawable.notif_badge_outgoing),
        SUCCESS(R.drawable.notif_badge_success),
        ERROR(R.drawable.notif_badge_error),
        CLIP(R.drawable.notif_badge_clip),
    }

    /** The glyph on the badge. */
    public enum class Glyph(internal val res: Int) {
        DESKTOP(R.drawable.notif_glyph_desktop),
        DOWNLOAD(R.drawable.notif_glyph_download),
        UPLOAD(R.drawable.notif_glyph_upload),
        CHECK(R.drawable.notif_glyph_check),
        ERROR(R.drawable.notif_glyph_error),
        CLIPBOARD(R.drawable.notif_glyph_clipboard),
        LINK(R.drawable.notif_glyph_link),
        MAIL(R.drawable.notif_glyph_mail),
        KEY(R.drawable.notif_glyph_key),
        CALL(R.drawable.notif_glyph_call),
        IMAGE(R.drawable.notif_glyph_image),
        SEARCH(R.drawable.notif_glyph_search),
        COPY(R.drawable.notif_glyph_copy),
        OPEN(R.drawable.notif_glyph_open),
        CLOSE(R.drawable.notif_glyph_close),
        FOLDER(R.drawable.notif_glyph_folder),
        DISCONNECT(R.drawable.notif_glyph_link_off),
    }

    /**
     * A button. On the collapsed card it is a round [icon] (the first one in the Veyro gradient),
     * so the text keeps the width; [label] is its accessibility name and its text when expanded.
     */
    public data class Pill(val label: String, val intent: PendingIntent, val icon: Glyph = Glyph.OPEN)

    public data class Spec(
        val title: String,
        val text: String,
        val tone: Tone = Tone.BRAND,
        val glyph: Glyph = Glyph.DESKTOP,
        /** A second line when expanded: speed and time left, a folder, a countdown hint. */
        val meta: String? = null,
        /** Longer text for the expanded card; [text] when null. */
        val bigText: String? = null,
        /** 0..100, or null for no bar. */
        val progress: Int? = null,
        /** A moving bar with no percentage (connecting, verifying). */
        val indeterminate: Boolean = false,
        /** Replaces the badge on the collapsed card (an image's thumbnail). */
        val thumbnail: Bitmap? = null,
        /** Shown full width on the expanded card. */
        val picture: Bitmap? = null,
        /** On the collapsed card, at most two. */
        val pills: List<Pill> = emptyList(),
        /** Standard actions for the expanded card, lock screen and watches; [pills] when null. */
        val actions: List<Pill>? = null,
        /** Draw every button grey (a lone Cancel should not look like the thing to press). */
        val secondaryOnly: Boolean = false,
    )

    /** Applies [spec] to [builder]: views, style, icon, colour and actions. */
    public fun apply(context: Context, builder: Notification.Builder, spec: Spec): Notification.Builder {
        val small = RemoteViews(context.packageName, R.layout.notification_card_small).apply {
            fill(spec, big = false)
            spec.pills.getOrNull(0)?.let {
                pill(R.id.card_pill_1, it)
                if (spec.secondaryOnly) setInt(R.id.card_pill_1, "setBackgroundResource", R.drawable.notif_button)
            }
            spec.pills.getOrNull(1)?.let { pill(R.id.card_pill_2, it) }
            spec.thumbnail?.let {
                setImageViewBitmap(R.id.card_thumb, it)
                setViewVisibility(R.id.card_thumb, View.VISIBLE)
                setViewVisibility(R.id.card_badge, View.GONE)
                setViewVisibility(R.id.card_glyph, View.GONE)
            }
        }
        val big = RemoteViews(context.packageName, R.layout.notification_card_big).apply {
            fill(spec, big = true)
            spec.picture?.let {
                setImageViewBitmap(R.id.card_picture, it)
                setViewVisibility(R.id.card_picture, View.VISIBLE)
            }
        }
        builder
            .setSmallIcon(R.drawable.ic_stat_veyro)
            .setColor(context.getColor(R.color.veyro_notif_accent))
            // Plain title and text too: the lock screen, watches and accessibility read these.
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
        (spec.actions ?: spec.pills).forEach { builder.addAction(Notification.Action.Builder(null, it.label, it.intent).build()) }
        if (spec.progress != null || spec.indeterminate) {
            // Also as a standard progress, for surfaces that ignore custom views.
            builder.setProgress(100, spec.progress ?: 0, spec.progress == null)
        }
        return builder
    }

    private fun RemoteViews.fill(spec: Spec, big: Boolean) {
        setImageViewResource(R.id.card_badge, spec.tone.badge)
        setImageViewResource(R.id.card_glyph, spec.glyph.res)
        setTextViewText(R.id.card_title, spec.title)
        setTextViewText(R.id.card_text, if (big) spec.bigText ?: spec.text else spec.text)
        if (big && !spec.meta.isNullOrBlank()) {
            setTextViewText(R.id.card_meta, spec.meta)
            setViewVisibility(R.id.card_meta, View.VISIBLE)
        }
        if (spec.progress != null || spec.indeterminate) {
            setProgressBar(R.id.card_progress, 100, spec.progress ?: 0, spec.progress == null)
            setViewVisibility(R.id.card_progress, View.VISIBLE)
        }
        if (spec.progress != null) {
            setTextViewText(R.id.card_percent, "${spec.progress.coerceIn(0, 100)}%")
            setViewVisibility(R.id.card_percent, View.VISIBLE)
        }
    }

    private fun RemoteViews.pill(id: Int, pill: Pill) {
        setImageViewResource(id, pill.icon.res)
        // Dark glyph on both the gradient and the light-grey button: readable on any shade.
        setInt(id, "setColorFilter", ON_BUTTON)
        setContentDescription(id, pill.label)
        setOnClickPendingIntent(id, pill.intent)
        setViewVisibility(id, View.VISIBLE)
    }

    private const val ON_BUTTON = 0xFF04070F.toInt()
}
