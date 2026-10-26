package com.veyro.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.veyro.ui.devices.DevicesViewModel
import com.veyro.ui.history.HistoryViewModel
import com.veyro.ui.home.HomeViewModel
import com.veyro.ui.onboarding.OnboardingViewModel
import com.veyro.ui.pairing.PairingViewModel
import com.veyro.ui.receive.ReceiveViewModel
import com.veyro.ui.send.SendViewModel
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
            connectedPeerId = container.connectedPeerId,
            sendMediaCommand = { container.mediaMirror.sendMediaCommand(it) },
            trustedPeers = container.trustStore.peers,
            connectPeer = { id, name, endpoints -> container.connectionManager.connect(id, name, endpoints) },
            disconnectPeer = { container.connectionManager.disconnect() },
        ) as T

        modelClass.isAssignableFrom(DevicesViewModel::class.java) -> DevicesViewModel(
            trustStore = container.trustStore,
        ) as T

        modelClass.isAssignableFrom(PairingViewModel::class.java) -> PairingViewModel(
            deviceRepository = container.deviceRepository,
            connectionManager = container.connectionManager,
        ) as T

        modelClass.isAssignableFrom(SendViewModel::class.java) -> SendViewModel(
            trustStore = container.trustStore,
            coordinator = container.transferCoordinator,
            selectedFiles = container.pickedFiles.files,
            onSelectionConsumed = container.pickedFiles::clear,
            onRemoveSelection = container.pickedFiles::removeAt,
            deviceRepository = container.deviceRepository,
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
        ) as T

        else -> error("Unknown ViewModel ${modelClass.name}; add it to VeyroViewModelFactory")
    }
}
