package com.veyro.core.discovery

import kotlinx.coroutines.flow.Flow

/**
 * The UDP half of discovery: multicast to 239.255.42.99:47653 plus a directed broadcast on each
 * usable interface.
 *
 * This is the fallback that makes the hotspot scenarios work (ProjectInfo/04-PROTOCOL.md
 * section 3.2). Several common access points and hotspot drivers drop multicast while happily
 * forwarding directed broadcast, and some do the reverse, so both are sent.
 *
 * Rate limits are part of the contract, not a tuning detail:
 *  - at most one beacon per second, and only while a discovery window is open
 *  - a `QUERY` is answered with a **unicast** `ANNOUNCE` to the sender
 *
 * The unicast reply matters: answering a query with a broadcast means one device joining a busy
 * network triggers a reply storm from every other device, which is both a battery problem and a
 * trivially abusable amplifier.
 */
public interface UdpBeacon {

    public val advertisements: Flow<PeerAdvertisement>

    /** Binds the socket and starts answering queries, advertising [port] as the TCP port. */
    public suspend fun start(port: Int)

    public suspend fun stop()

    /** Sends one `QUERY`. Used when a window opens, to avoid waiting for the next announce. */
    public suspend fun sendQuery()

    public val isRunning: Boolean
}
