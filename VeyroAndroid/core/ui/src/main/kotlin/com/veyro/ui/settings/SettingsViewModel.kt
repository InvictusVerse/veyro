package com.veyro.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veyro.core.common.Diagnostics
import com.veyro.core.common.RecordingLogSink
import com.veyro.core.crypto.IdentityManager
import com.veyro.core.crypto.IdentityUnavailableException
import com.veyro.core.data.SettingsStore
import com.veyro.core.data.TrustStore
import com.veyro.core.model.fingerprint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Settings state holder.
 *
 * Plain constructor injection, no annotations - the graph is assembled in `:app` (ADR-005), which
 * is what keeps this class trivially testable and this module framework-free.
 */
public class SettingsViewModel(
    private val identityManager: IdentityManager,
    private val settingsStore: SettingsStore,
    private val trustStore: TrustStore,
    private val logSink: RecordingLogSink?,
    private val diagnosticsProvider: suspend () -> Diagnostics,
    /** Whether the system has granted notification access. Re-read on every screen resume. */
    private val notificationAccessGranted: () -> Boolean = { false },
    /** Whether Do Not Disturb access is granted. Re-read on every screen resume. */
    private val policyAccessGranted: () -> Boolean = { false },
    /** Labels of the messaging permissions not granted yet. Re-read on every screen resume. */
    private val missingMessagingPermissions: () -> List<String> = { emptyList() },
    /** Whether the photo read permission is held. Re-read on every screen resume. */
    private val photosPermissionGranted: () -> Boolean = { false },
    /** Whether Android exempts Veyro from battery optimisation. Re-read on every screen resume. */
    private val batteryOptimizationIgnored: () -> Boolean = { false },
    /** Told when a background setting changes, so a stale Disconnect does not keep it off. */
    private val onBackgroundSettingChanged: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    public val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        loadIdentity()
        observeStores()
    }

    private fun loadIdentity() {
        viewModelScope.launch {
            try {
                val identity = identityManager.ensureIdentity()
                _state.value = _state.value.copy(
                    isLoading = false,
                    deviceId = identity.deviceId.value,
                    fingerprint = identity.deviceId.fingerprint(),
                    identityError = null,
                )
            } catch (e: IdentityUnavailableException) {
                // Show it. The only route out is the reset action below, and hiding this behind a
                // spinner would leave the user with a screen that never finishes loading.
                _state.value = _state.value.copy(isLoading = false, identityError = e.message)
            }
        }
    }

    private fun observeStores() {
        combine(
            settingsStore.deviceName,
            settingsStore.alwaysDiscoverable,
            settingsStore.destinationDir,
            trustStore.peers,
        ) { name, discoverable, dir, peers ->
            Quad(name, discoverable, dir?.absolutePath, peers.count { !it.revoked })
        }
            .onEach { (name, discoverable, path, count) ->
                _state.value = _state.value.copy(
                    deviceName = name,
                    alwaysDiscoverable = discoverable,
                    destinationPath = path,
                    pairedDeviceCount = count,
                )
            }
            .launchIn(viewModelScope)

        settingsStore.notificationMirrorEnabled
            .onEach { _state.value = _state.value.copy(notificationMirrorEnabled = it) }
            .launchIn(viewModelScope)

        settingsStore.mediaShareEnabled
            .onEach { _state.value = _state.value.copy(mediaShareEnabled = it) }
            .launchIn(viewModelScope)

        settingsStore.deviceControlAllowed
            .onEach { _state.value = _state.value.copy(deviceControlAllowed = it) }
            .launchIn(viewModelScope)

        settingsStore.messagesAndCallsEnabled
            .onEach { _state.value = _state.value.copy(messagesAndCallsEnabled = it) }
            .launchIn(viewModelScope)

        settingsStore.photosEnabled
            .onEach { _state.value = _state.value.copy(photosEnabled = it) }
            .launchIn(viewModelScope)

        settingsStore.runInBackground
            .onEach { _state.value = _state.value.copy(runInBackground = it) }
            .launchIn(viewModelScope)

        settingsStore.startOnBoot
            .onEach { _state.value = _state.value.copy(startOnBoot = it) }
            .launchIn(viewModelScope)

        settingsStore.wallpaperShareEnabled
            .onEach { _state.value = _state.value.copy(wallpaperShareEnabled = it) }
            .launchIn(viewModelScope)
    }

    /** ADR-038. The photo permission, if needed, is requested by the Activity. */
    public fun setWallpaperShareEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setWallpaperShareEnabled(enabled) }
    }

    /** ADR-036. The background service follows the stored value. */
    public fun setRunInBackground(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setRunInBackground(enabled)
            onBackgroundSettingChanged()
        }
    }

    public fun setStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setStartOnBoot(enabled) }
    }

    /** The runtime permission is requested by the Activity; this only stores the choice. */
    public fun setPhotosEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setPhotosEnabled(enabled) }
    }

    /** Runtime permissions are requested by the Activity; this only stores the choice. */
    public fun setMessagesAndCallsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setMessagesAndCallsEnabled(enabled) }
    }

    public fun setNotificationMirrorEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNotificationMirrorEnabled(enabled) }
    }

    public fun setMediaShareEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setMediaShareEnabled(enabled) }
    }

    public fun setDeviceControlAllowed(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setDeviceControlAllowed(enabled) }
    }

    /** Access is granted in system settings, outside the app, so it is polled on resume. */
    public fun refreshNotificationAccess() {
        _state.value = _state.value.copy(
            notificationAccessGranted = notificationAccessGranted(),
            policyAccessGranted = policyAccessGranted(),
            missingMessagingPermissions = missingMessagingPermissions(),
            photosPermissionGranted = photosPermissionGranted(),
            batteryOptimizationIgnored = batteryOptimizationIgnored(),
        )
    }

    public fun setDeviceName(name: String) {
        viewModelScope.launch { settingsStore.setDeviceName(name) }
    }

    public fun setAlwaysDiscoverable(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAlwaysDiscoverable(enabled) }
    }

    /** Destroys the identity. Every pairing dies with it, so the UI must confirm first. */
    public fun resetIdentity() {
        viewModelScope.launch {
            _state.value = _state.value.copy(resetInProgress = true)
            try {
                val identity = identityManager.resetIdentity()
                _state.value = _state.value.copy(
                    resetInProgress = false,
                    deviceId = identity.deviceId.value,
                    fingerprint = identity.deviceId.fingerprint(),
                    identityError = null,
                    trustStoreError = null,
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    resetInProgress = false,
                    identityError = t.message ?: "The identity could not be reset",
                )
            }
        }
    }

    /** Builds the diagnostics snapshot and the log tail for the debug section. */
    public fun refreshDiagnostics() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                diagnostics = diagnosticsProvider(),
                logDump = logSink?.dump() ?: "(logging recorder is not installed)",
            )
        }
    }

    public fun clearLog() {
        logSink?.clear()
        _state.value = _state.value.copy(logDump = "")
    }

    private data class Quad(
        val name: String,
        val discoverable: Boolean,
        val path: String?,
        val count: Int,
    )
}