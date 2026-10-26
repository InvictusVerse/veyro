package com.veyro.ui.home

import com.veyro.core.link.LinkMessage
import com.veyro.core.model.DeviceId
import com.veyro.core.model.NetworkDiagnosis
import com.veyro.core.model.NetworkTransport
import com.veyro.core.model.PeerDevice

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
) {
    /** The PC session the media card shows: the playing one, else the first. */
    val pcMediaSession: LinkMessage.MediaSessionInfo?
        get() = pcMedia?.sessions?.let { sessions -> sessions.firstOrNull { it.playing } ?: sessions.firstOrNull() }

    /** Status line extras for [device], present only for the connected PC. */
    public fun statusFor(device: PeerDevice): LinkMessage.DeviceStatus? =
        pcStatus?.takeIf { connectedPeerId != null && device.deviceId == connectedPeerId }

    val trustedDevices: List<PeerDevice>
        get() = devices.filter { it.trust == com.veyro.core.model.TrustState.TRUSTED }

    val otherDevices: List<PeerDevice>
        get() = devices.filterNot { it.trust == com.veyro.core.model.TrustState.TRUSTED }

    /** Show the diagnosis panel only when there is genuinely something wrong to report. */
    val shouldExplainEmptiness: Boolean
        get() = devices.isEmpty() && searchedLongEnough
}