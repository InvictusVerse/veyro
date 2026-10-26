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