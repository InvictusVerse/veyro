package com.veyro.core.companion

import com.veyro.core.link.PeerMessage
import kotlinx.coroutines.flow.Flow

/**
 * What [PhoneControls] reads right now (ADR-060). Every value is null when the phone cannot tell.
 */
public data class PhoneControlsReading(
    val torch: Boolean? = null,
    /** False when the phone has no flashlight. */
    val torchPresent: Boolean = false,
    /** True while another app holds the flashlight (camera open). */
    val torchBusy: Boolean = false,
    /** Media stream volume, 0..100. */
    val mediaVolume: Int? = null,
    /** False on a fixed-volume device. */
    val volumeAdjustable: Boolean = false,
    /** Brightness as the position of the phone's own slider, 0..100. */
    val brightness: Int? = null,
    val autoBrightness: Boolean? = null,
    /** `Settings.System.canWrite`: the user granted "Modify system settings" to Veyro. */
    val canWriteSettings: Boolean = false,
)

/**
 * Flashlight, media volume and screen brightness (ADR-060). Kept free of `android.*`, so the
 * gating in [DeviceStatusPublisher] is JVM-testable; the Android side is `AndroidPhoneControls`.
 * Callers validate ranges; implementations still clamp.
 */
public interface PhoneControls {

    /** Hot "something may have changed" signal. Spurious signals are fine; sends are deduped. */
    public val changes: Flow<Unit>

    public suspend fun current(): PhoneControlsReading

    public suspend fun setTorch(on: Boolean)

    /** [percent] 0..100 of the media stream's range. */
    public suspend fun setMediaVolume(percent: Int)

    /** [percent] 0..100 as a position of the phone's own slider. Switches automatic brightness off. */
    public suspend fun setBrightness(percent: Int)

    public suspend fun setAutoBrightness(on: Boolean)
}

/** Pure helpers for [PhoneControls], shared by the publisher and the Android implementation. */
public object PhoneControlMath {

    /** [value] in [min]..[max] as 0..100, rounded; null when the range is empty. */
    public fun toPercent(value: Int, min: Int, max: Int): Int? {
        if (max <= min) return null
        val clamped = value.coerceIn(min, max)
        return ((clamped - min) * 100.0 / (max - min) + 0.5).toInt().coerceIn(0, 100)
    }

    /** [percent] 0..100 as a value in [min]..[max], rounded. */
    public fun fromPercent(percent: Int, min: Int, max: Int): Int {
        if (max <= min) return min
        return (min + percent.coerceIn(0, 100) * (max - min) / 100.0 + 0.5).toInt().coerceIn(min, max)
    }

    /** True for a `devControl` percentage this phone acts on; anything else is ignored. */
    public fun isPercent(value: Int?): Boolean = value != null && value in 0..100

    // ── Brightness (ADR-060, revised after the Redmi K50i showed 1 % at about half brightness) ──
    //
    // Two facts make a plain "value / max" wrong on most phones:
    //  1. Android 11+ (API 30) keeps Settings.System.SCREEN_BRIGHTNESS in 1..255 on every phone
    //     (BrightnessSynchronizer maps the float brightness 0..1 to it), while
    //     config_screenBrightnessSettingMaximum still holds the OEM's old range (2047, 4095,
    //     8191 on Xiaomi). Dividing by the config value gave 27 / 2047 = 1 %.
    //  2. The system slider is not linear in brightness: it is the HLG gamma curve from AOSP
    //     settingslib BrightnessUtils. The setting 27 of 255 is about 55 % on the phone's slider.
    // So the percent on the wire is the slider position, computed with that curve over the
    // range the setting really uses on this Android version.

    // AOSP com.android.settingslib.display.BrightnessUtils constants.
    private const val GAMMA_R = 0.5
    private const val GAMMA_A = 0.17883277
    private const val GAMMA_B = 0.28466892
    private const val GAMMA_C = 0.55991073

    /** Linear brightness 0..1 as the system slider's position 0..1. */
    public fun linearToSlider(linear: Double): Double {
        val n = linear.coerceIn(0.0, 1.0) * 12.0
        val g = if (n <= 1.0) kotlin.math.sqrt(n) * GAMMA_R else GAMMA_A * kotlin.math.ln(n - GAMMA_B) + GAMMA_C
        return g.coerceIn(0.0, 1.0)
    }

    /** The system slider's position 0..1 as linear brightness 0..1. */
    public fun sliderToLinear(slider: Double): Double {
        val s = slider.coerceIn(0.0, 1.0)
        val n = if (s <= GAMMA_R) (s / GAMMA_R) * (s / GAMMA_R) else kotlin.math.exp((s - GAMMA_C) / GAMMA_A) + GAMMA_B
        return (n / 12.0).coerceIn(0.0, 1.0)
    }

    /**
     * The range `SCREEN_BRIGHTNESS` uses on this phone. Android 11+: 1..255 whatever the panel.
     * Android 10: the platform's config range (else 1..255). A reading above the range (an OEM
     * that kept a larger scale) widens it to the config maximum or the reading itself, so the
     * percent never exceeds 100 or drops to nonsense.
     */
    public fun brightnessRange(sdkInt: Int, configMin: Int?, configMax: Int?, observed: Int? = null): IntRange {
        val base = if (sdkInt >= 30) {
            1..255
        } else {
            val max = configMax?.takeIf { it > 1 } ?: 255
            val min = configMin?.takeIf { it in 0 until max } ?: 1
            min..max
        }
        if (observed == null || observed <= base.last) return base
        val top = configMax?.takeIf { it >= observed } ?: observed
        return base.first..top
    }

    /** A `SCREEN_BRIGHTNESS` value as the system slider's position, 0..100. */
    public fun brightnessToPercent(value: Int, range: IntRange): Int? {
        if (range.last <= range.first) return null
        val linear = (value.coerceIn(range.first, range.last) - range.first).toDouble() / (range.last - range.first)
        return (linearToSlider(linear) * 100.0 + 0.5).toInt().coerceIn(0, 100)
    }

    /** A slider position 0..100 as the `SCREEN_BRIGHTNESS` value to write; never below the range (0 can mean off). */
    public fun percentToBrightness(percent: Int, range: IntRange): Int {
        if (range.last <= range.first) return range.first
        val linear = sliderToLinear(percent.coerceIn(0, 100) / 100.0)
        return (range.first + linear * (range.last - range.first) + 0.5).toInt().coerceIn(range.first, range.last)
    }

    /**
     * The `devStatus` fields for [reading]. [allowed] is the phone's switch; with it off every
     * control reads [PeerMessage.PhoneControlState.OFF] (the values are still reported).
     */
    public fun statusFields(reading: PhoneControlsReading, allowed: Boolean): PeerMessage.DeviceStatus {
        fun state(present: Boolean, ready: Boolean, notReady: String): String = when {
            !present -> PeerMessage.PhoneControlState.UNSUPPORTED
            !allowed -> PeerMessage.PhoneControlState.OFF
            !ready -> notReady
            else -> PeerMessage.PhoneControlState.OK
        }
        return PeerMessage.DeviceStatus(
            torch = if (reading.torchPresent) reading.torch else null,
            mediaVolume = reading.mediaVolume?.coerceIn(0, 100),
            brightness = reading.brightness?.coerceIn(0, 100),
            autoBrightness = reading.autoBrightness,
            controls = PeerMessage.PhoneControlState(
                torch = state(reading.torchPresent, !reading.torchBusy, PeerMessage.PhoneControlState.BUSY),
                volume = state(reading.volumeAdjustable, true, PeerMessage.PhoneControlState.UNSUPPORTED),
                brightness = state(reading.brightness != null, reading.canWriteSettings, PeerMessage.PhoneControlState.PERMISSION),
            ),
        )
    }
}
