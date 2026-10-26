package com.veyro.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veyro.core.data.DeviceRepository
import com.veyro.core.data.DiscoverySubscription
import com.veyro.core.link.LinkMessage
import com.veyro.core.model.DeviceId
import com.veyro.core.model.NetworkStatus
import com.veyro.core.network.NetworkMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Hub state holder.
 *
 * Owns the discovery window. The window opens when the screen starts observing and closes when the
 * ViewModel is cleared, which is what makes NFR-02 ("zero packets when idle") true in practice:
 * leaving the hub stops advertising, stops browsing, and releases the multicast lock.
 */
public class HomeViewModel(
    private val deviceRepository: DeviceRepository,
    private val networkMonitor: NetworkMonitor,
    /** The connected PC's media (ADR-026); null when none. */
    pcMedia: Flow<LinkMessage.MediaState?> = flowOf(null),
    /** The connected PC's own status; null when none. */
    pcStatus: Flow<LinkMessage.DeviceStatus?> = flowOf(null),
    /** Device id of the PC the live session is with. */
    connectedPeerId: Flow<String?> = flowOf(null),
    private val sendMediaCommand: suspend (LinkMessage.MediaCommand) -> Boolean = { false },
) : ViewModel() {

    private companion object {
        /**
         * How long to search before "nothing found" is treated as meaningful.
         *
         * NFR-04 targets a peer visible within 2 s on a quiet LAN, so reporting a problem earlier
         * than that would often be wrong - and an alarming diagnosis that flashes and disappears
         * teaches users to ignore the panel.
         */
        const val GRACE_MILLIS = 3_000L
    }

    private val _state = MutableStateFlow(HomeUiState())
    public val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var subscription: DiscoverySubscription? = null

    init {
        deviceRepository.devices
            .onEach { devices -> _state.value = _state.value.copy(devices = devices) }
            .launchIn(viewModelScope)

        networkMonitor.status
            .onEach { applyNetwork(it) }
            .launchIn(viewModelScope)

        pcMedia
            .onEach { _state.value = _state.value.copy(pcMedia = it) }
            .launchIn(viewModelScope)

        pcStatus
            .onEach { _state.value = _state.value.copy(pcStatus = it) }
            .launchIn(viewModelScope)

        connectedPeerId
            .onEach { _state.value = _state.value.copy(connectedPeerId = DeviceId.parseOrNull(it)) }
            .launchIn(viewModelScope)
    }

    /** Drives the connected PC's media session [id] (ADR-026). Fire and forget. */
    public fun mediaCommand(action: String, id: String? = null, value: Long? = null) {
        viewModelScope.launch {
            sendMediaCommand(LinkMessage.MediaCommand(action = action, id = id, value = value))
        }
    }

    /** Opens the discovery window. Safe to call repeatedly; the second call is a no-op. */
    public fun startDiscovery() {
        if (subscription != null) return
        subscription = deviceRepository.observeNearby(forPairing = false)
        _state.value = _state.value.copy(isSearching = true, searchedLongEnough = false)

        viewModelScope.launch {
            delay(GRACE_MILLIS)
            _state.value = _state.value.copy(searchedLongEnough = true)
        }
    }

    public fun stopDiscovery() {
        subscription?.close()
        subscription = null
        _state.value = _state.value.copy(isSearching = false, searchedLongEnough = false)
    }

    public fun refresh() {
        stopDiscovery()
        startDiscovery()
    }

    private fun applyNetwork(status: NetworkStatus) {
        _state.value = _state.value.copy(
            transport = status.transport,
            hasUsableNetwork = status.hasUsableNetwork,
            isHotspotActive = status.isHotspotActive,
            isVpnActive = status.isVpnActive,
            localAddresses = status.localAddresses,
            diagnosis = status.diagnosis,
        )
    }

    override fun onCleared() {
        // Not optional. Leaving this out is exactly how an app ends up advertising forever and
        // holding a multicast lock while the user thinks it is idle.
        stopDiscovery()
        super.onCleared()
    }
}