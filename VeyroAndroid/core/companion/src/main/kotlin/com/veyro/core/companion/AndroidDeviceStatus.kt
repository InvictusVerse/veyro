package com.veyro.core.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.link.LinkMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * [DeviceStatusSource] from the sticky battery broadcast, `AudioManager` and `NotificationManager`.
 *
 * `ACTION_BATTERY_CHANGED` fires on every voltage or temperature tick; [DeviceStatusPublisher]
 * only sends when the resulting message differs, so that is a whole-point battery move or a
 * charging / ringer / DND change.
 */
public class AndroidDeviceStatusSource(context: Context) : DeviceStatusSource {

    private val appContext: Context = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val notificationManager: NotificationManager? =
        appContext.getSystemService(NotificationManager::class.java)

    override val changes: Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        // System broadcasts only, so the receiver need not be exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        awaitClose { runCatching { appContext.unregisterReceiver(receiver) } }
    }

    override suspend fun current(): LinkMessage.DeviceStatus {
        // A null receiver returns the sticky intent without registering anything.
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        val charging = battery?.let {
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }
        val ringer = when (audioManager?.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> DeviceStatusPublisher.RINGER_NORMAL
            AudioManager.RINGER_MODE_VIBRATE -> DeviceStatusPublisher.RINGER_VIBRATE
            AudioManager.RINGER_MODE_SILENT -> DeviceStatusPublisher.RINGER_SILENT
            else -> null
        }
        val dnd = notificationManager?.currentInterruptionFilter?.let { filter ->
            filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
        return LinkMessage.DeviceStatus(battery = percent, charging = charging, ringer = ringer, dnd = dnd)
    }
}

/**
 * [DeviceController] for find-my-phone, ringer mode and DND.
 *
 * The ring uses the default alarm sound on the alarm stream (`USAGE_ALARM`), so it is audible in
 * silent and vibrate mode, at full alarm volume. The previous alarm volume is restored on stop.
 * A high-priority "Ringing from ..." notification with a Stop action accompanies it.
 */
public class AndroidDeviceController(
    context: Context,
    private val dispatchers: AppDispatchers,
) : DeviceController {

    public companion object {
        private const val TAG = "DeviceController"
        public const val CHANNEL_RING: String = "veyro.ring"
        private const val ID_RING = 1101

        /** The live controller, for the notification's Stop action. App-scoped, so no leak. */
        @Volatile internal var instance: AndroidDeviceController? = null

        /** Whether this app may change ringer/DND state (Settings > Do Not Disturb access). */
        public fun isPolicyAccessGranted(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

        /** Opens the system screen where Do Not Disturb access is granted. */
        public fun policyAccessSettingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    }

    private val appContext: Context = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val notificationManager: NotificationManager? =
        appContext.getSystemService(NotificationManager::class.java)

    // Touched only on the main dispatcher.
    private var ringtone: Ringtone? = null
    private var savedAlarmVolume: Int? = null

    init {
        instance = this
    }

    override suspend fun startRing(fromName: String?) {
        withContext(dispatchers.main) {
            if (ringtone?.isPlaying == true) return@withContext
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val tone = uri?.let { RingtoneManager.getRingtone(appContext, it) }
            if (tone == null) {
                VeyroLog.w(TAG) { "No alarm sound available" }
                return@withContext
            }
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            tone.isLooping = true

            audioManager?.let { audio ->
                if (savedAlarmVolume == null) savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                try {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                } catch (e: SecurityException) {
                    VeyroLog.w(TAG) { "Alarm volume change refused: ${e.javaClass.simpleName}" }
                }
            }
            tone.play()
            ringtone = tone
            VeyroLog.i(TAG) { "Ringing at the PC's request" }
            showRingNotification(fromName)
        }
    }

    override suspend fun stopRing() {
        withContext(dispatchers.main) { stopRingNow() }
    }

    /** Called from the notification's Stop action, already on the main thread. */
    internal fun stopRingNow() {
        val tone = ringtone
        ringtone = null
        tone?.stop()
        savedAlarmVolume?.let { previous ->
            try {
                audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
            } catch (e: SecurityException) {
                VeyroLog.w(TAG) { "Alarm volume restore refused: ${e.javaClass.simpleName}" }
            }
        }
        savedAlarmVolume = null
        notificationManager?.cancel(ID_RING)
    }

    override fun hasPolicyAccess(): Boolean = notificationManager?.isNotificationPolicyAccessGranted == true

    override suspend fun setRinger(mode: String) {
        val audio = audioManager ?: return
        val target = when (mode) {
            DeviceStatusPublisher.RINGER_NORMAL -> AudioManager.RINGER_MODE_NORMAL
            DeviceStatusPublisher.RINGER_VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            DeviceStatusPublisher.RINGER_SILENT -> AudioManager.RINGER_MODE_SILENT
            else -> return
        }
        withContext(dispatchers.main) {
            try {
                audio.ringerMode = target
            } catch (e: SecurityException) {
                VeyroLog.w(TAG) { "Ringer change refused: ${e.javaClass.simpleName}" }
            }
        }
    }

    override suspend fun setDnd(enabled: Boolean) {
        val manager = notificationManager ?: return
        withContext(dispatchers.main) {
            try {
                manager.setInterruptionFilter(
                    if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
                )
            } catch (e: SecurityException) {
                VeyroLog.w(TAG) { "DND change refused: ${e.javaClass.simpleName}" }
            }
        }
    }

    private fun showRingNotification(fromName: String?) {
        val manager = notificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RING, "Find my phone", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while a connected PC is ringing this phone."
                // The alarm itself makes the sound; the notification must not add a second one.
                setSound(null, null)
            },
        )
        val stop = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(appContext, RingStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(appContext, CHANNEL_RING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Ringing from ${fromName ?: "your PC"}")
            .setContentText("Tap Stop to silence it.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        try {
            manager.notify(ID_RING, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: the alarm still sounds, just without the notification.
            VeyroLog.w(TAG) { "Ring notification refused: ${e.javaClass.simpleName}" }
        }
    }
}

/** The ring notification's Stop action. Not exported: only our own PendingIntent reaches it. */
public class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        AndroidDeviceController.instance?.stopRingNow()
    }
}
