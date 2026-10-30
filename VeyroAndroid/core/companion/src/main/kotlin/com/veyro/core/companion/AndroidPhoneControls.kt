package com.veyro.core.companion

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * [PhoneControls] on Android (ADR-060).
 *
 *  - Flashlight: `CameraManager.setTorchMode` on the first camera with a flash unit, the back
 *    camera preferred. No camera permission is involved. The state comes from a `TorchCallback`,
 *    which also says when another app holds the camera ("busy").
 *  - Media volume: `AudioManager` `STREAM_MUSIC`, as a percentage of the stream's own range.
 *    Changes are heard through the system's volume-changed broadcast.
 *  - Brightness: `Settings.System.SCREEN_BRIGHTNESS` and `SCREEN_BRIGHTNESS_MODE`. Reading needs
 *    nothing; writing needs the user to grant "Modify system settings"
 *    (`ACTION_MANAGE_WRITE_SETTINGS`, asked for from Device settings). The percent is the position
 *    of the phone's own slider: the setting's real range on this Android version
 *    ([PhoneControlMath.brightnessRange]; 1..255 on Android 11+, whatever the OEM config says)
 *    through AOSP's slider curve ([PhoneControlMath.linearToSlider]).
 *
 * Every call degrades to "absent" or a no-op on a device that refuses it.
 */
public class AndroidPhoneControls(
    context: Context,
    private val dispatchers: AppDispatchers,
) : PhoneControls {

    public companion object {
        private const val TAG = "PhoneControls"

        /** Not in the SDK, but sent by the platform on every stream volume change. */
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

        /** Whether the user let Veyro modify system settings (brightness). */
        public fun canWriteSettings(context: Context): Boolean = Settings.System.canWrite(context)
    }

    private val appContext: Context = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val cameraManager: CameraManager? = appContext.getSystemService(CameraManager::class.java)

    @Volatile private var torchOn: Boolean? = null
    @Volatile private var torchBusy: Boolean = false

    /** The camera whose flash is the flashlight, or null when there is none. */
    private val torchCameraId: String? by lazy {
        val manager = cameraManager ?: return@lazy null
        runCatching {
            val withFlash = manager.cameraIdList.filter { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            withFlash.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: withFlash.firstOrNull()
        }.onFailure { VeyroLog.d(TAG) { "No flashlight: ${it.javaClass.simpleName}" } }.getOrNull()
    }

    private val configBrightnessMin: Int? by lazy { systemInteger("config_screenBrightnessSettingMinimum") }
    private val configBrightnessMax: Int? by lazy { systemInteger("config_screenBrightnessSettingMaximum") }

    /**
     * The last brightness the PC set: the value written and the percent asked for. The setting
     * is an integer, so a percent does not always survive the round trip; while the setting
     * still holds what we wrote, the PC is shown its own percent instead of a neighbour.
     */
    @Volatile private var lastWrittenBrightness: Pair<Int, Int>? = null

    @SuppressLint("DiscouragedApi")
    private fun systemInteger(name: String): Int? {
        val id = Resources.getSystem().getIdentifier(name, "integer", "android")
        return runCatching { if (id != 0) Resources.getSystem().getInteger(id) else null }.getOrNull()?.takeIf { it > 0 }
    }

    private fun brightnessRange(observed: Int?): IntRange =
        PhoneControlMath.brightnessRange(Build.VERSION.SDK_INT, configBrightnessMin, configBrightnessMax, observed)

    override val changes: Flow<Unit> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        val torchId = torchCameraId
        val torchCallback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId != torchId) return
                torchOn = enabled
                torchBusy = false
                trySend(Unit)
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId != torchId) return
                torchBusy = true
                torchOn = false
                trySend(Unit)
            }
        }
        val torchRegistered = torchId != null && runCatching { cameraManager?.registerTorchCallback(torchCallback, handler) }
            .onFailure { VeyroLog.d(TAG) { "No torch callback: ${it.javaClass.simpleName}" } }
            .isSuccess

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        // A system broadcast, so the receiver need not be exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }

        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        runCatching {
            val resolver = appContext.contentResolver
            resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer)
            resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, observer)
        }

        awaitClose {
            if (torchRegistered) runCatching { cameraManager?.unregisterTorchCallback(torchCallback) }
            runCatching { appContext.unregisterReceiver(receiver) }
            runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
        }
    }

    override suspend fun current(): PhoneControlsReading = withContext(dispatchers.io) {
        val audio = audioManager
        val volume = audio?.let {
            runCatching {
                val min = it.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                PhoneControlMath.toPercent(it.getStreamVolume(AudioManager.STREAM_MUSIC), min, max)
            }.getOrNull()
        }
        val resolver = appContext.contentResolver
        val brightness = runCatching {
            val value = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
            lastWrittenBrightness?.takeIf { it.first == value }?.second
                ?: PhoneControlMath.brightnessToPercent(value, brightnessRange(value))
        }.getOrNull()
        val auto = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }.getOrNull()
        PhoneControlsReading(
            torch = torchOn,
            torchPresent = torchCameraId != null,
            torchBusy = torchBusy,
            mediaVolume = volume,
            volumeAdjustable = audio != null && volume != null && !audio.isVolumeFixed,
            brightness = brightness,
            autoBrightness = auto,
            canWriteSettings = canWriteSettings(appContext),
        )
    }

    override suspend fun setTorch(on: Boolean) {
        val id = torchCameraId ?: return
        withContext(dispatchers.io) { cameraManager?.setTorchMode(id, on) }
        VeyroLog.i(TAG) { "Flashlight set at the PC's request" }
    }

    override suspend fun setMediaVolume(percent: Int) {
        val audio = audioManager ?: return
        withContext(dispatchers.io) {
            val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            // No flags: no volume panel pops up on a phone nobody is holding.
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, PhoneControlMath.fromPercent(percent, min, max), 0)
        }
    }

    override suspend fun setBrightness(percent: Int) {
        if (!canWriteSettings(appContext)) return
        withContext(dispatchers.io) {
            val resolver = appContext.contentResolver
            // A value written while automatic brightness is on has no visible effect, so a
            // brightness from the PC means "this brightness", as moving the phone's own slider does.
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val current = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
            val value = PhoneControlMath.percentToBrightness(percent, brightnessRange(current))
            lastWrittenBrightness = value to percent.coerceIn(0, 100)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
        }
    }

    override suspend fun setAutoBrightness(on: Boolean) {
        if (!canWriteSettings(appContext)) return
        withContext(dispatchers.io) {
            Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        }
    }
}
