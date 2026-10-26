package com.veyro.core.discovery

import kotlinx.coroutines.flow.Flow

/**
 * The mDNS half of discovery: advertise `_veyro._tcp` and browse for it.
 *
 * Split from the UDP beacon code because the two have different failure modes and different
 * lifecycle requirements, while sharing the same deduplication and trust overlay. Keeping them
 * as two collaborators of [DiscoveryManager] keeps that seam visible.
 *
 * Three implementation requirements, each of which is a bug that is invisible until it bites
 * (all three were learned the hard way in the Invox ecosystem code - see
 * ProjectInfo/11-INVOX-FINDINGS.md):
 *
 *  1. **Hold a `MulticastLock` for the lifetime of the window.** Without it, many OEM ROMs drop
 *     inbound multicast once the device dozes, and discovery fails with no error at all.
 *     Requires the `CHANGE_WIFI_MULTICAST_STATE` permission.
 *
 *  2. **Serialise `NsdManager.resolveService` calls through a queue.** The platform service
 *     will not resolve two services concurrently; issuing a second resolve while one is in
 *     flight wedges both.
 *
 *  3. **Prefer the advertiser's own address from the TXT record over `resolved.host`.** The
 *     advertiser knows which interface it meant; the resolved host can be a link-local or VPN
 *     address that will never be reachable, producing a peer that is permanently visible and
 *     permanently unconnectable - the exact failure REQ-N06 exists to prevent.
 */
public interface MdnsAdvertiser {

    /**
     * Registers the service on [port].
     *
     * [displayName] is omitted from the TXT record in private mode so that a stranger on a
     * public network cannot harvest the user's device name (REQ-D06).
     */
    public suspend fun start(port: Int, deviceId: String, displayName: String?)

    public suspend fun stop()

    public val isRunning: Boolean
}

public interface MdnsBrowser {

    /** Advertisements as they resolve. Unresolvable services are dropped, not emitted. */
    public val advertisements: Flow<PeerAdvertisement>

    /** Service names lost via mDNS (e.g. "veyro-15SBN9RA"). */
    public val lostServices: Flow<String>

    public suspend fun start()

    public suspend fun stop()

    public val isRunning: Boolean
}
