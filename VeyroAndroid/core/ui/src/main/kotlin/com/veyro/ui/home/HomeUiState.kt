package com.veyro.ui.home

import com.veyro.core.link.LinkMessage
import com.veyro.core.model.DeviceId
import com.veyro.core.model.NetworkDiagnosis
import com.veyro.core.model.NetworkTransport
import com.veyro.core.model.PeerDevice
import com.veyro.core.model.Platform
import com.veyro.core.model.TrustState
import com.veyro.core.model.TrustedPeer

/** Where a paired device stands right now, for the device cards (Home and Devices). */
public enum class ConnectionStatus {
    /** The live session is with this device. */
    CONNECTED,

    /** A connect attempt started from the card is running. */
    CONNECTING,

    /** Discovery currently sees it, so Connect can be tried. */
    NEARBY,

    /** Paired, but not seen on the network. */
    OFFLINE,
}

/**
 * One paired device as the Sefirah-style cards show it: name, connection state and, for the
 * connected PC, the battery it reports (`devStatus`, ADR-026).
 */
public data class PairedDeviceUi(
    val deviceId: DeviceId,
    val name: String,
    val platform: Platform,
    val status: ConnectionStatus,
    val battery: Int? = null,
    val charging: Boolean? = null,
    /** The last Connect from the card for this device failed. */
    val connectFailed: Boolean = false,
    val lastConnectedEpochMillis: Long = 0L,
    val pairedAtEpochMillis: Long = 0L,
) {
    val isConnected: Boolean get() = status == ConnectionStatus.CONNECTED

    /** Connect can only be tried for a device discovery currently sees. */
    val canToggleConnection: Boolean
        get() = status == ConnectionStatus.CONNECTED || status == ConnectionStatus.NEARBY
}

/**
 * Everything the hub renders.
 *
 * One immutable object rather than several separately-collected flows, so the screen can never show
 * an inconsistent combination - "no devices found" next to a stale "searching" spinner, say.
 */
public data class HomeUiState(
    val isSearching: Boolean = false,
    val devices: List<PeerDevice> = emptyList(),
    val transport: NetworkTransport = NetworkTransport.NONE,
    val hasUsableNetwork: Boolean = false,
    val isHotspotActive: Boolean = false,
    val isVpnActive: Boolean = false,
    val localAddresses: List<String> = emptyList(),
    val diagnosis: NetworkDiagnosis = NetworkDiagnosis.OK,

    /**
     * True once we have been searching long enough that "nothing found" is meaningful rather than
     * just early. Without this the empty state flashes an alarming diagnosis for the first second
     * of every visit.
     */
    val searchedLongEnough: Boolean = false,

    /** Device id of the PC the live session is with, if any. */
    val connectedPeerId: DeviceId? = null,

    /** The connected PC's media sessions (ADR-026). Null when no capable PC is connected. */
    val pcMedia: LinkMessage.MediaState? = null,

    /** The connected PC's own status (battery etc.), when it reports one. */
    val pcStatus: LinkMessage.DeviceStatus? = null,

    /** Paired devices from the trust store, revoked ones excluded. */
    val pairedPeers: List<TrustedPeer> = emptyList(),

    /** Device a Connect from the card is running for. */
    val connectingId: DeviceId? = null,

    /** Device whose last Connect from the card failed. */
    val failedId: DeviceId? = null,
) {
    /** The PC session the media card shows: the playing one, else the first. */
    val pcMediaSession: LinkMessage.MediaSessionInfo?
        get() = pcMedia?.sessions?.let { sessions -> sessions.firstOrNull { it.playing } ?: sessions.firstOrNull() }

    /** Status line extras for [device], present only for the connected PC. */
    public fun statusFor(device: PeerDevice): LinkMessage.DeviceStatus? =
        pcStatus?.takeIf { connectedPeerId != null && device.deviceId == connectedPeerId }

    val trustedDevices: List<PeerDevice>
        get() = devices.filter { it.trust == TrustState.TRUSTED }

    val otherDevices: List<PeerDevice>
        get() = devices.filterNot { it.trust == TrustState.TRUSTED }

    /** Show the diagnosis panel only when there is genuinely something wrong to report. */
    val shouldExplainEmptiness: Boolean
        get() = devices.isEmpty() && searchedLongEnough

    /** Paired devices for the cards, the connected one first. */
    val pairedDevices: List<PairedDeviceUi>
        get() = pairedPeers
            .map { peer ->
                val connected = connectedPeerId == peer.deviceId
                val status = when {
                    connected -> ConnectionStatus.CONNECTED
                    connectingId == peer.deviceId -> ConnectionStatus.CONNECTING
                    devices.any { it.deviceId == peer.deviceId && it.endpoints.isNotEmpty() } -> ConnectionStatus.NEARBY
                    else -> ConnectionStatus.OFFLINE
                }
                PairedDeviceUi(
                    deviceId = peer.deviceId,
                    name = peer.name,
                    platform = peer.platform,
                    status = status,
                    battery = if (connected) pcStatus?.battery else null,
                    charging = if (connected) pcStatus?.charging else null,
                    connectFailed = failedId == peer.deviceId && !connected,
                    lastConnectedEpochMillis = peer.lastConnectedEpochMillis,
                    pairedAtEpochMillis = peer.pairedAtEpochMillis,
                )
            }
            .sortedBy { if (it.isConnected) 0 else 1 }

    /** The connected device's card, if any, for the quick actions. */
    val connectedDevice: PairedDeviceUi?
        get() = pairedDevices.firstOrNull { it.isConnected }
}
