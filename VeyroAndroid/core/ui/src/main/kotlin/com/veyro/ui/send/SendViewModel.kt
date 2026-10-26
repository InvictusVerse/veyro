package com.veyro.ui.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veyro.core.data.DeviceRepository
import com.veyro.core.data.DiscoverySubscription
import com.veyro.core.data.TrustStore
import com.veyro.core.model.DeviceId
import com.veyro.core.model.TransferProgress
import com.veyro.core.model.TrustedPeer
import com.veyro.core.link.OutgoingFile
import com.veyro.core.link.TransferCoordinator
import com.veyro.core.link.SendResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.veyro.core.model.ActiveTransfer
import kotlinx.coroutines.launch

public data class SendUiState(
    val devices: List<TrustedPeer> = emptyList(),
    val selected: List<OutgoingFile> = emptyList(),
    val target: DeviceId? = null,
    val progress: TransferProgress? = null,
    val activeTransfer: ActiveTransfer? = null,
    val outcome: String? = null,
    val isSending: Boolean = false,
) {
    val canSend: Boolean get() = selected.isNotEmpty() && target != null && !isSending
    val totalBytes: Long get() = selected.sumOf { it.sizeBytes }
}

/**
 * Send flow: pick files, pick a device, send.
 *
 * Three steps, which is the REQ-U01 target. Nothing here knows about sessions, chunks or hashes - it
 * hands [OutgoingFile]s to the coordinator and observes progress, which keeps the screen testable
 * without a socket and keeps the protocol out of the UI.
 *
 * ## Why the selection comes from a buffer rather than a parameter
 *
 * Only an Activity can launch a file picker, and the picker result can arrive after a configuration
 * change. [selectedFiles] is a process-scoped flow the Activity writes to, so a rotation mid-pick
 * does not silently lose the user's selection - which is the kind of bug that makes an app feel
 * unreliable without ever showing an error.
 */
public class SendViewModel(
    private val trustStore: TrustStore,
    private val coordinator: TransferCoordinator,
    private val selectedFiles: Flow<List<OutgoingFile>>,
    private val onSelectionConsumed: () -> Unit,
    private val onRemoveSelection: (Int) -> Unit,
    private val deviceRepository: DeviceRepository? = null,
) : ViewModel() {

    private var subscription: DiscoverySubscription? = null

    public fun startDiscovery() {
        if (subscription != null) return
        subscription = deviceRepository?.observeNearby(forPairing = false)
    }

    public fun stopDiscovery() {
        subscription?.close()
        subscription = null
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }

    private val _state = MutableStateFlow(SendUiState())
    public val state: StateFlow<SendUiState> = _state.asStateFlow()

    init {
        trustStore.peers
            .onEach { peers ->
                _state.value = _state.value.copy(devices = peers.filterNot { it.revoked })
            }
            .launchIn(viewModelScope)

        selectedFiles
            .onEach { files ->
                _state.value = _state.value.copy(selected = files, outcome = null)
            }
            .launchIn(viewModelScope)

        coordinator.progress
            .onEach { progress -> _state.value = _state.value.copy(progress = progress) }
            .launchIn(viewModelScope)

        coordinator.activeTransfer
            .onEach { active ->
                _state.value = _state.value.copy(
                    activeTransfer = active,
                    isSending = active != null && !active.isTerminal,
                )
            }
            .launchIn(viewModelScope)
    }

    public fun dismissOutcome() {
        _state.value = _state.value.copy(outcome = null)
        coordinator.clearActiveTransfer()
    }

    public fun removeFile(index: Int) {
        onRemoveSelection(index)
    }

    public fun clearFiles() {
        onSelectionConsumed()
    }

    public fun selectTarget(deviceId: DeviceId) {
        _state.value = _state.value.copy(target = deviceId, outcome = null)
    }

    public fun send() {
        val current = _state.value
        val target = current.target ?: return
        if (current.selected.isEmpty() || current.isSending) return

        _state.value = current.copy(isSending = true, outcome = null)

        viewModelScope.launch {
            val targetName = current.devices.firstOrNull { it.deviceId == target }?.name ?: "the device"
            val result = coordinator.sendTo(target, targetName, current.selected)

            _state.value = _state.value.copy(
                isSending = false,
                outcome = describe(result, targetName),
            )

            // Clear the selection only on success. After a failure the user almost certainly wants
            // to retry the same files, and re-picking them is the most annoying possible step.
            if (result is SendResult.Success) onSelectionConsumed()
        }
    }

    public fun cancel() {
        coordinator.cancel()
    }

    private fun describe(result: SendResult, targetName: String): String = when (result) {
        is SendResult.Success -> {
            val failed = result.results.count { !it.isVerified }
            if (failed == 0) {
                "Sent ${result.results.size} file(s), all verified"
            } else {
                // Said plainly. A file that failed its hash check was not published, and the user
                // needs to know that rather than assuming it arrived (REQ-T04).
                "Sent, but $failed file(s) failed verification and were not saved"
            }
        }
        // The receiver's own reason (declined, timeout, busy, error), not a generic decline.
        is SendResult.Declined -> result.reason.message(targetName)
        SendResult.Cancelled -> "Cancelled"
        is SendResult.Failed -> result.reason
    }
}
