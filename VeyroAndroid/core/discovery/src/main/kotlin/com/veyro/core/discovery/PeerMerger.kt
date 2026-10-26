package com.veyro.core.discovery

import com.veyro.core.model.Capabilities
import com.veyro.core.model.DeviceId
import com.veyro.core.model.DiscoverySource
import com.veyro.core.model.Endpoint
import com.veyro.core.model.PeerDevice
import com.veyro.core.model.Platform
import com.veyro.core.model.Reachability
import com.veyro.core.model.TrustState
import com.veyro.core.model.TrustedPeer

/**
 * Merges raw advertisements into the peer list the UI shows.
 *
 * Pure and separated from the Android plumbing because this is where the interesting decisions
 * live, and they are not checkable by reading them:
 *
 *  - **Deduplication by `deviceId`.** The same peer arrives over mDNS *and* UDP, repeatedly, from
 *    several addresses. Without merging, a single PC appears three times in the list.
 *  - **Endpoint union, not replacement.** A peer on both Wi-Fi and Ethernet advertises two
 *    addresses, often via different mechanisms. Keeping both is what lets `probe()` race them
 *    (REQ-D09); keeping only the newest would discard the one that works.
 *  - **Trust comes from the local store, never from the wire.** This is REQ-D07 made structural:
 *    [PeerAdvertisement.trustHint] is ignored outright. An attacker can claim any `deviceId`; what
 *    they cannot do is be in our trust store with a matching key.
 *  - **Source promotion.** A peer seen by both mechanisms is marked `BOTH`, which is the signal
 *    the diagnosis layer uses to tell a healthy LAN from one dropping multicast.
 */
public object PeerMerger {

    /**
     * @param advertisements everything observed in this window, oldest first
     * @param trusted the local trust store
     * @param nowMillis current time, injected so staleness is testable
     * @param staleAfterMillis how long a peer stays listed after its last sighting
     * @param includeUnpaired whether unknown peers are shown at all
     */
    public fun merge(
        advertisements: List<PeerAdvertisement>,
        trusted: List<TrustedPeer>,
        nowMillis: Long,
        staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
        includeUnpaired: Boolean = false,
    ): List<PeerDevice> {
        val trustById = trusted.associateBy { it.deviceId }
        val accumulated = LinkedHashMap<String, Accumulator>()

        for (ad in advertisements) {
            if (nowMillis - ad.observedAtMillis > staleAfterMillis) continue
            val id = DeviceId.parseOrNull(ad.deviceIdRaw) ?: continue

            val acc = accumulated.getOrPut(ad.deviceIdRaw) { Accumulator(id) }
            acc.absorb(ad)
        }

        return accumulated.values.mapNotNull { acc ->
            val record = trustById[acc.deviceId]

            val trust = when {
                record == null -> TrustState.UNKNOWN
                record.revoked -> TrustState.REVOKED
                else -> TrustState.TRUSTED
            }

            // A revoked peer is never shown: the user explicitly removed it, and surfacing it
            // again invites them to re-trust something they rejected.
            if (trust == TrustState.REVOKED) return@mapNotNull null
            if (trust == TrustState.UNKNOWN && !includeUnpaired) return@mapNotNull null

            PeerDevice(
                deviceId = acc.deviceId,
                advertisedName = acc.name,
                // The locally-chosen name wins in the UI: an advertised name is attacker-controlled
                // and must never override what the user called their own device (REQ-U04).
                localName = record?.name,
                platform = acc.platform,
                capabilities = acc.capabilities,
                endpoints = acc.endpoints.toList(),
                trust = trust,
                reachability = Reachability.UNKNOWN,
                lastSeenEpochMillis = acc.lastSeen,
                discoveredVia = acc.source(),
            )
        }.sortedWith(
            // Trusted first, then most recently seen. A user looking for their own PC should not
            // have to scan past strangers.
            compareBy({ if (it.trust == TrustState.TRUSTED) 0 else 1 }, { -it.lastSeenEpochMillis }),
        )
    }

    public const val DEFAULT_STALE_AFTER_MILLIS: Long = 30_000

    private class Accumulator(val deviceId: DeviceId) {
        var name: String? = null
        var platform: Platform = Platform.UNKNOWN
        var capabilities: Capabilities = Capabilities.NONE
        private val endpointsByHost = LinkedHashMap<String, Pair<Int, Long>>()
        var lastSeen: Long = 0
        var viaMdns = false
        var viaBeacon = false

        val endpoints: List<Endpoint>
            get() = endpointsByHost.entries
                .sortedByDescending { it.value.second }
                .map { Endpoint(it.key, it.value.first) }

        fun absorb(ad: PeerAdvertisement) {
            // Last non-null name wins: a peer that switches from private to open mode should start
            // showing its name without waiting for the entry to expire.
            ad.name?.let { name = it }
            if (ad.platformRaw != null) {
                val parsed = Platform.fromWire(ad.platformRaw)
                if (parsed != Platform.UNKNOWN) platform = parsed
            }
            if (ad.capabilityBits != 0) capabilities = Capabilities(ad.capabilityBits)

            // Multi-homed peers have distinct hosts (e.g. Wi-Fi and Ethernet), so keep distinct hosts.
            // On the same host, the newer port replaces any older port, preventing dials to closed ephemeral ports.
            for (ep in ad.endpoints) {
                val existing = endpointsByHost[ep.host]
                if (existing == null || ad.observedAtMillis >= existing.second) {
                    endpointsByHost[ep.host] = ep.port to ad.observedAtMillis
                }
            }
            if (ad.observedAtMillis > lastSeen) lastSeen = ad.observedAtMillis

            when (ad.source) {
                DiscoverySource.MDNS -> viaMdns = true
                DiscoverySource.UDP_BEACON -> viaBeacon = true
                DiscoverySource.BOTH -> { viaMdns = true; viaBeacon = true }
                DiscoverySource.CACHED -> Unit
            }
        }

        fun source(): DiscoverySource = when {
            viaMdns && viaBeacon -> DiscoverySource.BOTH
            viaMdns -> DiscoverySource.MDNS
            viaBeacon -> DiscoverySource.UDP_BEACON
            else -> DiscoverySource.CACHED
        }
    }
}

/**
 * Matches an advertised presence token against stored pairing secrets (04-PROTOCOL.md section 3.4).
 *
 * This is how a device in **private mode** - advertising no name - is still recognised as one of
 * yours. The advertiser publishes `HMAC(pairingSecret, "veyro-presence-v1" ‖ epoch)[0..7]`; a
 * browser recomputes it for each stored peer and looks for a match.
 *
 * Both the current and previous epoch are tried, because the epoch is a one-minute bucket and two
 * devices with slightly different clocks would otherwise fail to recognise each other for part of
 * every minute.
 *
 * Cost is `2 x pairedPeers` HMACs per observed advertisement, which ADR-007 accepts for realistic
 * peer counts and flags for benchmarking before private mode becomes the default.
 */
public object PresenceTokenMatcher {

    /**
     * @param tokenProvider computes the expected token, injected so this stays free of a crypto
     * dependency and testable with a trivial fake
     * @return the matching peer, or null when the token belongs to nobody we know
     */
    public fun match(
        advertisedToken: ByteArray,
        trusted: List<TrustedPeer>,
        unixSeconds: Long,
        tokenProvider: (secret: ByteArray, epoch: Long) -> ByteArray,
    ): TrustedPeer? {
        if (advertisedToken.size != 8) return null
        val epoch = unixSeconds / 60L

        for (peer in trusted) {
            if (peer.revoked) continue
            for (candidateEpoch in longArrayOf(epoch, epoch - 1)) {
                val expected = tokenProvider(peer.pairingSecret, candidateEpoch)
                if (constantTimeEquals(expected, advertisedToken)) return peer
            }
        }
        return null
    }

    /**
     * Constant-time compare.
     *
     * A short-circuiting comparison here would leak how many leading token bytes matched, which
     * over many observed advertisements is enough to recover a valid token and impersonate a known
     * device in the list.
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
