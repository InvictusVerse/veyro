package com.veyro.mirror

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import com.veyro.MainActivity
import com.veyro.VeyroApplication
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.companion.CompanionLink
import com.veyro.core.companion.PeerConnectionLink
import com.veyro.core.companion.ScreenGrant
import com.veyro.core.companion.ScreenSession
import com.veyro.core.companion.ScreenShareHost
import com.veyro.core.link.StreamCamera
import com.veyro.core.link.StreamCaps
import com.veyro.core.link.StreamEncoder
import com.veyro.core.link.StreamSize
import com.veyro.core.media.CameraCatalog
import com.veyro.core.protocol.media.Vms1
import com.veyro.core.service.VeyroCameraService
import com.veyro.core.link.StreamRules
import com.veyro.core.link.StreamScreen
import com.veyro.core.link.StreamStart
import com.veyro.core.link.media.Vms1Uplink
import com.veyro.core.media.EncoderCatalog
import com.veyro.core.service.ScreenShareServiceHost
import com.veyro.core.service.VeyroNotifications
import com.veyro.core.service.VeyroScreenShareService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

/**
 * The phone's screen sharing on Android (ADR-066): builds `streamCaps`, asks the phone's user
 * (a notification, because Android blocks activity starts from the background, then Android's
 * dialog in [MirrorConsentActivity]), turns the consent into a projection through
 * [VeyroScreenShareService], and runs one [PhoneScreenSession]. "Ask again" for the sound from
 * the PC arrives here as a notification that opens Android's permission dialog, or Veyro's App
 * info once Android no longer shows it. One request and one session at a time (the bridge
 * guards that). Names appear only in notifications, never in logs.
 */
public class ScreenShareCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val appVisible: StateFlow<Boolean>,
    /** The phone's own IPv4 addresses with prefix lengths, for choosing the dial address. */
    private val localSubnets: suspend () -> List<Pair<String, Int>>,
) : ScreenShareHost, ScreenShareServiceHost {

    internal companion object {
        private const val TAG = "ScreenShareCoordinator"
        const val ACTION_DECLINE = "com.veyro.action.SCREEN_SHARE_DECLINE"
        const val ACTION_DECLINE_CAMERA = "com.veyro.action.CAMERA_DECLINE"
        const val EXTRA_SID = "sid"
        private const val REQUEST_SHARE = 40
        private const val REQUEST_DECLINE = 41
        private const val REQUEST_AUDIO = 42
        private const val REQUEST_CAMERA = 43
        private const val REQUEST_CAMERA_DECLINE = 44
    }

    private val app = context.applicationContext
    private val catalog = EncoderCatalog()
    internal val audioState = AudioPermissionState(app)
    private val notifications by lazy { VeyroNotifications(app).also { it.ensureChannels() } }
    private val answered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** One consent waiting for the phone's user. */
    internal class Pending(val sid: String, val pcName: String, val audio: Boolean) {
        val result = CompletableDeferred<MediaProjection?>()
    }

    @Volatile private var pending: Pending? = null
    @Volatile private var session: PhoneScreenSession? = null
    @Volatile private var sessionPcName: String = ""

    override val capsChanges: Flow<Unit> = merge(
        answered,
        // Rotation changes the screen's size; refresh-rate switches (frequent on 120 Hz panels) do not.
        displayChanges().mapNotNull { readScreen() }.distinctUntilChanged().drop(1).map { },
    ).conflate()

    override suspend fun caps(allowed: Boolean, cameraAllowed: Boolean): StreamCaps = withContext(dispatchers.io) {
        val encoders = catalog.summary().take(16).map { StreamEncoder(it.codec, it.hw, it.maxW, it.maxH, it.fps) }
        val cameraList = if (cameraAllowed) {
            cameraCatalog.all.take(8).map { c -> StreamCamera(c.id, c.facing, c.sizes.map { StreamSize(it.w, it.h, it.fps) }) }
        } else {
            null
        }
        val screen = if (allowed) readScreen() else null
        if (screen == null) {
            return@withContext StreamCaps(
                allowed = false,
                encoders = if (cameraList.isNullOrEmpty()) emptyList() else encoders,
                cameraAllowed = cameraAllowed,
                cameras = cameraList,
            )
        }
        StreamCaps(
            allowed = true,
            screen = StreamScreen(screen.first, screen.second, maxRefreshRate()),
            encoders = encoders,
            // Android 10+ (Veyro's minimum) has playback capture.
            audio = true,
            audioPermission = audioState.current(),
            cameraAllowed = cameraAllowed,
            cameras = cameraList,
        )
    }

    // ── Camera (ADR-067) ─────────────────────────────────────────────────────────────────────

    private val cameraCatalog by lazy { CameraCatalog(app, catalog) }

    /** One camera request waiting for the phone's user. */
    internal class PendingCamera(val sid: String, val pcName: String) {
        val result = CompletableDeferred<Boolean>()
    }

    @Volatile private var pendingCamera: PendingCamera? = null
    @Volatile private var cameraSession: PhoneCameraSession? = null
    @Volatile private var cameraPcName: String = ""

    override suspend fun requestCameraConsent(sid: String, pcName: String): ScreenGrant? {
        val request = PendingCamera(sid, pcName)
        pendingCamera = request
        try {
            val open = MirrorConsentActivity.cameraIntent(app, sid)
            val direct = appVisible.value && runCatching { app.startActivity(open) }.isSuccess
            if (!direct) {
                notifications.show(
                    VeyroNotifications.ID_CAMERA_REQUEST,
                    notifications.screenRequest(
                        title = "$pcName wants to use your camera",
                        text = "Tap Allow to start. Only that PC sees it, until you stop.",
                        shareLabel = "Allow",
                        shareIntent = PendingIntent.getActivity(app, REQUEST_CAMERA, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                        declineIntent = PendingIntent.getBroadcast(
                            app,
                            REQUEST_CAMERA_DECLINE,
                            Intent(app, ScreenShareActionReceiver::class.java).setAction(ACTION_DECLINE_CAMERA).putExtra(EXTRA_SID, sid),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                        timeoutMs = com.veyro.core.companion.ScreenShareBridge.CONSENT_TIMEOUT_MS,
                    ),
                )
            }
            return if (request.result.await()) CameraGrant(request) else null
        } finally {
            if (pendingCamera === request) pendingCamera = null
            notifications.cancel(VeyroNotifications.ID_CAMERA_REQUEST)
        }
    }

    internal fun pendingCameraRequest(sid: String?): PendingCamera? = pendingCamera?.takeIf { it.sid == sid }

    internal fun declineCamera(sid: String?) {
        notifications.cancel(VeyroNotifications.ID_CAMERA_REQUEST)
        pendingCameraRequest(sid)?.result?.complete(false)
    }

    /** Allowed with the camera permission held: the service enters the foreground from the visible window. */
    internal fun cameraGranted(activity: Context, sid: String) {
        val request = pendingCameraRequest(sid) ?: return
        request.result.complete(VeyroCameraService.start(activity))
    }

    internal fun refreshCameraNotice() {
        VeyroCameraService.refresh(app)
    }

    internal fun onCameraSessionEnded(ended: PhoneCameraSession) {
        if (cameraSession !== ended) return
        cameraSession = null
        VeyroCameraService.stop(app)
    }

    override fun cameraNotice(): Pair<String, String> {
        val name = cameraPcName.ifBlank { "your PC" }
        val lens = when (cameraSession?.pcFacing) {
            "front" -> "front camera"
            "back" -> "back camera"
            else -> "camera"
        }
        return "$name is using your $lens" to "Stop ends it. Nothing is recorded on the phone."
    }

    override fun onCameraStopRequested() {
        cameraSession?.stop("user") ?: VeyroCameraService.stop(app)
    }

    private inner class CameraGrant(private val request: PendingCamera) : ScreenGrant {
        private var used = false

        override suspend fun start(link: CompanionLink, start: StreamStart): ScreenSession {
            used = true
            try {
                val connection = (link as? PeerConnectionLink)?.connection ?: throw IOException("no session")
                val subnets = runCatching { localSubnets() }.getOrDefault(emptyList())
                val host = StreamRules.chooseDialHost(start.host, connection.remoteHost, subnets) ?: throw IOException("no address")
                val sid = StreamRules.sidBytes(start.sid) ?: throw IOException("bad sid")
                val uplink = Vms1Uplink.dial(connection, host, start.port, sid, scope, dispatchers, Vms1.SOURCE_CAMERA)
                val created = PhoneCameraSession(app, catalog, cameraCatalog, uplink, start, this@ScreenShareCoordinator, scope, dispatchers)
                cameraSession = created
                cameraPcName = request.pcName
                if (!created.begin()) {
                    created.stop("unsupported")
                    throw IllegalStateException("camera did not start")
                }
                refreshCameraNotice()
                return created
            } catch (e: Exception) {
                if (cameraSession == null) VeyroCameraService.stop(app)
                throw e
            }
        }

        override fun release() {
            if (used) return
            used = true
            if (cameraSession == null) VeyroCameraService.stop(app)
        }
    }

    override suspend fun requestConsent(sid: String, pcName: String, audio: Boolean): ScreenGrant? {
        val request = Pending(sid, pcName, audio)
        pending = request
        try {
            showRequest(request)
            val projection = request.result.await() ?: return null
            return Grant(projection, request)
        } finally {
            if (pending === request) pending = null
            notifications.cancel(VeyroNotifications.ID_SCREEN_REQUEST)
        }
    }

    private fun showRequest(request: Pending) {
        val open = MirrorConsentActivity.screenIntent(app, request.sid)
        if (appVisible.value) {
            // Veyro is on screen: Android allows the dialog straight away.
            if (runCatching { app.startActivity(open) }.isSuccess) return
        }
        notifications.show(
            VeyroNotifications.ID_SCREEN_REQUEST,
            notifications.screenRequest(
                title = "${request.pcName} wants to show your screen",
                text = if (request.audio) {
                    "Tap Share, then Start now. The PC can also play this phone's sound."
                } else {
                    "Tap Share, then Start now. Only that PC sees it, until you stop."
                },
                shareLabel = "Share",
                shareIntent = PendingIntent.getActivity(app, REQUEST_SHARE, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                declineIntent = PendingIntent.getBroadcast(
                    app,
                    REQUEST_DECLINE,
                    Intent(app, ScreenShareActionReceiver::class.java).setAction(ACTION_DECLINE).putExtra(EXTRA_SID, request.sid),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
                timeoutMs = com.veyro.core.companion.ScreenShareBridge.CONSENT_TIMEOUT_MS,
            ),
        )
    }

    // ── Called by MirrorConsentActivity ──────────────────────────────────────────────────────

    /** The request [sid] is still waiting; null when it was answered, withdrawn or timed out. */
    internal fun pendingRequest(sid: String?): Pending? = pending?.takeIf { it.sid == sid }

    internal fun decline(sid: String?) {
        notifications.cancel(VeyroNotifications.ID_SCREEN_REQUEST)
        pendingRequest(sid)?.result?.complete(null)
    }

    /** Android's dialog said yes: the service turns [data] into a projection (Android 14+ order). */
    internal fun granted(activity: Context, sid: String, resultCode: Int, data: Intent) {
        val request = pendingRequest(sid) ?: return
        val audio = request.audio && audioState.granted()
        if (!VeyroScreenShareService.start(activity, sid, resultCode, data, audio)) request.result.complete(null)
    }

    /** Android's audio dialog was answered (also from App info). */
    internal fun audioAnswered(granted: Boolean, showRationale: Boolean) {
        audioState.record(granted, showRationale)
        answered.tryEmit(Unit)
    }

    /** The audio permission may have changed while a session runs; [visibleActivity] lets the service add its microphone type. */
    internal fun audioRecheck(visibleActivity: Context) {
        answered.tryEmit(Unit)
        notifications.cancel(VeyroNotifications.ID_SCREEN_AUDIO)
        val current = session ?: return
        if (audioState.granted() && current.wantsAudio) VeyroScreenShareService.enableAudio(visibleActivity)
        current.onAudioAnswered()
    }

    internal fun wantsAudio(sid: String?): Boolean = session?.takeIf { sid == null || sessionSid == sid }?.wantsAudio == true

    @Volatile private var sessionSid: String? = null

    // ── Called by PhoneScreenSession ─────────────────────────────────────────────────────────

    /** The PC's Ask again (or sound turned back on): a notification, or the dialog if Veyro is on screen. */
    internal fun askAudio(from: PhoneScreenSession) {
        if (session !== from) return
        if (audioState.granted()) {
            from.onAudioAnswered()
            return
        }
        val sid = sessionSid ?: return
        val blocked = audioState.current() == StreamRules.AUDIO_BLOCKED
        val open = MirrorConsentActivity.audioIntent(app, sid)
        if (appVisible.value && runCatching { app.startActivity(open) }.isSuccess) return
        notifications.show(
            VeyroNotifications.ID_SCREEN_AUDIO,
            notifications.screenRequest(
                title = "Play this phone's sound on $sessionPcName?",
                text = if (blocked) {
                    "Android will not ask again. Open settings > Permissions > Microphone > Allow. " +
                        "Veyro records only what apps play, never the microphone."
                } else {
                    "Android asks for its Microphone permission for this. Veyro records only what apps play, never the microphone."
                },
                shareLabel = if (blocked) "Open settings" else "Allow",
                shareIntent = PendingIntent.getActivity(app, REQUEST_AUDIO, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                declineIntent = null,
                timeoutMs = 120_000L,
            ),
        )
    }

    internal fun ensureAudioService() {
        // Only possible while Veyro is on screen (Android 14's while-in-use rule); otherwise the
        // next Ask again or App info visit adds it.
        if (appVisible.value) VeyroScreenShareService.enableAudio(app)
    }

    internal fun refreshNotice() {
        VeyroScreenShareService.refresh(app)
    }

    internal fun onSessionEnded(ended: PhoneScreenSession) {
        if (session !== ended) return
        session = null
        sessionSid = null
        notifications.cancel(VeyroNotifications.ID_SCREEN_AUDIO)
        VeyroScreenShareService.stop(app)
    }

    // ── ScreenShareServiceHost ───────────────────────────────────────────────────────────────

    override fun onProjection(sid: String, projection: MediaProjection) {
        val request = pendingRequest(sid)
        if (request == null || !request.result.complete(projection)) {
            // Withdrawn or timed out while Android asked: give it back.
            runCatching { projection.stop() }
            if (session == null) VeyroScreenShareService.stop(app)
        }
    }

    override fun onProjectionFailed(sid: String) {
        pendingRequest(sid)?.result?.complete(null)
    }

    override fun onStopRequested() {
        session?.stop("user") ?: VeyroScreenShareService.stop(app)
    }

    override fun sharingNotice(): Pair<String, String> {
        val name = sessionPcName.ifBlank { "your PC" }
        val sound = session?.state?.value?.audio == true
        return "Sharing your screen with $name" to
            if (sound) "The PC also plays this phone's sound. Stop ends it." else "Only that PC sees it. Stop ends it."
    }

    override fun openAppIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    // ── The consent, used once ───────────────────────────────────────────────────────────────

    private inner class Grant(private val projection: MediaProjection, private val request: Pending) : ScreenGrant {
        private var used = false

        override suspend fun start(link: CompanionLink, start: StreamStart): ScreenSession {
            used = true
            try {
                val connection = (link as? PeerConnectionLink)?.connection ?: throw IOException("no session")
                val subnets = runCatching { localSubnets() }.getOrDefault(emptyList())
                val host = StreamRules.chooseDialHost(start.host, connection.remoteHost, subnets) ?: throw IOException("no address")
                val sid = StreamRules.sidBytes(start.sid) ?: throw IOException("bad sid")
                val uplink = Vms1Uplink.dial(connection, host, start.port, sid, scope, dispatchers)
                val created = PhoneScreenSession(app, projection, catalog, uplink, start, audioState, this@ScreenShareCoordinator, scope, dispatchers)
                session = created
                sessionSid = start.sid
                sessionPcName = request.pcName
                if (!created.begin()) {
                    created.stop("encoder")
                    throw IllegalStateException("no encoder started")
                }
                refreshNotice()
                return created
            } catch (e: Exception) {
                if (session == null) {
                    runCatching { projection.stop() }
                    VeyroScreenShareService.stop(app)
                }
                throw e
            }
        }

        override fun release() {
            if (used) return
            used = true
            runCatching { projection.stop() }
            if (session == null) VeyroScreenShareService.stop(app)
        }
    }

    // ── The screen ───────────────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION") // getRealMetrics follows rotation without a visual context.
    private fun readScreen(): Pair<Int, Int>? {
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return (metrics.widthPixels to metrics.heightPixels).takeIf { it.first >= 16 && it.second >= 16 }
    }

    private fun maxRefreshRate(): Int {
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return 60
        val best = display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
        return best.roundToInt().coerceIn(1, 240)
    }

    private fun displayChanges(): Flow<Unit> = callbackFlow {
        val manager = app.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) trySend(Unit)
            }
        }
        trySend(Unit)
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        awaitClose { manager?.unregisterDisplayListener(listener) }
    }
}

/** Decline on the request notification. Not exported: only Veyro's own PendingIntents reach it. */
public class ScreenShareActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? VeyroApplication ?: return
        val sid = intent.getStringExtra(ScreenShareCoordinator.EXTRA_SID)
        when (intent.action) {
            ScreenShareCoordinator.ACTION_DECLINE -> app.container.screenShareCoordinator.decline(sid)
            ScreenShareCoordinator.ACTION_DECLINE_CAMERA -> app.container.screenShareCoordinator.declineCamera(sid)
            else -> return
        }
        VeyroLog.i("ScreenShareCoordinator") { "Request declined from the notification" }
    }
}
