package com.veyro.di

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.veyro.core.service.VeyroNotificationCard
import com.veyro.core.service.VeyroNotifications
import androidx.core.content.FileProvider
import com.veyro.core.companion.ClipboardBridge
import com.veyro.MainActivity
import com.veyro.VeyroApplication
import com.veyro.core.common.VeyroLog
import com.veyro.core.companion.ClipItem
import com.veyro.core.companion.ClipNotifier
import com.veyro.core.companion.ClipSmarts
import kotlinx.coroutines.launch
import java.io.File

/**
 * Android side of the clipboard (ADR-043): the "PC copied" notification with Copy, and writing
 * an item to the system clipboard. Writing is allowed from the background; only reading is not.
 */
internal object ClipboardSupport {
    private const val TAG = "Clipboard"
    const val CHANNEL = "veyro.clipboard"
    const val ACTION_COPY = "com.veyro.action.CLIP_COPY"
    /** Copies only the smart value (the code in "Your code is 123456"). */
    const val ACTION_COPY_VALUE = "com.veyro.action.CLIP_COPY_VALUE"
    const val ACTION_DISMISS = "com.veyro.action.CLIP_DISMISS"
    const val EXTRA_ID = "clipId"
    const val NOTIFICATION_ID = 4300

    /** PC copies since the notification was last cleared, for its "3 new" line. */
    val unseen = java.util.concurrent.atomic.AtomicInteger(0)

    fun copyText(context: Context, text: String): Boolean {
        if (text.isEmpty()) return false
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        return runCatching { manager.setPrimaryClip(ClipData.newPlainText("From PC", text)) }.isSuccess
    }

    /** Puts [item] on the phone's clipboard. Images go through a FileProvider URI. */
    fun copy(context: Context, item: ClipItem): Boolean {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = when {
            item.image != null -> {
                val dir = File(context.cacheDir, "clips").apply { mkdirs() }
                // One file at a time: the clipboard only ever holds one item.
                dir.listFiles()?.forEach { it.delete() }
                val png = item.image!![0] == 0x89.toByte()
                val file = File(dir, "clip-${item.id}.${if (png) "png" else "jpg"}")
                runCatching { file.writeBytes(item.image!!) }.getOrElse { return false }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.clips", file)
                ClipData.newUri(context.contentResolver, "Image from PC", uri)
            }
            item.text != null -> ClipData.newPlainText("From PC", item.text)
            else -> return false
        }
        return runCatching { manager.setPrimaryClip(clip) }
            .onFailure { VeyroLog.w(TAG) { "Copy failed: ${it.javaClass.simpleName}" } }
            .isSuccess
    }

    /** Reads the phone's clipboard. Only works while Veyro is in the foreground (Android 10+). */
    fun read(context: Context): Pair<String?, ByteArray?> {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null to null
        val clip = runCatching { manager.primaryClip }.getOrNull() ?: return null to null
        if (clip.itemCount == 0) return null to null
        val description = clip.description
        val first = clip.getItemAt(0)
        if (description.hasMimeType("image/*") && first.uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(first.uri)?.use { input ->
                    // Bounded: a huge image is re-encoded by fitImage, but must not exhaust memory.
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_READ_BYTES) return@use null
                    }
                    out.toByteArray()
                }
            }.getOrNull()
            if (bytes != null) return null to bytes
        }
        val text = runCatching { first.coerceToText(context)?.toString() }.getOrNull()
        return text to null
    }

    private const val MAX_READ_BYTES = 32 * 1024 * 1024

    /**
     * [bytes] as a PNG or JPEG within the protocol cap: as is when it already fits, otherwise
     * scaled down and re-encoded as JPEG. Null if it cannot be decoded or made to fit.
     */
    fun fitImage(bytes: ByteArray): ByteArray? {
        if (ClipboardBridge.isAcceptableImage(bytes)) return bytes
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
        var scaled = bitmap
        try {
            repeat(6) {
                val out = java.io.ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                val encoded = out.toByteArray()
                if (ClipboardBridge.isAcceptableImage(encoded)) return encoded
                val next = Bitmap.createScaledBitmap(scaled, (scaled.width * 0.7).toInt().coerceAtLeast(1), (scaled.height * 0.7).toInt().coerceAtLeast(1), true)
                if (scaled !== bitmap) scaled.recycle()
                scaled = next
            }
            return null
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }
}

/**
 * The single "Copied on <PC>" notification (ADR-043). Every new PC copy replaces it in place and
 * only the first one since it was last cleared makes a sound or vibration, so a busy copy session
 * never floods the shade. Its actions follow what was copied ([ClipSmarts]): Open for a link,
 * Call for a phone number, Email for an address, Copy code for a one-time code, Copy always.
 */
internal class AndroidClipNotifier(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val peerName: suspend (String?) -> String,
) : ClipNotifier {

    private val manager by lazy {
        context.getSystemService(NotificationManager::class.java)!!.also {
            it.createNotificationChannel(
                NotificationChannel(ClipboardSupport.CHANNEL, "Clipboard from your PC", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "What your PC copies, with Copy and Open buttons"
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
        // The card clears itself after 5 minutes; a copy after that starts a fresh count.
        val now = System.currentTimeMillis()
        if (now - lastPostAt > VeyroNotifications.AUTO_CLEAR_MS) ClipboardSupport.unseen.set(0)
        lastPostAt = now
        val count = ClipboardSupport.unseen.incrementAndGet()
        val smart = if (item.image != null) null else ClipSmarts.classify(item.text)
        val title = when (smart?.kind) {
            null -> "Image copied on $pcName"
            ClipSmarts.Kind.LINK -> "Link copied on $pcName"
            ClipSmarts.Kind.PHONE -> "Phone number copied on $pcName"
            ClipSmarts.Kind.EMAIL -> "Email address copied on $pcName"
            ClipSmarts.Kind.CODE -> "Code copied on $pcName"
            ClipSmarts.Kind.TEXT -> "Copied on $pcName"
        }
        val body = when (smart?.kind) {
            null -> "Tap Copy, then paste it in any app"
            ClipSmarts.Kind.LINK, ClipSmarts.Kind.PHONE, ClipSmarts.Kind.EMAIL, ClipSmarts.Kind.CODE -> smart.value
            ClipSmarts.Kind.TEXT -> smart.value.let { if (it.length > 300) it.take(300) + "..." else it }
        }

        // The action that fits what was copied. Null for plain text and images: Copy is enough.
        val primary: Pair<String, PendingIntent>? = when (smart?.kind) {
            ClipSmarts.Kind.LINK -> view(Uri.parse(smart.value))?.let { "Open" to it }
            ClipSmarts.Kind.PHONE -> "Call" to activity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + smart.value)), REQ_PRIMARY)
            ClipSmarts.Kind.EMAIL -> "Email" to activity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + smart.value)), REQ_PRIMARY)
            ClipSmarts.Kind.CODE -> "Copy code" to broadcast(ClipboardSupport.ACTION_COPY_VALUE, item.id, REQ_PRIMARY)
            else -> null
        }
        val copy = broadcast(ClipboardSupport.ACTION_COPY, item.id, REQ_COPY)
        val copyLabel = if (smart?.kind == ClipSmarts.Kind.CODE) "Copy all" else "Copy"
        val picture = item.image?.let(::preview)
        val shownTitle = if (count > 1) "$title  ·  $count new" else title

        val builder = Notification.Builder(context, ClipboardSupport.CHANNEL)
            // Tapping the notification itself copies (the code, for a code); it does not open Veyro.
            .setContentIntent(if (smart?.kind == ClipSmarts.Kind.CODE) primary!!.second else copy)
            .setDeleteIntent(broadcast(ClipboardSupport.ACTION_DISMISS, item.id, REQ_DISMISS))
            .setAutoCancel(true)
            // The heart of "one notification": replaced in place, alerting only the first time.
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_STATUS)
            // Gone 5 minutes after the last copy (each new copy restarts the clock).
            .setTimeoutAfter(VeyroNotifications.AUTO_CLEAR_MS)

        // The Veyro card (ADR-044): violet badge with what was copied, the buttons inline when
        // collapsed, and the standard actions (plus "All clips") when expanded or on the lock screen.
        val primaryIcon = when (smart?.kind) {
            ClipSmarts.Kind.PHONE -> VeyroNotificationCard.Glyph.CALL
            ClipSmarts.Kind.EMAIL -> VeyroNotificationCard.Glyph.MAIL
            ClipSmarts.Kind.CODE -> VeyroNotificationCard.Glyph.KEY
            else -> VeyroNotificationCard.Glyph.OPEN
        }
        val pills = listOfNotNull(
            primary?.let { VeyroNotificationCard.Pill(it.first, it.second, primaryIcon) },
            VeyroNotificationCard.Pill(copyLabel, copy, VeyroNotificationCard.Glyph.COPY),
        )
        VeyroNotificationCard.apply(
            context,
            builder,
            VeyroNotificationCard.Spec(
                title = shownTitle,
                text = body,
                tone = VeyroNotificationCard.Tone.CLIP,
                glyph = when (smart?.kind) {
                    null -> VeyroNotificationCard.Glyph.IMAGE
                    ClipSmarts.Kind.LINK -> VeyroNotificationCard.Glyph.LINK
                    ClipSmarts.Kind.PHONE -> VeyroNotificationCard.Glyph.CALL
                    ClipSmarts.Kind.EMAIL -> VeyroNotificationCard.Glyph.MAIL
                    ClipSmarts.Kind.CODE -> VeyroNotificationCard.Glyph.KEY
                    ClipSmarts.Kind.TEXT -> VeyroNotificationCard.Glyph.CLIPBOARD
                },
                meta = if (count > 1) "$count new from your PC, all in Veyro > Clipboard" else null,
                thumbnail = picture,
                picture = picture,
                pills = pills,
                actions = pills + VeyroNotificationCard.Pill("All clips", openVeyro()),
            ),
        )

        runCatching { manager.notify(ClipboardSupport.NOTIFICATION_ID, builder.build()) }
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
        Intent(context, ClipActionReceiver::class.java).setAction(action).putExtra(ClipboardSupport.EXTRA_ID, id),
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
        if (intent.action == ClipboardSupport.ACTION_DISMISS) {
            ClipboardSupport.unseen.set(0)
            return
        }
        if (intent.action != ClipboardSupport.ACTION_COPY && intent.action != ClipboardSupport.ACTION_COPY_VALUE) return
        val id = intent.getStringExtra(ClipboardSupport.EXTRA_ID) ?: return
        val app = context.applicationContext as? VeyroApplication ?: return
        val item = app.container.clipboardBridge.history.value.firstOrNull { it.id == id } ?: return
        val copied = if (intent.action == ClipboardSupport.ACTION_COPY_VALUE) {
            ClipboardSupport.copyText(context, ClipSmarts.classify(item.text).value)
        } else {
            ClipboardSupport.copy(context, item)
        }
        if (copied) {
            ClipboardSupport.unseen.set(0)
            context.getSystemService(NotificationManager::class.java)?.cancel(ClipboardSupport.NOTIFICATION_ID)
            // Android 13+ shows its own "copied" confirmation; below it, say so.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
