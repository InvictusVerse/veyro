package com.veyro.core.network

import com.veyro.core.model.NetworkDiagnosis
import com.veyro.core.model.NetworkTransport

/**
 * Turns a set of observations into a named diagnosis (REQ-N01..N06).
 *
 * The whole reason this is a separate, pure, tested component is REQ-N06: the app must never show a
 * bare "device not found". On a hotspot the usual cause is an access-point setting the user can
 * actually change, and the difference between "AP isolation" and "wrong subnet" is the difference
 * between a fix and a shrug.
 *
 * Keeping the mapping pure means the interesting part - *which* conclusion follows from *which*
 * combination of symptoms - is testable without a network, which is the only practical way to
 * cover combinations like "mDNS worked, broadcast did not, TCP failed".
 */
public object NetworkDiagnoser {

    /**
     * What was observed during a discovery and connect attempt.
     *
     * @param transport the active transport, or `NONE`
     * @param hasVpn a VPN transport is present
     * @param localAddresses advertisable local IPv4 addresses, after classification
     * @param seenViaMdns at least one peer advertisement arrived over mDNS
     * @param seenViaBroadcast at least one peer advertisement arrived over UDP broadcast/multicast
     * @param tcpConnectSucceeded a TCP connection to a peer endpoint was established
     * @param tcpRefused a connect attempt was actively refused (RST), as opposed to timing out
     * @param handshakeFailed TCP connected but the Noise handshake did not complete
     * @param peerAddresses addresses the peer advertised, if any
     * @param localPrefixLength prefix length of the primary local interface
     */
    public data class Observations(
        val transport: NetworkTransport,
        val hasVpn: Boolean = false,
        val localAddresses: List<String> = emptyList(),
        val seenViaMdns: Boolean = false,
        val seenViaBroadcast: Boolean = false,
        val tcpConnectSucceeded: Boolean = false,
        val tcpRefused: Boolean = false,
        val handshakeFailed: Boolean = false,
        val peerAddresses: List<String> = emptyList(),
        val localPrefixLength: Int = 24,
    ) {
        val sawPeer: Boolean get() = seenViaMdns || seenViaBroadcast
    }

    public fun diagnose(o: Observations): NetworkDiagnosis {
        // Order matters: each branch assumes the ones above it were ruled out.

        // 1. No usable transport at all. Nothing else can be concluded.
        if (o.transport == NetworkTransport.NONE) return NetworkDiagnosis.NO_NETWORK

        // 2. Cellular only. Peer-to-peer LAN discovery cannot work, and this is a different
        //    problem from "the network is broken" - the user needs Wi-Fi or a hotspot.
        if (o.transport == NetworkTransport.CELLULAR && o.localAddresses.isEmpty()) {
            return NetworkDiagnosis.NO_NETWORK
        }

        // 3. The happy path, checked before any failure analysis.
        if (o.tcpConnectSucceeded && !o.handshakeFailed) return NetworkDiagnosis.OK

        // 4. Reachable but the handshake failed. This is a key mismatch, not a network fault, and
        //    conflating the two sends the user to fix their router when they need to re-pair.
        if (o.tcpConnectSucceeded && o.handshakeFailed) {
            return NetworkDiagnosis.AUTHENTICATION_FAILED
        }

        // 5. Actively refused means the host is reachable and something said no - a firewall, or
        //    nothing listening. Distinct from a timeout, which is what isolation looks like.
        if (o.tcpRefused) return NetworkDiagnosis.PEER_FIREWALL

        // 6. We can see the peer but cannot connect to it. Two candidate causes, and the
        //    distinguishing evidence is whether the peer's address is even in our subnet.
        if (o.sawPeer && o.peerAddresses.isNotEmpty()) {
            val local = o.localAddresses.firstOrNull()
            val routable = local != null && o.peerAddresses.any { peer ->
                Ipv4.sameSubnet(local, peer, o.localPrefixLength)
            }
            // Same subnet, visible, yet every TCP connect times out: that is the signature of AP
            // client isolation. The access point forwards each client to the internet but refuses
            // to bridge client-to-client traffic, so discovery works and connections do not.
            return if (routable) {
                NetworkDiagnosis.AP_ISOLATED
            } else {
                NetworkDiagnosis.SUBNET_MISMATCH
            }
        }

        // 7. A VPN holding the default route is the most common reason the LAN is simply absent,
        //    and users almost never guess it.
        if (o.hasVpn) return NetworkDiagnosis.VPN_INTERFERENCE

        // 8. We saw nothing. If exactly one discovery mechanism produced results we would not be
        //    here; if neither did, and multicast is the one that is usually blocked, say so.
        if (!o.sawPeer) {
            return if (o.localAddresses.isEmpty()) {
                NetworkDiagnosis.NO_NETWORK
            } else {
                NetworkDiagnosis.MULTICAST_BLOCKED
            }
        }

        return NetworkDiagnosis.MULTICAST_BLOCKED
    }

    /**
     * Whether the two discovery mechanisms disagree, which is worth knowing even when the overall
     * result is OK.
     *
     * This is why both mechanisms run concurrently rather than one as a fallback: observing that
     * mDNS is silent while broadcast works is how we learn an access point drops multicast, and
     * that knowledge is what turns a future silent failure into a named one.
     */
    public fun mechanismHealth(o: Observations): MechanismHealth = when {
        o.seenViaMdns && o.seenViaBroadcast -> MechanismHealth.BOTH_WORKING
        o.seenViaMdns -> MechanismHealth.MDNS_ONLY
        o.seenViaBroadcast -> MechanismHealth.BROADCAST_ONLY
        else -> MechanismHealth.NEITHER
    }

    public enum class MechanismHealth {
        /** Healthy LAN. */
        BOTH_WORKING,

        /** Broadcast is filtered. Harmless - mDNS carries it. */
        MDNS_ONLY,

        /** Multicast is dropped, which is common on hotspots. The fallback is doing the work. */
        BROADCAST_ONLY,

        /** Nothing is getting through. */
        NEITHER,
    }
}

/** Human-facing remediation text. Kept next to the diagnosis so the two cannot drift apart. */
public object DiagnosisAdvice {

    public data class Advice(val summary: String, val steps: List<String>)

    public fun forDiagnosis(diagnosis: NetworkDiagnosis): Advice = when (diagnosis) {
        NetworkDiagnosis.OK -> Advice("Connected.", emptyList())

        NetworkDiagnosis.AP_ISOLATED -> Advice(
            summary = "This network blocks devices from talking to each other.",
            steps = listOf(
                "The Wi-Fi you are on has client isolation switched on. It is a router setting, not a problem with either device.",
                "Use one device's hotspot instead, and connect the other to it.",
                "Or connect both devices to a home router you control.",
                "Guest networks and public Wi-Fi usually have isolation on and cannot be changed.",
            ),
        )

        NetworkDiagnosis.MULTICAST_BLOCKED -> Advice(
            summary = "This network is filtering discovery traffic.",
            steps = listOf(
                "Veyro could not find the other device, though both appear to be online.",
                "Make sure both devices are on the same Wi-Fi network, not one on Wi-Fi and one on mobile data.",
                "If one device is on a 5 GHz band and the other on 2.4 GHz of a split network, join the same one.",
                "A hotspot from either device usually works when a router does not.",
            ),
        )

        NetworkDiagnosis.VPN_INTERFERENCE -> Advice(
            summary = "A VPN is preventing local connections.",
            steps = listOf(
                "A VPN is active and is routing all traffic, including traffic meant for your own network.",
                "Turn the VPN off for the transfer, or enable split tunnelling and exclude local addresses.",
            ),
        )

        NetworkDiagnosis.NO_NETWORK -> Advice(
            summary = "No usable network.",
            steps = listOf(
                "Veyro needs both devices on the same network. Mobile data alone will not work.",
                "Connect both to the same Wi-Fi, or turn on a hotspot on one and join it from the other.",
            ),
        )

        NetworkDiagnosis.PEER_FIREWALL -> Advice(
            summary = "The other device refused the connection.",
            steps = listOf(
                "The other device is reachable but something is blocking the port.",
                "On Windows, allow Veyro through the firewall for the current network profile.",
                "Check that Veyro is actually running on the other device.",
            ),
        )

        NetworkDiagnosis.SUBNET_MISMATCH -> Advice(
            summary = "The devices are on different networks.",
            steps = listOf(
                "Both devices are online but on separate networks, so they cannot reach each other.",
                "Join the same Wi-Fi on both, or use one device's hotspot.",
            ),
        )

        NetworkDiagnosis.AUTHENTICATION_FAILED -> Advice(
            summary = "The other device could not be verified.",
            steps = listOf(
                "The connection worked, but the other device did not prove it is the one you paired with.",
                "If you reset the identity on either device, pair them again.",
                "If you did not expect this, something on the network may be impersonating your device. Do not accept any transfer.",
            ),
        )
    }
}
