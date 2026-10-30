package com.veyro.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.veyro.ui.clipboard.ClipboardViewModel
import com.veyro.ui.devices.DevicesViewModel
import com.veyro.ui.history.HistoryViewModel
import com.veyro.ui.home.HomeViewModel
import com.veyro.ui.keepalive.KeepConnectedViewModel
import com.veyro.ui.onboarding.OnboardingViewModel
import com.veyro.ui.pairing.PairingViewModel
import com.veyro.ui.receive.ReceiveViewModel
import com.veyro.ui.settings.SettingsViewModel

/**
 * The one place ViewModels are constructed.
 *
 * This `when` is the entire cost of not using a DI framework (ADR-005). In exchange, the feature
 * modules carry no annotations, no generated code and no framework dependency, and every ViewModel
 * can be unit-tested by calling its constructor with fakes.
 *
 * Adding a ViewModel means adding one branch here. If that ever stops being obvious - if a
 * ViewModel needs something this class cannot reach - that is a signal the graph has outgrown
 * ADR-005, and it should be revisited with a new ADR rather than worked around here.
 */
public class VeyroViewModelFactory(
    private val container: VeyroContainer,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(
            deviceRepository = container.deviceRepository,
            networkMonitor = container.networkMonitor,
            pcMedia = container.mediaMirror.peerMedia,
            pcStatus = container.deviceStatusPublisher.peerStatus,
            connectedPeerIds = container.connectedPeerIds,
            // ADR-053: which devices the user disconnected, for "Disconnected" and Connect.
            userDisconnectedIds = container.backgroundController.userDisconnectedIds,
            sendMediaCommand = { id, command -> container.mediaMirror.sendMediaCommand(id, command) },
            trustedPeers = container.trustStore.peers,
            // Through the background controller (ADR-036, ADR-053), so a Disconnect here is not
            // undone by this phone's background reconnect or by the device dialling back in, and a
            // Connect resumes it. Both act on that one device; the others stay connected.
            connectPeer = { id, name, endpoints ->
                container.backgroundController.onUserConnect(id)
                container.connectionManager.connect(id, name, endpoints)
            },
            disconnectPeer = { id -> container.backgroundController.onUserDisconnect(id) },
            resumePeer = { id -> container.backgroundController.onUserConnect(id) },
            // ADR-038: the Phone Link-style Home.
            peerCapabilities = container.connectedPeerCapabilities,
            pcWallpapers = container.wallpaperBridge.peerWallpapers,
            history = container.historyStore.history,
            lockPc = { id -> container.lockPc(id) },
            onPcSelected = { id -> container.selectPc(id) },
            removeHistory = { container.historyStore.remove(it) },
            loadThumbnail = { container.loadThumbnail(it) },
            // ADR-061: the local network notice and the "stops in the background" notice.
            localNetwork = container.localNetworkAccess.state,
            keepAliveNotice = { container.keepAliveHomeNotice() },
        ) as T

        modelClass.isAssignableFrom(KeepConnectedViewModel::class.java) -> KeepConnectedViewModel(
            readFacts = container::keepAliveFacts,
            localNetwork = container.localNetworkAccess.state,
            open = container::openKeepAliveTarget,
        ) as T

        modelClass.isAssignableFrom(DevicesViewModel::class.java) -> DevicesViewModel(
            trustStore = container.trustStore,
            unpair = { id -> container.connectionManager.unpair(id.value) },
        ) as T

        modelClass.isAssignableFrom(PairingViewModel::class.java) -> PairingViewModel(
            deviceRepository = container.deviceRepository,
            connectionManager = container.connectionManager,
        ) as T

        modelClass.isAssignableFrom(ReceiveViewModel::class.java) -> ReceiveViewModel(
            coordinator = container.transferCoordinator,
            deviceRepository = container.deviceRepository,
        ) as T

        modelClass.isAssignableFrom(HistoryViewModel::class.java) -> HistoryViewModel(
            store = container.historyStore,
        ) as T

        modelClass.isAssignableFrom(OnboardingViewModel::class.java) -> OnboardingViewModel(
            settingsStore = container.settingsStore,
            trustedPeers = container.trustStore.peers,
            featureAccess = container::onboardingFeatureAccess,
        ) as T

        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(
            identityManager = container.identityManager,
            settingsStore = container.settingsStore,
            trustStore = container.trustStore,
            logSink = container.logRecorder,
            diagnosticsProvider = { container.diagnostics() },
            notificationAccessGranted = container::isNotificationAccessGranted,
            policyAccessGranted = container::isPolicyAccessGranted,
            missingMessagingPermissions = container::missingMessagingPermissions,
            photosPermissionGranted = container::isPhotosPermissionGranted,
            batteryOptimizationIgnored = container::isIgnoringBatteryOptimizations,
            onBackgroundSettingChanged = { container.backgroundController.onSettingChanged() },
            keepConnectedIssues = container::keepAliveIssueCount,
        ) as T

        modelClass.isAssignableFrom(ClipboardViewModel::class.java) -> ClipboardViewModel(
            rows = container.clipboardRows,
            autoFetch = container.settingsStore.clipboardReceiveEnabled,
            setAutoFetch = { container.settingsStore.setClipboardReceiveEnabled(it) },
            copy = container::copyClip,
            remove = { id -> container.clipboardBridge.history.value.filter { it.id == id }.forEach(container.clipboardBridge::remove) },
            clear = { container.clipboardBridge.clear() },
            sendClipboard = { container.sendPhoneClipboard() },
            copyText = container::copyClipText,
        ) as T
        else -> error("Unknown ViewModel ${modelClass.name}; add it to VeyroViewModelFactory")
    }
}
