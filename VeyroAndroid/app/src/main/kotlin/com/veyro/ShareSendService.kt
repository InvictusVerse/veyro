package com.veyro

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.veyro.core.common.VeyroLog
import com.veyro.core.service.NotificationCard
import com.veyro.core.service.VeyroNotifications
import com.veyro.ui.format.formatEta
import com.veyro.ui.format.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sends files shared to Veyro without opening it (ADR-043).
 *
 * [ShareToPcActivity] starts this the moment the user picks Veyro, then finishes: the user stays in
 * the app they shared from. Two reasons it is a service and not just the app scope:
 *  - The read grant on a shared `content://` URI belongs to the component that received it. Passed
 *    on in this service's start intent, it lasts until the service stops itself for that start,
 *    i.e. for the whole transfer. A finished Activity's grant can be revoked mid-transfer.
 *  - A foreground service keeps the process from being frozen while the source app is in front,
 *    even with "Run in background" off.
 *
 * Its notification is a Veyro card (ADR-044): progress with speed and Cancel, then the outcome.
 * Started only from a visible Activity, which Android requires for a dataSync foreground service.
 */
public class ShareSendService : Service() {

    public companion object {
        private const val TAG = "ShareSend"
        private const val ID_PROGRESS = 4400
        private const val ID_RESULT = 4401
        private const val EXTRA_TARGET = "com.veyro.extra.TARGET"
        private const val ACTION_CANCEL = "com.veyro.action.SHARE_CANCEL"

        /**
         * Hands [uris] (with their read grant) to the service. [target]: a PC id, or null for the
         * default PC. False if Android refused to start the service; the caller then sends another way.
         */
        public fun start(context: Context, uris: List<Uri>, target: String?): Boolean {
            if (uris.isEmpty()) return true
            val clip = ClipData.newRawUri("files", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            val intent = Intent(context, ShareSendService::class.java)
                .putExtra(EXTRA_TARGET, target)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = clip
            return runCatching { context.startForegroundService(intent) }
                .onFailure { VeyroLog.w(TAG) { "Share service not started: ${it.javaClass.simpleName}" } }
                .isSuccess
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Channels are created here, before startForeground posts to one (a missing channel crashes). */
    private val cards by lazy { VeyroNotifications(this).also { it.ensureChannels() } }

    /** Starts still running; the service stops once the last one is done. */
    private var running = 0
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        cards
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            (application as VeyroApplication).container.cancelTransfer()
            return START_NOT_STICKY
        }
        // Promote first: Android allows only a few seconds.
        promote(progress("Preparing to send", "Reading the shared files", null, null))
        val clip = intent?.clipData
        val uris = clip?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty()
        if (uris.isEmpty()) {
            finishStart(startId)
            return START_NOT_STICKY
        }
        val target = intent?.getStringExtra(EXTRA_TARGET)
        running++
        lastStartId = startId
        scope.launch { send(uris, target, startId) }
        // Redeliver keeps this start (and the URI grant that came with it) until stopSelf(startId),
        // and resends it if the process dies mid-transfer.
        return START_REDELIVER_INTENT
    }

    private suspend fun send(uris: List<Uri>, target: String?, startId: Int) {
        val container = (application as VeyroApplication).container
        val progressJob = scope.launch {
            container.activeTransfer.collectLatest { t ->
                if (t == null || t.isTerminal) return@collectLatest
                val name = t.peerDisplayName ?: "your PC"
                val percent = if (t.totalBytes > 0) (t.bytesTransferred * 100 / t.totalBytes).toInt() else null
                val text = if (t.totalFiles > 1) "${t.currentFileIndex + 1} of ${t.totalFiles} · ${t.currentFileName}" else t.currentFileName
                val meta = listOfNotNull(
                    t.bytesPerSecond.takeIf { it > 0 }?.let(::formatSpeed),
                    formatEta(t.etaSeconds),
                ).joinToString("  ·  ").ifBlank { null }
                cards.show(ID_PROGRESS, progress("Sending to $name", text, percent, meta))
            }
        }
        try {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { SharedUris.describe(this@ShareSendService, it) } }
            if (files.isEmpty()) {
                result("Nothing to send", "The app did not share a readable file.", success = false)
                return
            }
            val summary = container.sendFilesAndWait(listOfNotNull(target), files)
            val what = if (files.size == 1) files.first().effectiveName else "${files.size} files"
            when {
                summary.targets.isEmpty() -> result("No PC paired", "Pair a PC in Veyro first.", success = false)
                summary.succeeded == summary.targets.size -> result("Sent $what", "To ${summary.targets.joinToString()}", success = true)
                summary.succeeded > 0 -> result("Sent $what to some PCs", "Open Veyro for details.", success = false)
                else -> result("Could not send $what", "Is ${summary.targets.joinToString()} on and on the same Wi-Fi?", success = false)
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            VeyroLog.w(TAG) { "Share send failed: ${t.javaClass.simpleName}" }
            result("Could not send", "Something went wrong. Try again from Veyro.", success = false)
        } finally {
            progressJob.cancel()
            running--
            finishStart(startId)
        }
    }

    private fun finishStart(startId: Int) {
        if (running > 0) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Releases the URI grants of every start up to the newest one.
        stopSelf(maxOf(startId, lastStartId))
    }

    private fun promote(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_PROGRESS, notification)
        }
    }

    private fun progress(title: String, text: String, percent: Int?, meta: String?): Notification =
        cards.progress(
            title = title,
            text = text,
            progressPercent = percent,
            cancelIntent = cancelIntent(),
            incoming = false,
            meta = meta,
        )

    private fun result(title: String, text: String, success: Boolean) {
        val open = PendingIntent.getActivity(
            this,
            4402,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = cards.result(
            title = title,
            text = text,
            success = success,
            autoClearMs = (application as VeyroApplication).container.notificationAutoClearMs.value,
            pills = if (success) emptyList() else listOf(NotificationCard.Pill("Open Veyro", open)),
            contentIntent = open,
        )
        runCatching { cards.show(ID_RESULT, notification) }
    }

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        4403,
        Intent(this, ShareSendService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
