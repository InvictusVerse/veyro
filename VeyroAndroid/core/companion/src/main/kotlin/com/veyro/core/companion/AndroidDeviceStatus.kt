package com.veyro.core.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.link.PeerMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/**
 * [DeviceStatusSource] from the sticky battery broadcast, `AudioManager`, `NotificationManager`,
 * and (ADR-038) the radios: Wi-Fi and mobile data from `ConnectivityManager`, the cellular level
 * and carrier from `TelephonyManager`, Bluetooth on/off.
 *
 * `ACTION_BATTERY_CHANGED` and the signal callbacks fire often; [DeviceStatusPublisher] only sends
 * when the resulting message differs, so that is a whole-point battery move, a bar gained or lost,
 * or a charging / ringer / DND / radio change.
 *
 * No permission is added for any of it. Bluetooth is read through `BluetoothAdapter.isEnabled`
 * only when `BLUETOOTH_CONNECT` happens to be granted (API 31+), otherwise from
 * `Settings.Global.BLUETOOTH_ON`, which needs none: Veyro declares no Bluetooth permission
 * (ADR-001). The carrier comes from the data SIM's display name when `READ_PHONE_STATE` is granted
 * (it is for "Messages and calls on PC"), else from the SIM operator name. Every read degrades to
 * "absent" on a phone without telephony or on an OEM that refuses it. Carrier is user data: never
 * logged.
 */
public class AndroidDeviceStatusSource(context: Context) : DeviceStatusSource {

    private val appContext: Context = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val notificationManager: NotificationManager? =
        appContext.getSystemService(NotificationManager::class.java)
    private val connectivity: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager: WifiManager? = appContext.getSystemService(WifiManager::class.java)
    private val telephony: TelephonyManager? = appContext.getSystemService(TelephonyManager::class.java)

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
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            // Delivered only where the platform lets us; the settings observer below covers the rest.
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // System broadcasts only, so the receiver need not be exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }
            override fun onLost(network: Network) {
                trySend(Unit)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(Unit)
            }
        }
        val networkRegistered = runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { VeyroLog.d(TAG) { "No network callback: ${it.javaClass.simpleName}" } }
            .isSuccess && connectivity != null

        val bluetoothObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        runCatching {
            appContext.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.BLUETOOTH_ON),
                false,
                bluetoothObserver,
            )
        }

        val unregisterSignal = registerSignalListener { trySend(Unit) }

        awaitClose {
            runCatching { appContext.unregisterReceiver(receiver) }
            if (networkRegistered) runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            runCatching { appContext.contentResolver.unregisterContentObserver(bluetoothObserver) }
            runCatching { unregisterSignal() }
        }
    }

    /** Cellular signal changes. No permission is needed for signal strength on any supported API. */
    private fun registerSignalListener(onChange: () -> Unit): () -> Unit {
        val noop: () -> Unit = {}
        val manager = telephony ?: return noop
        val executor = Executor { it.run() }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = onChange()
                }
                manager.registerTelephonyCallback(executor, callback)
                return { manager.unregisterTelephonyCallback(callback) }
            }
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener(executor) {
                @Deprecated("Deprecated in Java")
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) = onChange()
            }
            @Suppress("DEPRECATION")
            manager.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            @Suppress("DEPRECATION")
            return { manager.listen(listener, PhoneStateListener.LISTEN_NONE) }
        } catch (e: RuntimeException) {
            // SecurityException on some OEM builds, or no telephony service at all.
            VeyroLog.d(TAG) { "No signal listener: ${e.javaClass.simpleName}" }
            return noop
        }
    }

    private class RadioState(val wifiConnected: Boolean, val wifiRssi: Int?, val mobileData: Boolean?)

    private fun readNetwork(): RadioState {
        val cm = connectivity ?: return RadioState(false, null, null)
        val active = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        val mobileData = active?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
        // The active network can be a VPN or cellular while Wi-Fi is still associated.
        val wifiCaps = active?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?: runCatching {
                @Suppress("DEPRECATION")
                cm.allNetworks.asSequence()
                    .mapNotNull { cm.getNetworkCapabilities(it) }
                    .firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            }.getOrNull()
        val rssi = wifiCaps?.signalStrength?.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
            ?: if (wifiCaps != null) {
                @Suppress("DEPRECATION")
                runCatching { wifiManager?.connectionInfo?.rssi }.getOrNull()
            } else {
                null
            }
        return RadioState(wifiConnected = wifiCaps != null, wifiRssi = rssi, mobileData = mobileData)
    }

    private fun simReady(): Boolean =
        runCatching { telephony?.simState == TelephonyManager.SIM_STATE_READY }.getOrDefault(false)

    private fun cellLevel(): Int? = runCatching { telephony?.signalStrength?.level }.getOrNull()

    // The permission is checked right here; without it the name falls back to the operator.
    @SuppressLint("MissingPermission")
    private fun carrier(): String? {
        val manager = telephony ?: return null
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        val display = if (granted(Manifest.permission.READ_PHONE_STATE) && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            runCatching {
                appContext.getSystemService(SubscriptionManager::class.java)
                    ?.getActiveSubscriptionInfo(subId)?.displayName?.toString()
            }.getOrNull()
        } else {
            null
        }
        val operator = display ?: runCatching {
            val forData = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) manager.createForSubscriptionId(subId) else manager
            forData.simOperatorName?.takeIf { it.isNotBlank() } ?: forData.networkOperatorName
        }.getOrNull()
        return operator?.trim()?.takeIf { it.isNotEmpty() }
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothOn(): Boolean? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            runCatching { appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }
                .getOrNull()?.let { return it }
        }
        return runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BLUETOOTH_ON, 0) != 0
        }.getOrNull()
    }

    private fun granted(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "DeviceStatusSource"
    }

    override suspend fun current(): PeerMessage.DeviceStatus {
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
        val radio = readNetwork()
        val simReady = simReady()
        return PeerMessage.DeviceStatus(
            battery = percent,
            charging = charging,
            ringer = ringer,
            dnd = dnd,
            wifi = RadioLevels.wifiField(radio.wifiConnected, RadioLevels.wifiLevelFromRssi(radio.wifiRssi)),
            cell = if (telephony == null) null else RadioLevels.cellField(simReady, cellLevel()),
            carrier = if (simReady) carrier() else null,
            bluetooth = bluetoothOn(),
            mobileData = radio.mobileData,
        )
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
