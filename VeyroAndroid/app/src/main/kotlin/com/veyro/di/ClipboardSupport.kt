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
import android.os.Build
import androidx.core.content.FileProvider
import com.veyro.core.companion.ClipboardBridge
import com.veyro.MainActivity
import com.veyro.VeyroApplication
import com.veyro.core.common.VeyroLog
import com.veyro.core.companion.ClipItem
import com.veyro.core.companion.ClipNotifier
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
    const val EXTRA_ID = "clipId"

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

/** Posts "Copied on <PC>" with a Copy action (ADR-043). */
internal class AndroidClipNotifier(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val peerName: suspend (String?) -> String,
) : ClipNotifier {

    private val manager by lazy {
        context.getSystemService(NotificationManager::class.java)!!.also {
            it.createNotificationChannel(
                NotificationChannel(ClipboardSupport.CHANNEL, "Clipboard from your PC", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "What your PC copies, with a Copy button"
                    setSound(null, null)
                },
            )
        }
    }

    override fun show(item: ClipItem) {
        scope.launch { post(item, peerName(item.peerId)) }
    }

    private fun post(item: ClipItem, pcName: String) {
        val preview = when {
            item.image != null -> "Image"
            else -> item.text.orEmpty().let { if (it.length > 200) it.take(200) + "..." else it }
        }
        val copy = PendingIntent.getBroadcast(
            context,
            item.id.hashCode(),
            Intent(context, ClipActionReceiver::class.java)
                .setAction(ClipboardSupport.ACTION_COPY)
                .putExtra(ClipboardSupport.EXTRA_ID, item.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context,
            4301,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, ClipboardSupport.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Copied on $pcName")
            .setContentText(preview)
            .setStyle(Notification.BigTextStyle().bigText(preview))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Copy", copy).build())
            .build()
        runCatching { manager.notify(4300, notification) }
            .onFailure { VeyroLog.w("Clipboard") { "Notification refused: ${it.javaClass.simpleName}" } }
    }
}

/** The notification's Copy. Not exported: only our PendingIntents reach it. */
public class ClipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ClipboardSupport.ACTION_COPY) return
        val id = intent.getStringExtra(ClipboardSupport.EXTRA_ID) ?: return
        val app = context.applicationContext as? VeyroApplication ?: return
        val item = app.container.clipboardBridge.history.value.firstOrNull { it.id == id } ?: return
        if (ClipboardSupport.copy(context, item)) {
            context.getSystemService(NotificationManager::class.java)?.cancel(4300)
            // Android 13+ shows its own "copied" confirmation; below it, say so.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
