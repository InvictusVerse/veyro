package com.veyro.di

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Size
import com.veyro.MainActivity
import com.veyro.VeyroApplication
import com.veyro.core.model.ActiveTransfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferStage
import com.veyro.core.service.VeyroNotificationCard
import com.veyro.core.service.VeyroNotifications
import com.veyro.ui.format.ReceivedFolders
import com.veyro.ui.format.formatBytes
import com.veyro.ui.format.formatEta
import com.veyro.ui.format.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Files coming from a PC, as Veyro cards (ADR-044), while the app is not on screen (the in-app
 * banner covers that case): progress with speed, time left and Cancel, then "Received ..." with a
 * thumbnail for a picture, Open and Show in folder; or what went wrong.
 *
 * Sends have their own notification from [com.veyro.ShareSendService]; picker sends run while the
 * app is open, under the banner.
 */
public class TransferNotifier(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transfers: () -> StateFlow<ActiveTransfer?>,
    private val appVisible: StateFlow<Boolean>,
    /** Settings: how long the result stays; null keeps it until dismissed. */
    private val autoClearMs: () -> Long? = { VeyroNotifications.AUTO_CLEAR_MS },
) {
    private companion object {
        const val PROGRESS_INTERVAL_MS = 700L
        const val REQ_CANCEL = 4501
        const val REQ_OPEN_FILE = 4502
        const val REQ_FOLDER = 4503
        const val REQ_OPEN_APP = 4504
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }

    private val cards by lazy { VeyroNotifications(context).also { it.ensureChannels() } }
    private var job: Job? = null
    private var lastPostAt = 0L
    private var lastPercent = -1
    private var finished: String? = null

    @Synchronized
    public fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(transfers(), appVisible) { t, visible -> t to visible }.collect { (t, visible) -> onUpdate(t, visible) }
        }
    }

    private suspend fun onUpdate(t: ActiveTransfer?, visible: Boolean) {
        if (t == null || t.direction != TransferDirection.RECEIVE) {
            cards.cancel(VeyroNotifications.ID_PROGRESS)
            return
        }
        if (t.isTerminal) {
            cards.cancel(VeyroNotifications.ID_PROGRESS)
            if (finished == t.transferId) return
            finished = t.transferId
            // On screen, the banner already says it; a notification would only repeat it.
            if (!visible) showResult(t)
            return
        }
        if (visible) {
            cards.cancel(VeyroNotifications.ID_PROGRESS)
            return
        }
        val percent = if (t.totalBytes > 0) (t.bytesTransferred * 100 / t.totalBytes).toInt().coerceIn(0, 100) else null
        val now = System.currentTimeMillis()
        // Android drops notification updates sent too fast; one every 0.7 s is smooth enough.
        if (percent == lastPercent && now - lastPostAt < PROGRESS_INTERVAL_MS * 3) return
        if (now - lastPostAt < PROGRESS_INTERVAL_MS && percent != 100) return
        lastPostAt = now
        lastPercent = percent ?: -1

        val name = t.peerDisplayName ?: "your PC"
        val text = when {
            t.stage == TransferStage.VERIFYING || t.stage == TransferStage.PUBLISHING -> "Checking and saving ${t.currentFileName}"
            t.totalFiles > 1 -> "${t.currentFileIndex + 1} of ${t.totalFiles} · ${t.currentFileName}"
            else -> t.currentFileName
        }
        val meta = listOfNotNull(
            "${formatBytes(t.bytesTransferred)} of ${formatBytes(t.totalBytes)}",
            t.bytesPerSecond.takeIf { it > 0 }?.let(::formatSpeed),
            formatEta(t.etaSeconds),
        ).joinToString("  ·  ")
        cards.show(
            VeyroNotifications.ID_PROGRESS,
            cards.progress(
                title = "Receiving from $name",
                text = text,
                progressPercent = percent.takeIf { t.stage == TransferStage.TRANSFERRING },
                cancelIntent = broadcast(TransferActionReceiver.ACTION_CANCEL, REQ_CANCEL),
                incoming = true,
                meta = meta,
                contentIntent = openApp(),
            ),
        )
    }

    private suspend fun showResult(t: ActiveTransfer) {
        val name = t.peerDisplayName ?: "your PC"
        val what = if (t.totalFiles == 1) t.currentFileName else "${t.totalFiles} files"
        when (t.stage) {
            TransferStage.COMPLETED -> {
                val folder = if (t.totalFiles == 1) ReceivedFolders.pathFor(t.currentFileName) else ReceivedFolders.ROOT
                val uri = t.destinationUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                val thumb = uri?.takeIf { t.totalFiles == 1 }?.let { thumbnail(it) }
                val pills = listOfNotNull(
                    uri?.takeIf { t.totalFiles == 1 }?.let { VeyroNotificationCard.Pill("Open", openFile(it), VeyroNotificationCard.Glyph.OPEN) },
                    VeyroNotificationCard.Pill("Show in folder", openFolder(folder), VeyroNotificationCard.Glyph.FOLDER),
                )
                cards.show(
                    VeyroNotifications.ID_RESULT,
                    cards.result(
                        title = "Received $what",
                        text = "From $name",
                        success = true,
                        meta = "Saved to $folder",
                        thumbnail = thumb,
                        pills = pills,
                        contentIntent = pills.first().intent,
                        autoClearMs = autoClearMs(),
                    ),
                )
            }
            TransferStage.FAILED -> cards.show(
                VeyroNotifications.ID_RESULT,
                cards.result(
                    title = "Could not receive $what",
                    text = t.errorMessage ?: "The connection to $name was lost.",
                    success = false,
                    pills = listOf(VeyroNotificationCard.Pill("Open Veyro", openApp())),
                    contentIntent = openApp(),
                    autoClearMs = autoClearMs(),
                ),
            )
            else -> Unit // Cancelled: the user did it, nothing to report.
        }
    }

    private suspend fun thumbnail(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.loadThumbnail(uri, Size(512, 512), null) }.getOrNull()
    }

    private fun openFile(uri: Uri): PendingIntent {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, REQ_OPEN_FILE, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The folder in the Files app. */
    private fun openFolder(relativeFolder: String): PendingIntent {
        val dir = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$relativeFolder")
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(dir, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, REQ_FOLDER, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_OPEN_APP,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(action: String, request: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        request,
        Intent(context, TransferActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Cancel on the receiving card. Not exported: only our PendingIntents reach it. */
public class TransferActionReceiver : BroadcastReceiver() {
    public companion object {
        public const val ACTION_CANCEL: String = "com.veyro.action.TRANSFER_CANCEL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        (context.applicationContext as? VeyroApplication)?.container?.cancelTransfer()
    }
}
