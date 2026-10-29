package com.veyro.ui.home

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veyro.core.data.DeviceRepository
import com.veyro.core.data.DiscoverySubscription
import com.veyro.core.link.PeerMessage
import com.veyro.core.model.Capabilities
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.model.NetworkStatus
import com.veyro.core.model.Transfer
import com.veyro.core.model.TrustedPeer
import com.veyro.core.network.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Hub state holder.
 *
 * Owns the discovery window. The window opens when the screen starts observing and closes when the
 * ViewModel is cleared, which is what makes NFR-02 ("zero packets when idle") true in practice:
 * leaving the hub stops advertising, stops browsing, and releases the multicast lock.
 *
 * Since ADR-038 it also feeds the Phone Link-style Home: the selected PC with its wallpaper and
 * reported status, Lock PC, and the newest transfer for the Recent card.
 */
public class HomeViewModel(
    private val deviceRepository: DeviceRepository,
    private val networkMonitor: NetworkMonitor,
    /** Each connected PC's media (ADR-026), by device id. */
    pcMedia: Flow<Map<String, PeerMessage.MediaState>> = flowOf(emptyMap()),
    /** Each connected PC's own status, by device id. */
    pcStatus: Flow<Map<String, PeerMessage.DeviceStatus>> = flowOf(emptyMap()),
    /** Device ids of the PCs with a live session (multiple PCs). */
    connectedPeerIds: Flow<Set<String>> = flowOf(emptySet()),
    /** Devices the user disconnected here; they stay off until Connect (ADR-053). */
    userDisconnectedIds: Flow<Set<String>> = flowOf(emptySet()),
    /** Sends a media command to the PC with this device id; true if it was sent. */
    private val sendMediaCommand: suspend (deviceId: String, PeerMessage.MediaCommand) -> Boolean = { _, _ -> false },
    /** Paired devices, for the device cards and the PC switcher. */
    trustedPeers: Flow<List<TrustedPeer>> = flowOf(emptyList()),
    /** Opens a session with a paired device at the given endpoints; true on success. */
    private val connectPeer: suspend (deviceId: String, name: String, endpoints: List<Endpoint>) -> Boolean =
        { _, _, _ -> false },
    /** Closes the live session with this PC only; other PCs stay connected. */
    private val disconnectPeer: (deviceId: String) -> Unit = {},
    /** Lifts a Disconnect (ADR-053), so the device may reconnect by itself when it shows up. */
    private val resumePeer: suspend (deviceId: String) -> Unit = {},
    /** What each connected PC advertised, by device id. */
    peerCapabilities: Flow<Map<String, Capabilities>> = flowOf(emptyMap()),
    /** The last wallpaper each PC sent, by device id (ADR-038). */
    pcWallpapers: Flow<Map<String, ByteArray>> = flowOf(emptyMap()),
    /** Transfer history, newest first, for the Recent card. */
    history: Flow<List<Transfer>> = flowOf(emptyList()),
    /** Sends `pcLock` over that PC's live session; true if it was sent. */
    private val lockPc: suspend (deviceId: String) -> Boolean = { false },
    /** The switcher's choice, so the connection layer's selected PC follows Home. */
    private val onPcSelected: (deviceId: String) -> Unit = {},
    /** Drops one entry from the transfer history. */
    private val removeHistory: suspend (transferId: String) -> Unit = {},
    /** A small preview of a received image, read off the main thread by `:app`. */
    private val loadThumbnail: suspend (uri: String) -> ImageBitmap? = { null },
) : ViewModel() {

    /** For the Recent card's image preview. */
    public suspend fun thumbnail(uri: String): ImageBitmap? = loadThumbnail(uri)

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
            .onEach { devices -> _state.update { it.copy(devices = devices) } }
            .launchIn(viewModelScope)

        networkMonitor.status
            .onEach { applyNetwork(it) }
            .launchIn(viewModelScope)

        pcMedia
            .catch { emit(emptyMap()) }
            .onEach { media -> _state.update { it.copy(pcMediaByPeer = media) } }
            .launchIn(viewModelScope)

        pcStatus
            .catch { emit(emptyMap()) }
            .onEach { statuses -> _state.update { it.copy(pcStatuses = statuses) } }
            .launchIn(viewModelScope)

        connectedPeerIds
            .catch { emit(emptySet()) }
            .onEach { ids ->
                _state.update { it.copy(connectedPeerIds = ids.mapNotNull(DeviceId::parseOrNull).toSet()) }
            }
            .launchIn(viewModelScope)

        userDisconnectedIds
            .catch { emit(emptySet()) }
            .onEach { ids ->
                _state.update { it.copy(userDisconnectedIds = ids.mapNotNull(DeviceId::parseOrNull).toSet()) }
            }
            .launchIn(viewModelScope)

        trustedPeers
            .catch { emit(emptyList()) }
            .onEach { peers -> _state.update { it.copy(pairedPeers = peers.filterNot { p -> p.revoked }) } }
            .launchIn(viewModelScope)

        peerCapabilities
            .catch { emit(emptyMap()) }
            .onEach { caps -> _state.update { it.copy(peerCapabilities = caps) } }
            .launchIn(viewModelScope)

        pcWallpapers
            .catch { emit(emptyMap()) }
            .onEach { wallpapers -> _state.update { it.copy(pcWallpapers = wallpapers) } }
            .launchIn(viewModelScope)

        history
            .catch { emit(emptyList()) }
            .onEach { transfers -> _state.update { it.copy(history = transfers) } }
            .launchIn(viewModelScope)
    }

    /** The PC switcher on Home. The hero, Lock PC, media and Send files follow it. */
    public fun selectPc(deviceId: DeviceId) {
        _state.update { it.copy(selectedId = deviceId) }
        onPcSelected(deviceId.value)
    }

    /**
     * The card's sync button: disconnects [deviceId] if it is connected (other PCs stay
     * connected), otherwise dials it at the endpoints discovery currently reports for it.
     */
    public fun toggleConnection(deviceId: DeviceId) {
        val current = _state.value
        if (deviceId in current.connectedPeerIds) {
            disconnectPeer(deviceId.value)
            return
        }
        if (current.connectingId != null) return
        val peer = current.pairedPeers.firstOrNull { it.deviceId == deviceId } ?: return
        val endpoints = current.devices.firstOrNull { it.deviceId == deviceId }?.endpoints.orEmpty()
        if (endpoints.isEmpty()) {
            if (deviceId in current.userDisconnectedIds) {
                // Not visible right now (a phone is silent in the background): lift the
                // Disconnect, and it reconnects on its own when it is around (ADR-053).
                viewModelScope.launch { resumePeer(deviceId.value) }
                _state.update { it.copy(message = "${peer.name} will connect when it is nearby") }
            } else {
                _state.update { it.copy(failedId = deviceId) }
            }
            return
        }
        _state.update { it.copy(connectingId = deviceId, failedId = null) }
        viewModelScope.launch {
            val ok = try {
                connectPeer(deviceId.value, peer.name, endpoints)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _state.update { it.copy(connectingId = null, failedId = if (ok) null else deviceId) }
        }
    }

    /** The Lock PC tile (ADR-038). A no-op unless [HomeUiState.canLockPc]. */
    public fun lockSelectedPc() {
        val current = _state.value
        val pc = current.selectedPc ?: return
        if (!current.canLockPc) return
        viewModelScope.launch {
            val sent = try {
                lockPc(pc.deviceId.value)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _state.update { it.copy(message = if (sent) "Locking ${pc.name}" else "Could not reach ${pc.name}") }
        }
    }

    /** "Delete from history" on the Recent card. The file itself stays where it is. */
    public fun removeFromHistory(transferId: String) {
        viewModelScope.launch {
            runCatching { removeHistory(transferId) }
            _state.update { it.copy(message = "Removed from history") }
        }
    }

    /** The snackbar showed [HomeUiState.message]. */
    public fun messageShown() {
        _state.update { it.copy(message = null) }
    }

    /** Drives the selected PC's media session [id] (ADR-026). Fire and forget. */
    public fun mediaCommand(action: String, id: String? = null, value: Long? = null) {
        val pc = _state.value.selectedPc?.takeIf { it.isConnected } ?: return
        viewModelScope.launch {
            sendMediaCommand(pc.deviceId.value, PeerMessage.MediaCommand(action = action, id = id, value = value))
        }
    }

    /**
     * What Send files sends to when no question is needed: the selected PC if it is connected,
     * else the one connected PC, else the selected (possibly offline) PC, which the coordinator
     * dials. Null when nothing is paired.
     */
    public fun defaultSendTarget(): DeviceId? {
        val current = _state.value
        val selected = current.selectedPc ?: return null
        if (selected.isConnected) return selected.deviceId
        return current.connectedDevices.singleOrNull()?.deviceId ?: selected.deviceId
    }

    /** Opens the discovery window. Safe to call repeatedly; the second call is a no-op. */
    public fun startDiscovery() {
        if (subscription != null) return
        subscription = deviceRepository.observeNearby(forPairing = false)
        _state.update { it.copy(isSearching = true, searchedLongEnough = false) }

        viewModelScope.launch {
            delay(GRACE_MILLIS)
            _state.update { it.copy(searchedLongEnough = true) }
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
        _state.update { it.copy(isSearching = false, searchedLongEnough = false) }
    }

    public fun refresh() {
        stopDiscovery()
        startDiscovery()
    }

    private fun applyNetwork(status: NetworkStatus) {
        _state.update {
            it.copy(
                transport = status.transport,
                hasUsableNetwork = status.hasUsableNetwork,
                isHotspotActive = status.isHotspotActive,
                isVpnActive = status.isVpnActive,
                localAddresses = status.localAddresses,
                diagnosis = status.diagnosis,
            )
        }
    }

    override fun onCleared() {
        // Not optional. Leaving this out is exactly how an app ends up advertising forever and
        // holding a multicast lock while the user thinks it is idle.
        stopDiscovery()
        super.onCleared()
    }
}
