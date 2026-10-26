package com.veyro.ui.home

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
) {
    val trustedDevices: List<PeerDevice>
        get() = devices.filter { it.trust == com.veyro.core.model.TrustState.TRUSTED }

    val otherDevices: List<PeerDevice>
        get() = devices.filterNot { it.trust == com.veyro.core.model.TrustState.TRUSTED }

    /** Show the diagnosis panel only when there is genuinely something wrong to report. */
    val shouldExplainEmptiness: Boolean
        get() = devices.isEmpty() && searchedLongEnough
}