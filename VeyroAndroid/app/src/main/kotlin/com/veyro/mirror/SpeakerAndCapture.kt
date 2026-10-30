package com.veyro.mirror

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.veyro.MainActivity
import com.veyro.VeyroApplication
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.companion.CaptureOutcome
import com.veyro.core.companion.CompanionLink
import com.veyro.core.companion.PeerConnectionLink
import com.veyro.core.companion.SpeakerCaptureHost
import com.veyro.core.companion.SpeakerSession
import com.veyro.core.link.OutgoingFile
import com.veyro.core.link.SpeakerCaptureRules
import com.veyro.core.link.SpeakerStart
import com.veyro.core.link.StreamRules
import com.veyro.core.link.media.PcAudioSink
import com.veyro.core.link.media.Vms1SpeakerLink
import com.veyro.core.media.PcAudioPlayer
import com.veyro.core.protocol.media.Vms1
import com.veyro.core.service.VeyroNotifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's side of "Phone as speaker" and photo capture (ADR-073). The PC's sound plays
 * through [PcAudioPlayer] with a notification that has Stop. A photo request is a notification
 * that opens [CaptureActivity]; its result comes back through [finishCapture]. Names appear in
 * notifications only, never in logs.
 */
public class SpeakerAndCapture(
    context: Context,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val appVisible: StateFlow<Boolean>,
    private val localSubnets: suspend () -> List<Pair<String, Int>>,
) : SpeakerCaptureHost {

    internal companion object {
        const val ACTION_STOP_SPEAKER = "com.veyro.action.SPEAKER_STOP"
        const val ACTION_DECLINE_CAPTURE = "com.veyro.action.CAPTURE_DECLINE"
        const val EXTRA_RID = "rid"
        private const val REQUEST_SPEAKER_STOP = 60
        private const val REQUEST_CAPTURE_OPEN = 61
        private const val REQUEST_CAPTURE_DECLINE = 62
        private const val REQUEST_SPEAKER_OPEN = 63
    }

    private val app = context.applicationContext
    private val notifications by lazy { VeyroNotifications(app).also { it.ensureChannels() } }

    // ── Speaker ──────────────────────────────────────────────────────────────────────────

    private inner class Playing(val link: Vms1SpeakerLink, val player: PcAudioPlayer) : SpeakerSession {
        private val stopped = AtomicBoolean(false)
        override val ended: Deferred<String> get() = link.closed

        override fun stop(reason: String) {
            if (!stopped.compareAndSet(false, true)) return
            player.stop()
            scope.launch(dispatchers.io) { link.close(reason.takeUnless { it == "network" }) }
            notifications.cancel(VeyroNotifications.ID_SPEAKER)
            if (playing === this) playing = null
        }
    }

    @Volatile private var playing: Playing? = null

    override suspend fun startSpeaker(link: CompanionLink, start: SpeakerStart, pcName: String): SpeakerSession {
        val connection = (link as? PeerConnectionLink)?.connection ?: throw IOException("no session")
        val subnets = runCatching { localSubnets() }.getOrDefault(emptyList())
        val host = StreamRules.chooseDialHost(start.host, connection.remoteHost, subnets) ?: throw IOException("no address")
        val sid = StreamRules.sidBytes(start.sid) ?: throw IOException("bad sid")
        val player = PcAudioPlayer()
        val sink = object : PcAudioSink {
            override fun onAudioConfig(config: Vms1.AudioConfig) = player.configure(config)
            override fun onAudio(ptsUs: Long, samples: ByteArray) = player.push(samples)
        }
        val socket = try {
            Vms1SpeakerLink.dial(connection, host, start.port, sid, sink, scope, dispatchers)
        } catch (e: IOException) {
            player.stop()
            throw e
        }
        val session = Playing(socket, player)
        playing = session
        scope.launch { session.ended.await().let { session.stop(it) } }
        notifications.show(
            VeyroNotifications.ID_SPEAKER,
            notifications.screenSharing(
                title = "Playing $pcName's sound",
                text = "Through this phone's speaker or headphones. Stop ends it.",
                stopIntent = PendingIntent.getBroadcast(
                    app,
                    REQUEST_SPEAKER_STOP,
                    Intent(app, SpeakerCaptureReceiver::class.java).setAction(ACTION_STOP_SPEAKER),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
                contentIntent = PendingIntent.getActivity(
                    app,
                    REQUEST_SPEAKER_OPEN,
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ),
        )
        return session
    }

    internal fun stopSpeaker() {
        playing?.stop("user")
    }

    // ── Capture ──────────────────────────────────────────────────────────────────────────

    internal class PendingCapture(val rid: String, val mode: String, val pcName: String) {
        val result = CompletableDeferred<CaptureOutcome>()
    }

    @Volatile private var pending: PendingCapture? = null

    override suspend fun capture(rid: String, mode: String, pcName: String): CaptureOutcome {
        val request = PendingCapture(rid, mode, pcName)
        pending = request
        try {
            val open = CaptureActivity.intent(app, rid)
            val direct = appVisible.value && runCatching { app.startActivity(open) }.isSuccess
            if (!direct) {
                val scan = mode == SpeakerCaptureRules.MODE_SCAN
                notifications.show(
                    VeyroNotifications.ID_CAPTURE_REQUEST,
                    notifications.screenRequest(
                        title = if (scan) "$pcName wants to scan a document" else "$pcName wants to take a photo",
                        text = if (scan) "Tap Scan, take the page, adjust its corners and send it." else "Tap Take photo, then send it.",
                        shareLabel = if (scan) "Scan" else "Take photo",
                        shareIntent = PendingIntent.getActivity(app, REQUEST_CAPTURE_OPEN, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                        declineIntent = PendingIntent.getBroadcast(
                            app,
                            REQUEST_CAPTURE_DECLINE,
                            Intent(app, SpeakerCaptureReceiver::class.java).setAction(ACTION_DECLINE_CAPTURE).putExtra(EXTRA_RID, rid),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                        timeoutMs = SpeakerCaptureRules.CAPTURE_EXPIRY_MS,
                    ),
                )
            }
            return request.result.await()
        } finally {
            if (pending === request) pending = null
            notifications.cancel(VeyroNotifications.ID_CAPTURE_REQUEST)
        }
    }

    internal fun pendingCapture(rid: String?): PendingCapture? = pending?.takeIf { it.rid == rid }

    /** Where the camera app writes the photo for [rid]; [rid] is base64url, safe as a file name. */
    internal fun captureFile(rid: String, suffix: String): File =
        File(File(app.cacheDir, "captures").apply { mkdirs() }, "$rid$suffix.jpg")

    /** The capture screen finished: [file] holds the JPEG to send, or [reason] says why not. */
    internal fun finishCapture(rid: String, file: File?, reason: String = "cancelled") {
        val request = pendingCapture(rid) ?: return
        if (file == null || !file.isFile) {
            request.result.complete(CaptureOutcome.Failed(reason))
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = if (request.mode == SpeakerCaptureRules.MODE_SCAN) "Scan $stamp.jpg" else "Photo $stamp.jpg"
        VeyroLog.i("SpeakerCapture") { "Capture ready for the PC" }
        request.result.complete(
            CaptureOutcome.Taken(OutgoingFile(name, file.length(), file.lastModified()) { file.inputStream() }),
        )
    }

    /** Old captures (sent or not) leave the cache. */
    internal fun cleanCaptures(keep: String?) {
        File(app.cacheDir, "captures").listFiles()?.forEach { if (keep == null || !it.name.startsWith(keep)) it.delete() }
    }
}

/** Stop on the speaker notification and Decline on the capture request. Only our own PendingIntents. */
public class SpeakerCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? VeyroApplication ?: return
        val host = app.container.speakerAndCapture
        when (intent.action) {
            SpeakerAndCapture.ACTION_STOP_SPEAKER -> host.stopSpeaker()
            SpeakerAndCapture.ACTION_DECLINE_CAPTURE -> host.finishCapture(intent.getStringExtra(SpeakerAndCapture.EXTRA_RID) ?: return, null, "declined")
        }
    }
}
