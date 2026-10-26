package com.veyro.core.model

import kotlinx.serialization.Serializable

/**
 * A device seen on the network right now.
 *
 * Everything in here except [trust] comes from discovery, which is **untrusted input**
 * (REQ-D07). A [PeerDevice] with `trust == TrustState.TRUSTED` only means "this device id is in
 * our trust store"; it does not mean the thing currently advertising that id is the real device.
 * Only a completed handshake proves that, which is why [reachability] and [trust] are separate.
 */
public data class PeerDevice(
    val deviceId: DeviceId,
    /** Name the peer advertises. Absent when the peer is in private mode. Never trusted. */
    val advertisedName: String?,
    /** Name the user gave this device locally. Wins over [advertisedName] in the UI. */
    val localName: String?,
    val platform: Platform,
    val capabilities: Capabilities,
    /** Every address the peer advertised, raced on connect (REQ-D09). */
    val endpoints: List<Endpoint>,
    val trust: TrustState,
    val reachability: Reachability,
    val lastSeenEpochMillis: Long,
    val discoveredVia: DiscoverySource,
) {
    /** What to show in a list row. */
    val displayName: String
        get() = localName ?: advertisedName ?: "Unknown device (${deviceId.short})"

    val isConnectable: Boolean
        get() = trust == TrustState.TRUSTED && endpoints.isNotEmpty()
}

@Serializable
public data class Endpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(port in 1..65535) { "port out of range: $port" }
    }

    override fun toString(): String = "$host:$port"
}

public enum class TrustState {
    /** In the trust store: a `Noise_KK` session can be attempted. */
    TRUSTED,

    /** Seen before but never paired. Requires the full pairing flow. */
    KNOWN_UNPAIRED,

    /** Never seen. Shown only while the user has a pairing window open. */
    UNKNOWN,

    /** Explicitly revoked by the user. Hidden and never auto-connected. */
    REVOKED,
}

public enum class Reachability {
    /** Not probed yet. */
    UNKNOWN,

    /** TCP connect succeeded. */
    REACHABLE,

    /**
     * Advertised but not connectable. The usual cause is AP client isolation; see
     * [NetworkDiagnosis] for the specific classification.
     */
    UNREACHABLE,
}

public enum class DiscoverySource {
    MDNS,
    UDP_BEACON,

    /** Seen by both mechanisms - the healthy case on a normal LAN. */
    BOTH,

    /** From the trust store, not currently advertising. */
    CACHED,
}
