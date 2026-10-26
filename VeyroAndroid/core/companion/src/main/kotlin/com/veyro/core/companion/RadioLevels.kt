package com.veyro.core.companion

/**
 * The radio values `devStatus` carries (ADR-038, 04-PROTOCOL.md 6.2.1), as pure functions so the
 * mapping is JVM-testable. Android reads the raw values in [AndroidDeviceStatusSource].
 */
public object RadioLevels {

    /** Wire value for "Wi-Fi off or not connected" and "no cellular service". */
    public const val NONE: Int = -1

    public const val MAX_LEVEL: Int = 4

    /**
     * Wi-Fi bars 0..4 from an RSSI in dBm, with the thresholds Android's status bar uses for five
     * levels. Null or an out-of-range RSSI (the platform reports -127 or `Int.MIN_VALUE` when it
     * has none) means the level is unknown.
     */
    public fun wifiLevelFromRssi(rssi: Int?): Int? = when {
        rssi == null || rssi <= -127 || rssi >= 0 -> null
        rssi >= -55 -> 4
        rssi >= -66 -> 3
        rssi >= -77 -> 2
        rssi >= -88 -> 1
        else -> 0
    }

    /** Rescales a platform level in `0..maxLevel` to the wire's 0..4. */
    public fun scale(level: Int, maxLevel: Int): Int {
        if (maxLevel <= 0) return level.coerceIn(0, MAX_LEVEL)
        if (maxLevel == MAX_LEVEL) return level.coerceIn(0, MAX_LEVEL)
        return Math.round(level.coerceIn(0, maxLevel) * MAX_LEVEL.toFloat() / maxLevel).coerceIn(0, MAX_LEVEL)
    }

    /**
     * The `wifi` field: [NONE] while Wi-Fi is not the connected transport, else the level, or the
     * lowest bar when connected without a readable signal (so "connected" is never shown as "off").
     */
    public fun wifiField(wifiConnected: Boolean, level: Int?): Int =
        if (!wifiConnected) NONE else (level ?: 0).coerceIn(0, MAX_LEVEL)

    /** The `cell` field: [NONE] without a ready SIM, else the platform's 0..4 level. */
    public fun cellField(simReady: Boolean, level: Int?): Int =
        if (!simReady || level == null) NONE else level.coerceIn(0, MAX_LEVEL)
}
