package com.veyro.ui.settings

import com.veyro.core.common.Diagnostics

/**
 * Everything the settings screen renders.
 *
 * One immutable state object rather than several flows collected separately, so the screen can
 * never show a half-updated combination (a new device name next to a stale fingerprint).
 */
public data class SettingsUiState(
    val isLoading: Boolean = true,
    val deviceName: String = "",
    val deviceId: String = "",
    val fingerprint: String = "",
    val pairedDeviceCount: Int = 0,
    val alwaysDiscoverable: Boolean = false,
    val destinationPath: String? = null,

    /** The user's choice to mirror notifications to a connected PC (ADR-025). */
    val notificationMirrorEnabled: Boolean = false,

    /** Whether the system has granted notification access. Mirroring does nothing without it. */
    val notificationAccessGranted: Boolean = false,

    /** Share this phone's media with a connected PC (ADR-026). Needs notification access too. */
    val mediaShareEnabled: Boolean = false,

    /** Let a connected PC ring this phone and change its sound mode (ADR-026). */
    val deviceControlAllowed: Boolean = false,

    /** Whether Do Not Disturb access is granted. Ringer/DND changes from the PC need it. */
    val policyAccessGranted: Boolean = false,

    /** Let a connected PC use the flashlight, media volume and brightness (ADR-060). */
    val phoneControlsAllowed: Boolean = false,

    /** Whether "Modify system settings" is granted. Brightness from the PC needs it. */
    val writeSettingsGranted: Boolean = false,

    /** Serve SMS and calls to a connected PC (ADR-027). */
    val messagesAndCallsEnabled: Boolean = false,

    /** Labels of the runtime permissions that feature still lacks; empty when all are granted. */
    val missingMessagingPermissions: List<String> = emptyList(),

    /** Serve recent photos to a connected PC (ADR-034). */
    val photosEnabled: Boolean = false,

    /** Whether the photo read permission is held. Photos are served only with it. */
    val photosPermissionGranted: Boolean = false,

    /** Keep the connection to the paired PC in the background (ADR-036). On by default. */
    val runInBackground: Boolean = true,

    /** Start that connection after the phone boots. Off by default. */
    val startOnBoot: Boolean = false,

    /** Send this phone's wallpaper to the PC (ADR-038). On by default. */
    val wallpaperShareEnabled: Boolean = true,

    /** Clipboard and sent / received notifications clear themselves (ADR-044). On by default. */
    val notificationAutoClear: Boolean = true,

    /** After how many minutes. 5 by default. */
    val notificationAutoClearMinutes: Int = 5,

    /** Whether Android exempts Veyro from battery optimisation (asked only from Settings). */
    val batteryOptimizationIgnored: Boolean = false,

    /**
     * Set when the identity could not be loaded at all. This is the "your identity was lost"
     * state from `05-SECURITY.md` section 7, and it must be shown rather than hidden behind a
     * spinner, because the only way out is a user action.
     */
    val identityError: String? = null,

    /** Set when the trust store failed authentication. Means "re-pair your devices". */
    val trustStoreError: String? = null,

    val diagnostics: Diagnostics? = null,
    val logDump: String = "",
    val resetInProgress: Boolean = false,
)