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
    /** Brightness setting, 0..100. */
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

    /** [percent] 0..100 of the brightness range. Switches automatic brightness off. */
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
