package com.veyro.core.discovery

import com.veyro.core.model.DiscoverySource
import com.veyro.core.model.Endpoint
import com.veyro.core.model.PeerDevice
import com.veyro.core.model.TrustState
import kotlinx.coroutines.flow.StateFlow

/**
 * Discovers peers on the local network.
 *
 * Implementations combine two mechanisms inside one bounded window (ProjectInfo/04-PROTOCOL.md
 * section 3):
 *
 *  - mDNS / DNS-SD service `_veyro._tcp` - primary, well-behaved on normal LANs
 *  - UDP multicast + per-interface directed broadcast on 239.255.42.99:47653 - fallback for
 *    hotspot drivers and firewall profiles that drop multicast
 *
 * Both run concurrently and are deduplicated by deviceId. The reason for two is not paranoia:
 * each one fails on a different class of access point, and neither failure produces an error -
 * you just see nothing, which is the bug report "it doesn't find my PC".
 *
 * Discovery is deliberately *windowed*. NFR-02 requires zero packets sent when no window is
 * open; that is not an optimisation, it is what keeps idle battery and network cost at zero.
 */
public interface DiscoveryManager {

    /** Peers currently visible, deduplicated and merged with the trust store. */
    public val peers: StateFlow<List<PeerDevice>>

    /** Whether a window is currently open, and why. */
    public val windowState: StateFlow<DiscoveryWindow>

    /**
     * Opens a window that lives as long as the returned handle is held.
     *
     * This is a resource, not a flag: closing the handle stops advertising, stops browsing and
     * releases the multicast lock (REQ-D05). It is [AutoCloseable] so `use {}` scopes it to a
     * screen or a transfer, and forgetting to close it is visible rather than silent.
     */
    public fun openWindow(request: DiscoveryRequest): DiscoveryHandle
}

public data class DiscoveryRequest(
    val reason: DiscoveryReason,
    /** `null` means "as long as the holder lives". Only `PAIRING` is hard-capped. */
    val timeoutMillis: Long? = null,
    /** Unpaired peers are hidden unless the user is explicitly pairing. */
    val includeUnpaired: Boolean = false,
)

public enum class DiscoveryReason {
    /** The user is looking at the nearby list. */
    FOREGROUND,

    /** A transfer is in progress and needs its peer. */
    TRANSFER,

    /** The "pair a new device" flow - bounded to `ProtocolConstants.PAIRING_WINDOW_MILLIS`. */
    PAIRING,

    /** The user turned on "always discoverable". Off by default. */
    ALWAYS_ON,
}

public data class DiscoveryWindow(
    val isOpen: Boolean,
    val reason: DiscoveryReason? = null,
    val openedAtMillis: Long = 0L,
)

public interface DiscoveryHandle : AutoCloseable {
    override fun close()
}

/**
 * A raw advertisement decoded from mDNS or a UDP beacon, before any trust is applied.
 *
 * Every field here is attacker-controlled (REQ-D07). `trustHint` is exactly that - a hint,
 * computed locally by matching the advertised presence token against stored pairing secrets. It
 * is never proof of identity; only a completed handshake is.
 */
public data class PeerAdvertisement(
    val deviceIdRaw: String,
    val name: String?,
    val platformRaw: String?,
    val version: Int,
    val capabilityBits: Int,
    val endpoints: List<Endpoint>,
    val trustHint: TrustState,
    val source: DiscoverySource,
    val observedAtMillis: Long,
)
