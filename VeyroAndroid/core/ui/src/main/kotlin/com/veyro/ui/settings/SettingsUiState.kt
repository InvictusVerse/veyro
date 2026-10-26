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