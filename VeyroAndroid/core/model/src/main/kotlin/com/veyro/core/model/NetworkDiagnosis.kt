package com.veyro.core.model

/**
 * Why discovery or a connection did not work.
 *
 * The whole point of this type is REQ-N06: the app must never show a bare "device not found".
 * Every failure gets a named cause and a concrete remediation, because on a hotspot the usual
 * cause is an AP setting the user can actually change.
 */
public enum class NetworkDiagnosis {

    /** Everything works. */
    OK,

    /**
     * The peer is advertising, but TCP connect fails to every address it published while the
     * local link is up. That signature is AP client isolation: the access point forwards each
     * client to the internet but refuses to bridge client-to-client traffic.
     */
    AP_ISOLATED,

    /**
     * mDNS produced nothing but UDP directed broadcast did (or the reverse). Handled
     * automatically by falling back; only surfaced if both mechanisms fail.
     */
    MULTICAST_BLOCKED,

    /**
     * A VPN holds the default route and the local subnet is not reachable through it.
     * Extremely common and almost never guessed correctly by users.
     */
    VPN_INTERFERENCE,

    /** No connected Wi-Fi or Ethernet transport at all. */
    NO_NETWORK,

    /** The host is reachable but refuses the port - typically a Windows firewall profile. */
    PEER_FIREWALL,

    /** The peer's address is not in any local subnet and there is no route to it. */
    SUBNET_MISMATCH,

    /** Reachable, but the handshake failed. A key mismatch, not a network problem. */
    AUTHENTICATION_FAILED,
    ;

    public val isNetworkProblem: Boolean
        get() = this != OK && this != AUTHENTICATION_FAILED
}

/** A snapshot of what the device is connected to. */
public data class NetworkStatus(
    val hasUsableNetwork: Boolean,
    val transport: NetworkTransport,
    /** True when this device is itself running a hotspot that peers can join. */
    val isHotspotActive: Boolean,
    /** True when a VPN transport is present, which is the usual cause of LAN failures. */
    val isVpnActive: Boolean,
    val localAddresses: List<String>,
    val diagnosis: NetworkDiagnosis,
)

public enum class NetworkTransport {
    WIFI,
    ETHERNET,

    /** This device's own hotspot (soft AP). Peers joining it are on our subnet. */
    HOTSPOT,

    /** Cellular only. Peer-to-peer LAN discovery cannot work here. */
    CELLULAR,
    NONE,
}

/** Whether Veyro is currently discoverable, and why. Idle must mean genuinely idle (NFR-02). */
public enum class DiscoveryWindowState {
    /** No advertising, no browsing, no packets, no multicast lock. */
    CLOSED,

    /** Open because the user has the app in the foreground. */
    FOREGROUND,

    /** Open because a transfer is in progress. */
    TRANSFER_ACTIVE,

    /** Open because the user explicitly enabled "always discoverable". */
    ALWAYS_ON,

    /** Open and accepting unpaired peers. Bounded to 120 seconds (04-PROTOCOL.md section 4.6). */
    PAIRING,
}
