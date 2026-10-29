package com.veyro.di

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.veyro.core.service.NotificationCard
import com.veyro.core.service.VeyroNotifications
import com.veyro.MainActivity
import com.veyro.VeyroApplication
import com.veyro.core.common.VeyroLog
import com.veyro.core.companion.ClipItem
import com.veyro.core.companion.ClipNotifier
import com.veyro.core.companion.ClipClassifier
import kotlinx.coroutines.launch

/**
 * The single "Copied on <PC>" notification (ADR-043), also "From <phone>" for text another phone
 * sent with "Send with Veyro" (ADR-054). Every new clip replaces it in place and
 * only the first one since it was last cleared makes a sound or vibration, so a busy copy session
 * never floods the shade. Its actions follow what was copied ([ClipClassifier]): Open for a link,
 * Call for a phone number, Email for an address, Copy code for a one-time code, Copy always.
 */
internal class AndroidClipNotifier(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val peerName: suspend (String?) -> String,
    /** Settings: how long the card stays after the last copy; null keeps it until dismissed. */
    private val autoClearMs: () -> Long? = { VeyroNotifications.AUTO_CLEAR_MS },
) : ClipNotifier {

    private val manager by lazy {
        context.getSystemService(NotificationManager::class.java)!!.also {
            it.createNotificationChannel(
                NotificationChannel(SystemClipboard.CHANNEL, "Clipboard from your devices", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "What your PC copies and what other phones send, with Copy and Open buttons"
                    setSound(null, null)
                },
            )
        }
    }

    private var lastPostAt = 0L

    override fun show(item: ClipItem) {
        scope.launch { post(item, peerName(item.peerId)) }
    }

    private fun post(item: ClipItem, pcName: String) {
        // When the card has cleared itself, a later copy starts a fresh count.
        val clearAfter = autoClearMs()
        val now = System.currentTimeMillis()
        if (clearAfter != null && now - lastPostAt > clearAfter) SystemClipboard.unseen.set(0)
        lastPostAt = now
        val count = SystemClipboard.unseen.incrementAndGet()
        val smart = if (item.image != null) null else ClipClassifier.classify(item.text)
        // "copied on" for what a PC copied; "from" for what another phone sent on purpose (ADR-054).
        val where = if (item.sent) "from $pcName" else "copied on $pcName"
        val title = when (smart?.kind) {
            null -> "Image $where"
            ClipClassifier.Kind.LINK -> "Link $where"
            ClipClassifier.Kind.PHONE -> "Phone number $where"
            ClipClassifier.Kind.EMAIL -> "Email address $where"
            ClipClassifier.Kind.CODE -> "Code $where"
            ClipClassifier.Kind.TEXT -> if (item.sent) "From $pcName" else "Copied on $pcName"
        }
        val body = when (smart?.kind) {
            null -> "Tap Copy, then paste it in any app"
            ClipClassifier.Kind.LINK, ClipClassifier.Kind.PHONE, ClipClassifier.Kind.EMAIL, ClipClassifier.Kind.CODE -> smart.value
            ClipClassifier.Kind.TEXT -> smart.value.let { if (it.length > 300) it.take(300) + "..." else it }
        }

        // The action that fits what was copied. Null for plain text and images: Copy is enough.
        val primary: Pair<String, PendingIntent>? = when (smart?.kind) {
            ClipClassifier.Kind.LINK -> view(Uri.parse(smart.value))?.let { "Open" to it }
            ClipClassifier.Kind.PHONE -> "Call" to activity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + smart.value)), REQ_PRIMARY)
            ClipClassifier.Kind.EMAIL -> "Email" to activity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + smart.value)), REQ_PRIMARY)
            ClipClassifier.Kind.CODE -> "Copy code" to broadcast(SystemClipboard.ACTION_COPY_VALUE, item.id, REQ_PRIMARY)
            else -> null
        }
        val copy = broadcast(SystemClipboard.ACTION_COPY, item.id, REQ_COPY)
        val copyLabel = if (smart?.kind == ClipClassifier.Kind.CODE) "Copy all" else "Copy"
        val picture = item.image?.let(::preview)
        val shownTitle = if (count > 1) "$title  ·  $count new" else title

        val builder = Notification.Builder(context, SystemClipboard.CHANNEL)
            // Tapping the notification itself copies (the code, for a code); it does not open Veyro.
            .setContentIntent(if (smart?.kind == ClipClassifier.Kind.CODE) primary!!.second else copy)
            .setDeleteIntent(broadcast(SystemClipboard.ACTION_DISMISS, item.id, REQ_DISMISS))
            .setAutoCancel(true)
            // The heart of "one notification": replaced in place, alerting only the first time.
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_STATUS)
        // Gone that long after the last copy (each new copy restarts the clock), when turned on.
        clearAfter?.let { builder.setTimeoutAfter(it) }

        // The Veyro card (ADR-044/050): the clip, the buttons inline when collapsed, and the
        // standard actions (plus "All clips") when expanded or on the lock screen.
        val primaryIcon = when (smart?.kind) {
            ClipClassifier.Kind.PHONE -> NotificationCard.Glyph.CALL
            ClipClassifier.Kind.EMAIL -> NotificationCard.Glyph.MAIL
            ClipClassifier.Kind.CODE -> NotificationCard.Glyph.KEY
            else -> NotificationCard.Glyph.OPEN
        }
        val pills = listOfNotNull(
            primary?.let { NotificationCard.Pill(it.first, it.second, primaryIcon) },
            NotificationCard.Pill(copyLabel, copy, NotificationCard.Glyph.COPY),
        )
        NotificationCard.apply(
            context,
            builder,
            NotificationCard.Spec(
                title = shownTitle,
                text = body,
                meta = if (count > 1) "$count new, all in Veyro > Clipboard" else null,
                thumbnail = picture,
                picture = picture,
                pills = pills,
                actions = pills + NotificationCard.Pill("All clips", openVeyro()),
            ),
        )

        runCatching { manager.notify(SystemClipboard.NOTIFICATION_ID, builder.build()) }
            .onFailure { VeyroLog.w("Clipboard") { "Notification refused: ${it.javaClass.simpleName}" } }
    }

    /** Open for http(s) only: anything else copied is never launched from a notification. */
    private fun view(uri: Uri): PendingIntent? {
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
        return activity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE), REQ_PRIMARY)
    }

    private fun activity(intent: Intent, request: Int): PendingIntent = PendingIntent.getActivity(
        context,
        request,
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(action: String, id: String, request: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        request,
        Intent(context, ClipActionReceiver::class.java).setAction(action).putExtra(SystemClipboard.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openVeyro(): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_OPEN,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )


    /** A downsampled bitmap for the expanded notification; the original stays in the history. */
    private fun preview(bytes: ByteArray): android.graphics.Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= PREVIEW_PX || bounds.outHeight / (sample * 2) >= PREVIEW_PX) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private companion object {
        const val REQ_OPEN = 4301
        const val REQ_PRIMARY = 4302
        const val REQ_COPY = 4303
        const val REQ_DISMISS = 4304
        const val PREVIEW_PX = 512
    }
}

/** The notification's Copy, Copy code and dismiss. Not exported: only our PendingIntents reach it. */
public class ClipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SystemClipboard.ACTION_DISMISS) {
            SystemClipboard.unseen.set(0)
            return
        }
        if (intent.action != SystemClipboard.ACTION_COPY && intent.action != SystemClipboard.ACTION_COPY_VALUE) return
        val id = intent.getStringExtra(SystemClipboard.EXTRA_ID) ?: return
        val app = context.applicationContext as? VeyroApplication ?: return
        val item = app.container.clipboardBridge.history.value.firstOrNull { it.id == id } ?: return
        val copied = if (intent.action == SystemClipboard.ACTION_COPY_VALUE) {
            SystemClipboard.copyText(context, ClipClassifier.classify(item.text).value)
        } else {
            SystemClipboard.copy(context, item)
        }
        if (copied) {
            SystemClipboard.unseen.set(0)
            context.getSystemService(NotificationManager::class.java)?.cancel(SystemClipboard.NOTIFICATION_ID)
            // Android 13+ shows its own "copied" confirmation; below it, say so.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
