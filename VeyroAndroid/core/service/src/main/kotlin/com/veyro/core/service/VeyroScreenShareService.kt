package com.veyro.core.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.veyro.core.common.VeyroLog

/** What [VeyroScreenShareService] needs from the app (ADR-005: only `:app` wires the graph). */
public interface ScreenShareServiceHost {

    /** The service is in the foreground and holds [projection] for session [sid]. */
    public fun onProjection(sid: String, projection: MediaProjection)

    /** No projection for [sid]: the consent result was refused, or the service could not start. */
    public fun onProjectionFailed(sid: String)

    /** The notification's Stop. */
    public fun onStopRequested()

    /** The notification's title and text: which PC sees the screen, and whether it hears the phone. */
    public fun sharingNotice(): Pair<String, String>

    public fun openAppIntent(context: Context): Intent

    /** Implemented by the Application. */
    public interface Provider {
        public val screenShareHost: ScreenShareServiceHost
    }

    public companion object {
        public fun from(context: Context): ScreenShareServiceHost? =
            (context.applicationContext as? Provider)?.screenShareHost
    }
}

/**
 * Holds screen sharing in the foreground (ADR-066). Android 14+ requires a `mediaProjection`
 * foreground service before the consent result may be turned into a projection, so the consent
 * activity starts this service with the result, and the service enters the foreground first and
 * only then asks for the projection. With the phone's sound it also carries the `microphone`
 * type, without which Android records silence while Veyro is in the background; Veyro still
 * records only what apps play, never the microphone. Started only from Veyro's own visible
 * activity; holds no logic, the host runs the session.
 */
public class VeyroScreenShareService : Service() {

    public companion object {
        private const val TAG = "ScreenShareService"
        private const val ACTION_START = "com.veyro.action.SCREEN_SHARE_START"
        private const val ACTION_AUDIO = "com.veyro.action.SCREEN_SHARE_AUDIO"
        private const val ACTION_STOP = "com.veyro.action.SCREEN_SHARE_STOP"
        private const val ACTION_REFRESH = "com.veyro.action.SCREEN_SHARE_REFRESH"
        private const val EXTRA_SID = "sid"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_AUDIO = "audio"
        private const val REQUEST_STOP = 30
        private const val REQUEST_OPEN = 31

        /** Call from a visible activity with Android's consent result. */
        public fun start(context: Context, sid: String, resultCode: Int, data: Intent, audio: Boolean): Boolean = try {
            context.startForegroundService(
                Intent(context, VeyroScreenShareService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SID, sid)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data)
                    .putExtra(EXTRA_AUDIO, audio),
            )
            true
        } catch (e: RuntimeException) {
            VeyroLog.w(TAG) { "Screen share service not started: ${e.javaClass.simpleName}" }
            false
        }

        /** Adds the microphone type after the audio permission was granted. Call from a visible activity. */
        public fun enableAudio(context: Context): Boolean = send(context, ACTION_AUDIO)

        /** Shows the host's current notice. */
        public fun refresh(context: Context): Boolean = send(context, ACTION_REFRESH)

        public fun stop(context: Context) {
            context.stopService(Intent(context, VeyroScreenShareService::class.java))
        }

        private fun send(context: Context, action: String): Boolean = try {
            context.startService(Intent(context, VeyroScreenShareService::class.java).setAction(action))
            true
        } catch (e: RuntimeException) {
            VeyroLog.w(TAG) { "Screen share service not reached: ${e.javaClass.simpleName}" }
            false
        }
    }

    private lateinit var notifications: VeyroNotifications
    private var host: ScreenShareServiceHost? = null
    private var foreground = false
    private var audio = false

    override fun onCreate() {
        super.onCreate()
        notifications = VeyroNotifications(this).also { it.ensureChannels() }
        host = ScreenShareServiceHost.from(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = host
        when (intent?.action) {
            ACTION_START -> {
                val sid = intent.getStringExtra(EXTRA_SID)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                audio = intent.getBooleanExtra(EXTRA_AUDIO, false)
                if (host == null || sid == null || data == null || !enterForeground()) {
                    if (sid != null) host?.onProjectionFailed(sid)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val projection = try {
                    getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(code, data)
                } catch (e: RuntimeException) {
                    VeyroLog.w(TAG) { "No projection: ${e.javaClass.simpleName}" }
                    null
                }
                if (projection == null) {
                    host.onProjectionFailed(sid)
                    stopSelf()
                    return START_NOT_STICKY
                }
                host.onProjection(sid, projection)
            }
            ACTION_AUDIO -> if (foreground && !audio) {
                audio = true
                enterForeground()
            }
            ACTION_REFRESH -> if (foreground) show()
            ACTION_STOP -> host?.onStopRequested()
            else -> if (!foreground) {
                // A restart without a session (process death): nothing to share.
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        // Android 11+ only: before that, playback capture needs no microphone type.
        if (audio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return try {
            startForeground(VeyroNotifications.ID_SCREEN_SHARING, notification(), types)
            foreground = true
            true
        } catch (e: RuntimeException) {
            if (audio) {
                // Without the audio permission the microphone type is refused: share the screen alone.
                VeyroLog.w(TAG) { "Microphone type refused (${e.javaClass.simpleName}); screen only" }
                audio = false
                return enterForeground()
            }
            VeyroLog.w(TAG) { "Could not enter the foreground: ${e.javaClass.simpleName}" }
            false
        }
    }

    private fun show() {
        notifications.show(VeyroNotifications.ID_SCREEN_SHARING, notification())
    }

    private fun notification() = run {
        val (title, text) = host?.sharingNotice() ?: ("Sharing your screen" to "A PC is showing this phone's screen.")
        notifications.screenSharing(
            title = title,
            text = text,
            stopIntent = PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, VeyroScreenShareService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
            contentIntent = host?.let {
                PendingIntent.getActivity(
                    this,
                    REQUEST_OPEN,
                    it.openAppIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            },
        )
    }

    override fun onDestroy() {
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
