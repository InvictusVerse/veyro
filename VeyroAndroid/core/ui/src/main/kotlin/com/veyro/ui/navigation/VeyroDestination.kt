package com.veyro.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Navigation destinations.
 *
 * Type-safe routes (`@Serializable` objects consumed by `composable<T>`) rather than string
 * routes with `navArgument`. The difference is not cosmetic: with strings, a typo in a route or
 * a missing argument is a runtime crash in one specific navigation path, which is exactly the
 * kind of bug that survives testing. Here it is a compile error.
 *
 * The four tabs show the bottom bar and the tab top bar; every other destination is a pushed
 * screen with its own back arrow, as in Sefirah's root graph.
 */
public sealed interface VeyroDestination {

    /** The tabbed main screen (bottom bar + tab top bar) hosting the four tabs below. */
    @Serializable
    public data object Main : VeyroDestination

    @Serializable
    public data object Home : VeyroDestination

    @Serializable
    public data object Devices : VeyroDestination

    @Serializable
    public data object History : VeyroDestination

    @Serializable
    public data object Settings : VeyroDestination

    /** Reached from Home's empty card, the quick actions and the Devices button. */
    @Serializable
    public data object Pairing : VeyroDestination

    @Serializable
    public data object Receive : VeyroDestination

    @Serializable
    public data object Clipboard : VeyroDestination

    /** One paired device's settings (Sefirah's DeviceSettingsScreen). */
    @Serializable
    public data class DeviceSettings(val deviceId: String) : VeyroDestination

    /** "Share media with PC" details, behind its switch on DeviceSettings. */
    @Serializable
    public data object DeviceMedia : VeyroDestination

    /** "Messages and calls on PC" details, behind its switch on DeviceSettings. */
    @Serializable
    public data object DeviceMessages : VeyroDestination

    /** "Keep Veyro connected" checklist (ADR-061), from Settings and Home's notice. */
    @Serializable
    public data object KeepConnected : VeyroDestination
}
