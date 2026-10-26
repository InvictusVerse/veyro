package com.veyro.core.network

import com.veyro.core.model.Endpoint
import com.veyro.core.model.NetworkDiagnosis
import com.veyro.core.model.NetworkStatus
import kotlinx.coroutines.flow.Flow

/**
 * What the device is connected to, and whether a peer is actually reachable.
 *
 * Uses `ConnectivityManager.NetworkCallback` rather than polling, so that an idle app does no
 * work at all (NFR-01, NFR-03).
 */
public interface NetworkMonitor {

    public val status: Flow<NetworkStatus>

    public suspend fun currentStatus(): NetworkStatus

    /**
     * Attempts a TCP connect to each endpoint and returns the first that answers.
     *
     * A null result with a healthy local link is the signature of AP client isolation, which is
     * why this returns the diagnosis as well as the result (REQ-N01, REQ-N02).
     */
    public suspend fun probe(endpoints: List<Endpoint>, timeoutMillis: Long = 1_500): ProbeResult
}

public data class ProbeResult(
    val reachable: Endpoint?,
    val diagnosis: NetworkDiagnosis,
)

/**
 * Enumerates usable local IPv4 addresses, newest-useful-first.
 *
 * Interface selection matters more than it looks: virtual adapters (VirtualBox, VMware, WSL,
 * Docker) and VPN tunnels all present addresses that will never reach a phone on the same Wi-Fi.
 * Advertising one of those in an mDNS record produces a peer that is visible and permanently
 * unreachable, which is exactly the failure mode REQ-N06 exists to prevent.
 */
public interface LocalAddressProvider {
    public suspend fun localAddresses(): List<LocalAddress>
}

public data class LocalAddress(
    val address: String,
    val interfaceName: String,
    val kind: InterfaceKind,
    /** Subnet prefix length, e.g. 24 for a /24. Needed to decide whether a peer is routable. */
    val prefixLength: Int,
    /** Directed broadcast address for this subnet, used by the UDP beacon fallback. */
    val broadcastAddress: String?,
    /**
     * `169.254.0.0/16`. Usually means DHCP failed. Worth surfacing separately, because a
     * link-local address looks valid but almost never reaches a peer.
     */
    val isLinkLocal: Boolean = false,
)

public enum class InterfaceKind {
    WIFI,
    ETHERNET,

    /** This device'"'"'s own soft AP. Peers that joined our hotspot live here. */
    HOTSPOT,
    CELLULAR,

    /** VirtualBox, VMware, WSL, Docker. Never advertise these. */
    VIRTUAL,
    VPN,
    OTHER,
}