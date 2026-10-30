package com.veyro.core.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.veyro.core.common.VeyroLog

/**
 * Holds a PC's use of the phone's camera in the foreground (ADR-067): type `camera`, started only
 * from Veyro's visible consent window (Android lets the camera run in the background only for a
 * foreground service started while the app was in front). Its notification says which PC uses
 * the camera and has Stop. No logic of its own; the host runs the session.
 */
public class VeyroCameraService : Service() {

    public companion object {
        private const val TAG = "CameraService"
        private const val ACTION_START = "com.veyro.action.CAMERA_START"
        private const val ACTION_STOP = "com.veyro.action.CAMERA_STOP"
        private const val ACTION_REFRESH = "com.veyro.action.CAMERA_REFRESH"
        private const val REQUEST_STOP = 50
        private const val REQUEST_OPEN = 51

        /** Call from a visible activity, after the camera permission was granted. */
        public fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, VeyroCameraService::class.java).setAction(ACTION_START))
            true
        } catch (e: RuntimeException) {
            VeyroLog.w(TAG) { "Camera service not started: ${e.javaClass.simpleName}" }
            false
        }

        public fun refresh(context: Context) {
            runCatching { context.startService(Intent(context, VeyroCameraService::class.java).setAction(ACTION_REFRESH)) }
        }

        public fun stop(context: Context) {
            context.stopService(Intent(context, VeyroCameraService::class.java))
        }
    }

    private lateinit var notifications: VeyroNotifications
    private var host: ScreenShareServiceHost? = null
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        notifications = VeyroNotifications(this).also { it.ensureChannels() }
        host = ScreenShareServiceHost.from(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!foreground) {
                try {
                    startForeground(VeyroNotifications.ID_CAMERA, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                    foreground = true
                } catch (e: RuntimeException) {
                    VeyroLog.w(TAG) { "Could not enter the foreground: ${e.javaClass.simpleName}" }
                    stopSelf()
                }
            }
            ACTION_REFRESH -> if (foreground) notifications.show(VeyroNotifications.ID_CAMERA, notification())
            ACTION_STOP -> host?.onCameraStopRequested()
            else -> if (!foreground) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification() = run {
        val (title, text) = host?.cameraNotice() ?: ("Streaming your camera" to "A PC is using this phone's camera.")
        notifications.screenSharing(
            title = title,
            text = text,
            stopIntent = PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, VeyroCameraService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
            contentIntent = host?.let {
                PendingIntent.getActivity(this, REQUEST_OPEN, it.openAppIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
