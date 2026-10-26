package com.veyro.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veyro.core.data.DeviceRepository
import com.veyro.core.data.DiscoverySubscription
import com.veyro.core.model.ActiveTransfer
import com.veyro.core.model.TransferProgress
import com.veyro.core.link.IncomingOffer
import com.veyro.core.link.TransferCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

/**
 * Receive flow: authorize an offer, then watch it arrive.
 *
 * ## Why the prompt shows a summary, not a file list
 *
 * REQ-U04 is about the user being able to tell what they are accepting. A list of 2000 filenames is
 * not a decision aid - it is a wall the user scrolls past before tapping Accept. So the prompt shows
 * who is sending, how many files, and the total size, and the *top-level* names only.
 *
 * ## Nothing is written before the user answers
 *
 * This is the important property, and it is structural rather than a convention: the coordinator
 * does not call into the engine until [TransferCoordinator.respondToOffer] has been called. There is
 * no code path where a file lands on disk because the user did not tap fast enough.
 */
public class ReceiveViewModel(
    private val coordinator: TransferCoordinator,
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

    public data class UiState(
        val pendingOffer: IncomingOffer? = null,
        val progress: TransferProgress? = null,
        val activeTransfer: ActiveTransfer? = null,
        val lastOutcome: String? = null,
        val destination: File? = null,
        val isReceiving: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    public val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        coordinator.pendingOffer
            .onEach { offer ->
                // Only the newest offer is surfaced. Two simultaneous offers would mean two prompts
                // competing for one answer, and the second would be answered by the first tap.
                _state.value = _state.value.copy(pendingOffer = offer, lastOutcome = if (offer != null) null else _state.value.lastOutcome)
            }
            .launchIn(viewModelScope)

        coordinator.progress
            .onEach { progress ->
                _state.value = _state.value.copy(
                    progress = progress,
                    isReceiving = progress.state == com.veyro.core.model.TransferState.TRANSFERRING,
                )
            }
            .launchIn(viewModelScope)

        coordinator.activeTransfer
            .onEach { active ->
                _state.value = _state.value.copy(
                    activeTransfer = active,
                    isReceiving = active != null && !active.isTerminal,
                )
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            _state.value = _state.value.copy(destination = coordinator.destinationDirectory())
        }
    }

    public fun accept() {
        val offer = _state.value.pendingOffer ?: return
        _state.value = _state.value.copy(pendingOffer = null, lastOutcome = "Receiving...")
        viewModelScope.launch {
            coordinator.respondToOffer(offer.transferId, accept = true)
        }
    }

    public fun decline() {
        val offer = _state.value.pendingOffer ?: return
        _state.value = _state.value.copy(
            pendingOffer = null,
            lastOutcome = "Declined ${offer.summary()} from ${offer.peerName}",
        )
        viewModelScope.launch {
            coordinator.respondToOffer(offer.transferId, accept = false)
        }
    }

    public fun dismissOutcome() {
        _state.value = _state.value.copy(lastOutcome = null, progress = null)
        coordinator.clearActiveTransfer()
    }
}
