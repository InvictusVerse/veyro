package com.veyro.di

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import com.veyro.core.companion.ClipboardBridge
import com.veyro.core.common.VeyroLog
import com.veyro.core.companion.ClipItem
import java.io.File

/**
 * The phone's system clipboard (ADR-043): writing an item (text, or an image through a
 * FileProvider URI) and reading it for "Send clipboard to PC". Writing is allowed from the
 * background; reading only while Veyro is in front. The notification is in ClipboardNotifier.kt.
 */
internal object SystemClipboard {
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
