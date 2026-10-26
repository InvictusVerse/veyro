package com.veyro.core.network

import com.veyro.core.model.NetworkDiagnosis
import com.veyro.core.model.NetworkTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnosis mapping (REQ-N01..N06).
 *
 * The point of these tests is the *combinations*. Any single symptom is ambiguous; the diagnosis
 * comes from which symptoms appear together, and that is exactly what cannot be checked by reading
 * the code. A pure mapping makes it cheap to assert all of them.
 */
class NetworkDiagnoserTest {

    private fun obs(
        transport: NetworkTransport = NetworkTransport.WIFI,
        hasVpn: Boolean = false,
        local: List<String> = listOf("192.168.1.10"),
        mdns: Boolean = false,
        broadcast: Boolean = false,
        tcpOk: Boolean = false,
        refused: Boolean = false,
        handshakeFailed: Boolean = false,
        peer: List<String> = emptyList(),
        prefix: Int = 24,
    ) = NetworkDiagnoser.Observations(
        transport = transport,
        hasVpn = hasVpn,
        localAddresses = local,
        seenViaMdns = mdns,
        seenViaBroadcast = broadcast,
        tcpConnectSucceeded = tcpOk,
        tcpRefused = refused,
        handshakeFailed = handshakeFailed,
        peerAddresses = peer,
        localPrefixLength = prefix,
    )

    // ── The happy path ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a working connection is OK`() {
        assertEquals(
            NetworkDiagnosis.OK,
            NetworkDiagnoser.diagnose(obs(mdns = true, tcpOk = true, peer = listOf("192.168.1.20"))),
        )
    }

    // ── AP isolation: the headline case ────────────────────────────────────────────────────

    @Test
    fun `visible peer on the same subnet with every connect timing out is AP isolation`() {
        // This exact signature - discovery works, TCP always times out, same subnet, link up - is
        // client isolation. It is the most common hotspot complaint and the reason REQ-N02 exists.
        assertEquals(
            NetworkDiagnosis.AP_ISOLATED,
            NetworkDiagnoser.diagnose(
                obs(mdns = true, broadcast = true, tcpOk = false, peer = listOf("192.168.1.20")),
            ),
        )
    }

    @Test
    fun `AP isolation is distinguished from a firewall by the refusal`() {
        // A timeout means nothing came back. A refusal means the host answered "no". Same failure
        // to connect, completely different advice, so the two must not collapse into one.
        val isolated = NetworkDiagnoser.diagnose(
            obs(mdns = true, peer = listOf("192.168.1.20"), refused = false),
        )
        val firewalled = NetworkDiagnoser.diagnose(
            obs(mdns = true, peer = listOf("192.168.1.20"), refused = true),
        )
        assertEquals(NetworkDiagnosis.AP_ISOLATED, isolated)
        assertEquals(NetworkDiagnosis.PEER_FIREWALL, firewalled)
    }

    @Test
    fun `a visible peer on a different subnet is a subnet mismatch not isolation`() {
        // Both are "visible but unreachable", but the fix differs: isolation needs a different
        // network, a mismatch needs the *same* network. Telling the user the wrong one wastes
        // their time.
        assertEquals(
            NetworkDiagnosis.SUBNET_MISMATCH,
            NetworkDiagnoser.diagnose(
                obs(mdns = true, local = listOf("192.168.1.10"), peer = listOf("10.0.0.5")),
            ),
        )
    }

    @Test
    fun `a wider prefix makes a previously mismatched peer routable`() {
        // Same addresses, different prefix: on a /16 these are the same subnet, so the diagnosis
        // must change. This pins that the prefix is actually used rather than assumed to be 24.
        val args = { p: Int ->
            obs(mdns = true, local = listOf("192.168.1.10"), peer = listOf("192.168.2.5"), prefix = p)
        }
        assertEquals(NetworkDiagnosis.SUBNET_MISMATCH, NetworkDiagnoser.diagnose(args(24)))
        assertEquals(NetworkDiagnosis.AP_ISOLATED, NetworkDiagnoser.diagnose(args(16)))
    }

    // ── Authentication vs network ──────────────────────────────────────────────────────────

    @Test
    fun `a failed handshake over a working connection is not a network problem`() {
        // Conflating these sends the user to fix their router when they need to re-pair.
        val d = NetworkDiagnoser.diagnose(
            obs(mdns = true, tcpOk = true, handshakeFailed = true, peer = listOf("192.168.1.20")),
        )
        assertEquals(NetworkDiagnosis.AUTHENTICATION_FAILED, d)
        assertTrue("authentication failure must not be reported as a network fault", !d.isNetworkProblem)
    }

    @Test
    fun `every other diagnosis is flagged as a network problem`() {
        for (d in NetworkDiagnosis.entries) {
            val expected = d != NetworkDiagnosis.OK && d != NetworkDiagnosis.AUTHENTICATION_FAILED
            assertEquals("isNetworkProblem for $d", expected, d.isNetworkProblem)
        }
    }

    // ── No network ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `no transport is no network`() {
        assertEquals(
            NetworkDiagnosis.NO_NETWORK,
            NetworkDiagnoser.diagnose(obs(transport = NetworkTransport.NONE, local = emptyList())),
        )
    }

    @Test
    fun `cellular only is no network for peer to peer`() {
        // Mobile data cannot carry LAN discovery. This is a distinct message from "your network is
        // broken": the user needs Wi-Fi or a hotspot, and should be told so.
        assertEquals(
            NetworkDiagnosis.NO_NETWORK,
            NetworkDiagnoser.diagnose(
                obs(transport = NetworkTransport.CELLULAR, local = emptyList()),
            ),
        )
    }

    @Test
    fun `no addresses means no network even when a transport is claimed`() {
        assertEquals(
            NetworkDiagnosis.NO_NETWORK,
            NetworkDiagnoser.diagnose(obs(transport = NetworkTransport.WIFI, local = emptyList())),
        )
    }

    // ── VPN ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a VPN with nothing discovered is reported as VPN interference`() {
        // Users essentially never guess this one, which is why it gets its own diagnosis rather
        // than being folded into "multicast blocked".
        assertEquals(
            NetworkDiagnosis.VPN_INTERFERENCE,
            NetworkDiagnoser.diagnose(obs(hasVpn = true, mdns = false, broadcast = false)),
        )
    }

    @Test
    fun `a VPN does not mask a successful connection`() {
        assertEquals(
            NetworkDiagnosis.OK,
            NetworkDiagnoser.diagnose(
                obs(hasVpn = true, mdns = true, tcpOk = true, peer = listOf("192.168.1.20")),
            ),
        )
    }

    @Test
    fun `an isolated peer is diagnosed as isolation even with a VPN present`() {
        // Evidence beats heuristics: if we can see the peer on our subnet, the VPN is not the
        // cause, whatever else is running.
        assertEquals(
            NetworkDiagnosis.AP_ISOLATED,
            NetworkDiagnoser.diagnose(obs(hasVpn = true, mdns = true, peer = listOf("192.168.1.20"))),
        )
    }

    // ── Nothing found ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a healthy link with nothing discovered points at blocked discovery`() {
        assertEquals(
            NetworkDiagnosis.MULTICAST_BLOCKED,
            NetworkDiagnoser.diagnose(obs(mdns = false, broadcast = false)),
        )
    }

    // ── Mechanism health ───────────────────────────────────────────────────────────────────

    @Test
    fun `mechanism health reports which discovery path worked`() {
        // This is why both mechanisms run concurrently instead of one as a fallback: knowing that
        // mDNS is silent while broadcast works is how we learn an AP drops multicast.
        assertEquals(
            NetworkDiagnoser.MechanismHealth.BOTH_WORKING,
            NetworkDiagnoser.mechanismHealth(obs(mdns = true, broadcast = true)),
        )
        assertEquals(
            NetworkDiagnoser.MechanismHealth.MDNS_ONLY,
            NetworkDiagnoser.mechanismHealth(obs(mdns = true)),
        )
        assertEquals(
            NetworkDiagnoser.MechanismHealth.BROADCAST_ONLY,
            NetworkDiagnoser.mechanismHealth(obs(broadcast = true)),
        )
        assertEquals(
            NetworkDiagnoser.MechanismHealth.NEITHER,
            NetworkDiagnoser.mechanismHealth(obs()),
        )
    }

    // ── Advice ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every diagnosis has advice`() {
        // REQ-N06: no bare "device not found". If a diagnosis exists, there must be something
        // actionable to say about it.
        for (d in NetworkDiagnosis.entries) {
            val advice = DiagnosisAdvice.forDiagnosis(d)
            assertTrue("$d has no summary", advice.summary.isNotBlank())
            if (d != NetworkDiagnosis.OK) {
                assertTrue("$d has no remediation steps", advice.steps.isNotEmpty())
            }
        }
    }

    @Test
    fun `isolation advice names the cause and offers a hotspot`() {
        val advice = DiagnosisAdvice.forDiagnosis(NetworkDiagnosis.AP_ISOLATED)
        val text = (advice.summary + advice.steps.joinToString(" ")).lowercase()
        assertTrue("should name client isolation", text.contains("isolation"))
        assertTrue("should suggest a hotspot", text.contains("hotspot"))
        assertTrue(
            "should make clear it is not the devices' fault",
            text.contains("not a problem with either device") || text.contains("router setting"),
        )
    }

    @Test
    fun `authentication advice warns about impersonation`() {
        // If a handshake fails unexpectedly, the possibility that something is impersonating the
        // peer has to be stated. Telling the user to "try again" would be the wrong advice.
        val advice = DiagnosisAdvice.forDiagnosis(NetworkDiagnosis.AUTHENTICATION_FAILED)
        val text = advice.steps.joinToString(" ").lowercase()
        assertTrue(text.contains("impersonating"))
        assertTrue(text.contains("do not accept"))
    }
}
