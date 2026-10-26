package com.veyro.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veyro.core.data.DeviceRepository
import com.veyro.core.data.DiscoverySubscription
import com.veyro.core.link.LinkMessage
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.model.TrustedPeer
import com.veyro.core.model.NetworkStatus
import com.veyro.core.network.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
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
    /** Paired devices, for the Sefirah-style device cards. */
    trustedPeers: Flow<List<TrustedPeer>> = flowOf(emptyList()),
    /** Opens a session with a paired device at the given endpoints; true on success. */
    private val connectPeer: suspend (deviceId: String, name: String, endpoints: List<Endpoint>) -> Boolean =
        { _, _, _ -> false },
    /** Closes the live session. */
    private val disconnectPeer: () -> Unit = {},
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

        trustedPeers
            .catch { emit(emptyList()) }
            .onEach { peers -> _state.value = _state.value.copy(pairedPeers = peers.filterNot { it.revoked }) }
            .launchIn(viewModelScope)
    }

    /**
     * The card's sync button (Sefirah's DeviceCard): disconnects the connected device, otherwise
     * dials [deviceId] at the endpoints discovery currently reports for it.
     */
    public fun toggleConnection(deviceId: DeviceId) {
        val current = _state.value
        if (current.connectedPeerId == deviceId) {
            disconnectPeer()
            return
        }
        if (current.connectingId != null) return
        val peer = current.pairedPeers.firstOrNull { it.deviceId == deviceId } ?: return
        val endpoints = current.devices.firstOrNull { it.deviceId == deviceId }?.endpoints.orEmpty()
        if (endpoints.isEmpty()) {
            _state.value = current.copy(failedId = deviceId)
            return
        }
        _state.value = current.copy(connectingId = deviceId, failedId = null)
        viewModelScope.launch {
            val ok = try {
                connectPeer(deviceId.value, peer.name, endpoints)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _state.value = _state.value.copy(connectingId = null, failedId = if (ok) null else deviceId)
        }
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

    private var discoveryHolders = 0

    /**
     * Reference-counted [startDiscovery] for screens sharing this ViewModel (Home, Devices, a
     * device's settings). During a navigation transition the incoming screen starts before the
     * outgoing one stops; a plain stop from the outgoing screen would close the window the
     * incoming one still needs.
     */
    public fun acquireDiscovery() {
        discoveryHolders++
        if (discoveryHolders == 1) startDiscovery()
    }

    /** Pairs with [acquireDiscovery]; the window closes when the last holder releases it. */
    public fun releaseDiscovery() {
        if (discoveryHolders == 0) return
        discoveryHolders--
        if (discoveryHolders == 0) stopDiscovery()
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