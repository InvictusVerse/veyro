package com.veyro.core.discovery

import com.veyro.core.model.Capabilities
import com.veyro.core.model.DeviceId
import com.veyro.core.model.DiscoverySource
import com.veyro.core.model.Endpoint
import com.veyro.core.model.Platform
import com.veyro.core.model.TrustState
import com.veyro.core.model.TrustedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Peer merging and presence-token matching.
 *
 * These are the parts of discovery with real decisions in them, kept pure so they can be tested
 * without a network. The Android plumbing around them is thin by design.
 */
class PeerMergerTest {

    private val now = 1_700_000_000_000L

    private fun idFor(seed: Int): DeviceId =
        DeviceId.fromPublicKey(ByteArray(32) { (it + seed).toByte() })

    private fun ad(
        id: DeviceId,
        host: String = "192.168.1.20",
        port: Int = 40000,
        name: String? = "PC",
        source: DiscoverySource = DiscoverySource.MDNS,
        at: Long = now,
        caps: Int = Capabilities.ANDROID_CURRENT.bits,
        platform: String? = "windows",
        trustHint: TrustState = TrustState.UNKNOWN,
    ) = PeerAdvertisement(
        deviceIdRaw = id.value,
        name = name,
        platformRaw = platform,
        version = 1,
        capabilityBits = caps,
        endpoints = listOf(Endpoint(host, port)),
        trustHint = trustHint,
        source = source,
        observedAtMillis = at,
    )

    private fun trusted(id: DeviceId, name: String = "My PC", revoked: Boolean = false): TrustedPeer {
        val seed = ByteArray(32) { 7 }
        return TrustedPeer(
            deviceId = id,
            // Not self-consistent, but TrustedPeer only validates sizes; the merger does not check
            // key commitment, and does not need to - the handshake does.
            staticPublicKey = ByteArray(32) { 1 },
            pairingSecret = seed,
            name = name,
            platform = Platform.WINDOWS,
            pairedAtEpochMillis = now - 1000,
            revoked = revoked,
        )
    }

    // ── Deduplication ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the same peer seen repeatedly appears once`() {
        val id = idFor(1)
        val merged = PeerMerger.merge(
            advertisements = listOf(ad(id), ad(id), ad(id)),
            trusted = listOf(trusted(id)),
            nowMillis = now,
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun `a peer seen by both mechanisms is marked BOTH`() {
        // This is the signal the diagnosis layer uses to tell a healthy LAN from one dropping
        // multicast, so it has to survive the merge.
        val id = idFor(1)
        val merged = PeerMerger.merge(
            advertisements = listOf(
                ad(id, source = DiscoverySource.MDNS),
                ad(id, source = DiscoverySource.UDP_BEACON),
            ),
            trusted = listOf(trusted(id)),
            nowMillis = now,
        )
        assertEquals(DiscoverySource.BOTH, merged.single().discoveredVia)
    }

    @Test
    fun `endpoints are unioned not replaced`() {
        // A PC on both Wi-Fi and Ethernet advertises two addresses, often via different
        // mechanisms. Keeping only the newest would discard the one that actually works.
        val id = idFor(1)
        val merged = PeerMerger.merge(
            advertisements = listOf(
                ad(id, host = "192.168.1.20", source = DiscoverySource.MDNS),
                ad(id, host = "10.0.0.5", source = DiscoverySource.UDP_BEACON),
            ),
            trusted = listOf(trusted(id)),
            nowMillis = now,
        )
        val endpoints = merged.single().endpoints.map { it.host }
        assertTrue("both addresses must survive: $endpoints", endpoints.containsAll(listOf("192.168.1.20", "10.0.0.5")))
        assertEquals(2, endpoints.size)
    }

    @Test
    fun `duplicate endpoints are collapsed`() {
        val id = idFor(1)
        val merged = PeerMerger.merge(
            advertisements = listOf(ad(id), ad(id), ad(id)),
            trusted = listOf(trusted(id)),
            nowMillis = now,
        )
        assertEquals(1, merged.single().endpoints.size)
    }

    @Test
    fun `last seen takes the newest sighting`() {
        val id = idFor(1)
        val merged = PeerMerger.merge(
            advertisements = listOf(ad(id, at = now - 5000), ad(id, at = now - 100)),
            trusted = listOf(trusted(id)),
            nowMillis = now,
        )
        assertEquals(now - 100, merged.single().lastSeenEpochMillis)
    }

    @Test
    fun `when a peer changes ephemeral port on the same host, the newer port replaces the stale port`() {
        val id = idFor(1)
        val merged = PeerMerger.merge(
            advertisements = listOf(
                ad(id, host = "192.168.31.47", port = 36737, at = now - 2000),
                ad(id, host = "192.168.31.47", port = 41285, at = now - 500),
            ),
            trusted = listOf(trusted(id)),
            nowMillis = now,
        )
        val endpoints = merged.single().endpoints
        assertEquals(1, endpoints.size)
        assertEquals(Endpoint("192.168.31.47", 41285), endpoints.first())
    }

    @Test
    fun `an older advertisement cannot overwrite a newer port on the same host`() {
        val id = idFor(1)
        val merged = PeerMerger.merge(
            advertisements = listOf(
                ad(id, host = "192.168.31.47", port = 41285, at = now - 500),
                ad(id, host = "192.168.31.47", port = 36737, at = now - 2000),
            ),
            trusted = listOf(trusted(id)),
            nowMillis = now,
        )
        val endpoints = merged.single().endpoints
        assertEquals(1, endpoints.size)
        assertEquals(Endpoint("192.168.31.47", 41285), endpoints.first())
    }

    // ── Trust comes from the store, never the wire ─────────────────────────────────────────

    @Test
    fun `an advertisement claiming to be trusted is not trusted`() {
        // REQ-D07 made structural: the wire's trustHint is ignored outright. An attacker can claim
        // any deviceId; what they cannot do is be in our trust store.
        val id = idFor(99)
        val merged = PeerMerger.merge(
            advertisements = listOf(ad(id, trustHint = TrustState.TRUSTED)),
            trusted = emptyList(),
            nowMillis = now,
            includeUnpaired = true,
        )
        assertEquals(TrustState.UNKNOWN, merged.single().trust)
    }

    @Test
    fun `a peer in the trust store is trusted`() {
        val id = idFor(1)
        val merged = PeerMerger.merge(
            advertisements = listOf(ad(id)),
            trusted = listOf(trusted(id)),
            nowMillis = now,
        )
        assertEquals(TrustState.TRUSTED, merged.single().trust)
    }

    @Test
    fun `a revoked peer is never shown`() {
        // The user explicitly removed it. Surfacing it again invites them to re-trust something
        // they rejected.
        val id = idFor(1)
        val merged = PeerMerger.merge(
            advertisements = listOf(ad(id)),
            trusted = listOf(trusted(id, revoked = true)),
            nowMillis = now,
            includeUnpaired = true,
        )
        assertTrue("a revoked peer must stay hidden", merged.isEmpty())
    }

    @Test
    fun `unknown peers are hidden unless explicitly included`() {
        val id = idFor(50)
        assertTrue(
            PeerMerger.merge(listOf(ad(id)), emptyList(), now, includeUnpaired = false).isEmpty(),
        )
        assertEquals(
            1,
            PeerMerger.merge(listOf(ad(id)), emptyList(), now, includeUnpaired = true).size,
        )
    }

    @Test
    fun `the local name wins over the advertised name`() {
        // An advertised name is attacker-controlled and must never override what the user called
        // their own device (REQ-U04).
        val id = idFor(1)
        val merged = PeerMerger.merge(
            advertisements = listOf(ad(id, name = "Totally Your PC")),
            trusted = listOf(trusted(id, name = "Study PC")),
            nowMillis = now,
        )
        val peer = merged.single()
        assertEquals("Study PC", peer.localName)
        assertEquals("Totally Your PC", peer.advertisedName)
        assertEquals("the UI must show the local name", "Study PC", peer.displayName)
    }

    @Test
    fun `an unnamed unknown peer still gets a usable display name`() {
        val id = idFor(60)
        val merged = PeerMerger.merge(
            listOf(ad(id, name = null)), emptyList(), now, includeUnpaired = true,
        )
        val display = merged.single().displayName
        assertTrue("should fall back to something identifiable: $display", display.contains(id.short))
    }

    // ── Staleness ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `stale advertisements are dropped`() {
        val id = idFor(1)
        val merged = PeerMerger.merge(
            advertisements = listOf(ad(id, at = now - 60_000)),
            trusted = listOf(trusted(id)),
            nowMillis = now,
            staleAfterMillis = 30_000,
        )
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `a fresh sighting keeps a peer that also has a stale one`() {
        val id = idFor(1)
        val merged = PeerMerger.merge(
            advertisements = listOf(ad(id, at = now - 60_000), ad(id, at = now - 1_000)),
            trusted = listOf(trusted(id)),
            nowMillis = now,
            staleAfterMillis = 30_000,
        )
        assertEquals(1, merged.size)
        assertEquals(now - 1_000, merged.single().lastSeenEpochMillis)
    }

    // ── Ordering and robustness ────────────────────────────────────────────────────────────

    @Test
    fun `trusted peers sort before unknown ones`() {
        val mine = idFor(1)
        val stranger = idFor(80)
        val merged = PeerMerger.merge(
            advertisements = listOf(ad(stranger, at = now), ad(mine, at = now - 5000)),
            trusted = listOf(trusted(mine)),
            nowMillis = now,
            includeUnpaired = true,
        )
        assertEquals("your own device must come first", mine, merged.first().deviceId)
    }

    @Test
    fun `a malformed device id is skipped without failing the merge`() {
        val good = idFor(1)
        val bad = ad(good).copy(deviceIdRaw = "NOT-A-DEVICE-ID")
        val merged = PeerMerger.merge(
            advertisements = listOf(bad, ad(good)),
            trusted = listOf(trusted(good)),
            nowMillis = now,
        )
        assertEquals("one bad record must not take out the list", 1, merged.size)
    }

    @Test
    fun `an empty input yields an empty list`() {
        assertTrue(PeerMerger.merge(emptyList(), emptyList(), now).isEmpty())
    }

    @Test
    fun `capabilities and platform survive a partial advertisement`() {
        // A UDP beacon may carry less than an mDNS record. Merging must not downgrade what was
        // already learned.
        val id = idFor(1)
        val merged = PeerMerger.merge(
            advertisements = listOf(
                ad(id, caps = Capabilities.ANDROID_CURRENT.bits, platform = "windows"),
                ad(id, caps = 0, platform = null, source = DiscoverySource.UDP_BEACON),
            ),
            trusted = listOf(trusted(id)),
            nowMillis = now,
        )
        val peer = merged.single()
        assertEquals(Capabilities.ANDROID_CURRENT.bits, peer.capabilities.bits)
        assertEquals(Platform.WINDOWS, peer.platform)
    }

    @Test
    fun `service-lost event removes mDNS advertisement but preserves UDP beacon advertisement`() {
        val id = idFor(1)
        val trustedPeer = trusted(id, "Test Samsung")
        val mdnsAd = ad(id, host = "192.168.1.10", port = 43947, source = DiscoverySource.MDNS)
        val udpAd = ad(id, host = "192.168.1.10", port = 43947, source = DiscoverySource.UDP_BEACON)

        val observed = mutableListOf(mdnsAd, udpAd)

        // When mDNS service lost arrives, purge only MDNS source
        val shortId = id.value.take(8)
        observed.removeAll {
            it.source == DiscoverySource.MDNS &&
                it.deviceIdRaw.take(shortId.length).equals(shortId, ignoreCase = true)
        }

        // UDP beacon advertisement MUST remain
        assertEquals(1, observed.size)
        assertEquals(DiscoverySource.UDP_BEACON, observed.single().source)

        // Merge still yields the peer via UDP_BEACON
        val merged = PeerMerger.merge(observed, listOf(trustedPeer), now)
        assertEquals(1, merged.size)
        assertEquals(id, merged.single().deviceId)
        assertEquals(DiscoverySource.UDP_BEACON, merged.single().discoveredVia)
        assertEquals(TrustState.TRUSTED, merged.single().trust)
    }

    @Test
    fun `service loss of all advertisements drops ephemeral endpoints but preserves permanent trust record`() {
        val id = idFor(2)
        val trustedPeer = trusted(id, "Test Samsung")
        val mdnsAd = ad(id, host = "192.168.1.20", port = 43947, source = DiscoverySource.MDNS)

        val observed = mutableListOf(mdnsAd)
        observed.removeAll { it.source == DiscoverySource.MDNS }

        // Active discovered endpoints are now empty
        val merged = PeerMerger.merge(observed, listOf(trustedPeer), now)
        assertTrue("Peer is not actively advertised", merged.isEmpty())

        // But permanent trust store record remains completely intact
        assertEquals(id, trustedPeer.deviceId)
        assertEquals("Test Samsung", trustedPeer.name)
        assertFalse(trustedPeer.revoked)
    }
}

/** Presence-token matching: how a private-mode device is still recognised as yours. */
class PresenceTokenMatcherTest {

    private val now = 1_700_000_040L // 40 seconds into an epoch

    private fun peer(secretByte: Byte, revoked: Boolean = false) = TrustedPeer(
        deviceId = DeviceId.fromPublicKey(ByteArray(32) { secretByte }),
        staticPublicKey = ByteArray(32) { secretByte },
        pairingSecret = ByteArray(32) { secretByte },
        name = "peer$secretByte",
        platform = Platform.WINDOWS,
        pairedAtEpochMillis = 0,
        revoked = revoked,
    )

    /** Deterministic stand-in for HMAC: enough to exercise the matching logic. */
    private val fakeToken: (ByteArray, Long) -> ByteArray = { secret, epoch ->
        ByteArray(8) { i -> (secret[0] + epoch.toByte() + i).toByte() }
    }

    @Test
    fun `a token from the current epoch matches`() {
        val p = peer(5)
        val token = fakeToken(p.pairingSecret, now / 60)
        assertEquals(p, PresenceTokenMatcher.match(token, listOf(p), now, fakeToken))
    }

    @Test
    fun `a token from the previous epoch matches`() {
        // The epoch is a one-minute bucket. Without this tolerance two devices with slightly
        // different clocks would fail to recognise each other for part of every minute.
        val p = peer(5)
        val token = fakeToken(p.pairingSecret, (now / 60) - 1)
        assertEquals(p, PresenceTokenMatcher.match(token, listOf(p), now, fakeToken))
    }

    @Test
    fun `a token from two epochs ago does not match`() {
        val p = peer(5)
        val token = fakeToken(p.pairingSecret, (now / 60) - 2)
        assertNull(PresenceTokenMatcher.match(token, listOf(p), now, fakeToken))
    }

    @Test
    fun `the right peer is found among several`() {
        val peers = listOf(peer(1), peer(2), peer(3))
        val target = peers[1]
        val token = fakeToken(target.pairingSecret, now / 60)
        assertEquals(target, PresenceTokenMatcher.match(token, peers, now, fakeToken))
    }

    @Test
    fun `an unknown token matches nobody`() {
        assertNull(
            PresenceTokenMatcher.match(ByteArray(8) { 99 }, listOf(peer(1)), now, fakeToken),
        )
    }

    @Test
    fun `a revoked peer is never matched`() {
        val p = peer(5, revoked = true)
        val token = fakeToken(p.pairingSecret, now / 60)
        assertNull(
            "a revoked peer must not be recognised through its old token",
            PresenceTokenMatcher.match(token, listOf(p), now, fakeToken),
        )
    }

    @Test
    fun `a wrong-size token is rejected`() {
        for (size in intArrayOf(0, 4, 7, 9, 16)) {
            assertNull(
                "a $size-byte token should be rejected",
                PresenceTokenMatcher.match(ByteArray(size), listOf(peer(1)), now, fakeToken),
            )
        }
    }

    @Test
    fun `an empty trust store matches nothing`() {
        assertNull(PresenceTokenMatcher.match(ByteArray(8), emptyList(), now, fakeToken))
    }

    @Test
    fun `matching is attempted for every peer`() {
        // Guards against an early return that would make only the first stored peer recognisable
        // in private mode.
        var calls = 0
        val counting: (ByteArray, Long) -> ByteArray = { s, e -> calls++; fakeToken(s, e) }
        PresenceTokenMatcher.match(ByteArray(8) { 123 }, List(5) { peer(it.toByte()) }, now, counting)
        assertEquals("5 peers x 2 epochs", 10, calls)
    }
}
