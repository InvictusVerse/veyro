package com.veyro.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interface classification against the names actually seen in the wild.
 *
 * This is the test that matters most in `:core:network`, because the failure it prevents is the
 * nastiest one in the product: advertising an address that no phone can reach produces a peer that
 * is **visible and permanently unreachable**, with no error anywhere. The user sees their PC, taps
 * it, and nothing happens.
 */
class InterfaceClassifierTest {

    @Test
    fun `wifi interfaces are recognised`() {
        for (name in listOf("wlan0", "wlan", "wlp3s0", "wl0", "Wi-Fi", "WiFi")) {
            assertEquals("$name should be WIFI", InterfaceKind.WIFI, InterfaceClassifier.classify(name))
        }
    }

    @Test
    fun `ethernet interfaces are recognised`() {
        for (name in listOf("eth0", "enp0s31f6", "ens33", "eno1", "Ethernet")) {
            assertEquals(
                "$name should be ETHERNET",
                InterfaceKind.ETHERNET,
                InterfaceClassifier.classify(name),
            )
        }
    }

    @Test
    fun `soft ap interfaces are recognised across vendor naming`() {
        // Android names the hotspot interface inconsistently and offers no API to ask. Getting
        // this wrong means hotspot transfers fail silently - one of the three scenarios the whole
        // product exists for.
        for (name in listOf("ap0", "softap0", "swlan0", "wlan1", "wifi_ap0", "p2p-wlan0-0")) {
            assertEquals(
                "$name should be HOTSPOT",
                InterfaceKind.HOTSPOT,
                InterfaceClassifier.classify(name),
            )
        }
    }

    @Test
    fun `hypervisor and container adapters are rejected`() {
        for (name in listOf(
            "VirtualBox Host-Only Network",
            "vboxnet0",
            "VMware Network Adapter VMnet8",
            "vmnet1",
            "vEthernet (WSL)",
            "vEthernet (Default Switch)",
            "docker0",
            "br-1a2b3c",
            "veth1234",
            "virbr0",
        )) {
            assertEquals(
                "$name must be VIRTUAL or it will be advertised and never reachable",
                InterfaceKind.VIRTUAL,
                InterfaceClassifier.classify(name),
            )
        }
    }

    @Test
    fun `vEthernet WSL is virtual despite containing the word ethernet`() {
        // The ordering trap: a naive Ethernet check matches "vEthernet (WSL)" first. This is the
        // single most likely classification bug, so it gets its own test.
        assertEquals(InterfaceKind.VIRTUAL, InterfaceClassifier.classify("vEthernet (WSL)"))
        assertFalse(InterfaceClassifier.isAdvertisable(InterfaceClassifier.classify("vEthernet (WSL)")))
    }

    @Test
    fun `wlan1 is hotspot not wifi despite starting with wlan`() {
        // The other ordering trap. wlan1 is conventionally the soft-AP on many Android devices.
        assertEquals(InterfaceKind.HOTSPOT, InterfaceClassifier.classify("wlan1"))
    }

    @Test
    fun `vpn tunnels are rejected`() {
        for (name in listOf("tun0", "tap0", "ppp0", "ipsec0", "wg0", "nordlynx")) {
            assertEquals(
                "$name should be VPN",
                InterfaceKind.VPN,
                InterfaceClassifier.classify(name),
            )
        }
    }

    @Test
    fun `cellular interfaces are recognised`() {
        for (name in listOf("rmnet0", "rmnet_data0", "ccmni0", "pdp_ip0")) {
            assertEquals(InterfaceKind.CELLULAR, InterfaceClassifier.classify(name))
        }
    }

    @Test
    fun `loopback is never advertisable`() {
        val kind = InterfaceClassifier.classify("lo", isLoopback = true)
        assertFalse(InterfaceClassifier.isAdvertisable(kind))
    }

    @Test
    fun `only wifi ethernet and hotspot are advertisable`() {
        assertTrue(InterfaceClassifier.isAdvertisable(InterfaceKind.WIFI))
        assertTrue(InterfaceClassifier.isAdvertisable(InterfaceKind.ETHERNET))
        assertTrue(InterfaceClassifier.isAdvertisable(InterfaceKind.HOTSPOT))

        assertFalse(InterfaceClassifier.isAdvertisable(InterfaceKind.VIRTUAL))
        assertFalse(InterfaceClassifier.isAdvertisable(InterfaceKind.VPN))
        assertFalse(
            "a carrier address cannot be reached by a phone on the same Wi-Fi",
            InterfaceClassifier.isAdvertisable(InterfaceKind.CELLULAR),
        )
        assertFalse(InterfaceClassifier.isAdvertisable(InterfaceKind.OTHER))
    }

    @Test
    fun `hotspot is preferred over wifi`() {
        // A peer that joined our hotspot can only be reached on that interface, whereas a
        // Wi-Fi-connected peer is usually reachable several ways. Narrowest-but-essential first.
        assertTrue(
            InterfaceClassifier.preference(InterfaceKind.HOTSPOT) <
                InterfaceClassifier.preference(InterfaceKind.WIFI),
        )
        assertTrue(
            InterfaceClassifier.preference(InterfaceKind.WIFI) <
                InterfaceClassifier.preference(InterfaceKind.ETHERNET),
        )
        assertTrue(
            InterfaceClassifier.preference(InterfaceKind.ETHERNET) <
                InterfaceClassifier.preference(InterfaceKind.VPN),
        )
        assertTrue(
            InterfaceClassifier.preference(InterfaceKind.VPN) <
                InterfaceClassifier.preference(InterfaceKind.VIRTUAL),
        )
    }

    @Test
    fun `a realistic developer machine yields only the real adapters`() {
        // The scenario this component exists for: a PC with Wi-Fi, Ethernet, VirtualBox, VMware,
        // WSL, Docker and a VPN all up at once, most with a valid IPv4 address.
        val machine = listOf(
            "Wi-Fi", "Ethernet", "VirtualBox Host-Only Network",
            "VMware Network Adapter VMnet1", "vEthernet (WSL)", "docker0", "wg0",
        )
        val advertisable = machine
            .map { it to InterfaceClassifier.classify(it) }
            .filter { InterfaceClassifier.isAdvertisable(it.second) }
            .map { it.first }

        assertEquals(listOf("Wi-Fi", "Ethernet"), advertisable)
    }

    @Test
    fun `unknown names fall back to OTHER and are not advertised`() {
        val kind = InterfaceClassifier.classify("some-future-adapter")
        assertEquals(InterfaceKind.OTHER, kind)
        assertFalse(
            "an unrecognised adapter must not be advertised: failing closed beats a phantom peer",
            InterfaceClassifier.isAdvertisable(kind),
        )
    }
}

/** IPv4 arithmetic. Small, but the broadcast address is what makes the UDP fallback work. */
class Ipv4Test {

    @Test
    fun `parse accepts valid dotted quads`() {
        assertEquals(0x00000000, Ipv4.parse("0.0.0.0"))
        assertEquals(0xC0A80101.toInt(), Ipv4.parse("192.168.1.1"))
        assertEquals(0xFFFFFFFF.toInt(), Ipv4.parse("255.255.255.255"))
    }

    @Test
    fun `parse rejects malformed input`() {
        for (bad in listOf(
            "", "1.2.3", "1.2.3.4.5", "256.1.1.1", "-1.1.1.1", "a.b.c.d",
            "1.1.1.", ".1.1.1", "1..1.1", "1.1.1.1 ",
        )) {
            assertNull("'$bad' should not parse", Ipv4.parse(bad))
        }
    }

    @Test
    fun `parse rejects leading zeros`() {
        // "010.1.1.1" is octal in some C resolvers and decimal in others. A lenient parser here
        // would let a peer claim an address that means different things on the two platforms.
        assertNull(Ipv4.parse("010.1.1.1"))
        assertNull(Ipv4.parse("192.168.01.1"))
    }

    @Test
    fun `round trip through format`() {
        for (address in listOf("0.0.0.0", "10.0.0.1", "192.168.1.255", "172.16.254.3", "255.255.255.255")) {
            assertEquals(address, Ipv4.format(Ipv4.parse(address)!!))
        }
    }

    @Test
    fun `mask of a prefix length`() {
        assertEquals(0, Ipv4.maskOf(0))
        assertEquals(0xFF000000.toInt(), Ipv4.maskOf(8))
        assertEquals(0xFFFF0000.toInt(), Ipv4.maskOf(16))
        assertEquals(0xFFFFFF00.toInt(), Ipv4.maskOf(24))
        assertEquals(0xFFFFFFFF.toInt(), Ipv4.maskOf(32))
    }

    @Test
    fun `broadcast address for common subnets`() {
        assertEquals("192.168.1.255", Ipv4.broadcastAddress("192.168.1.37", 24))
        assertEquals("192.168.43.255", Ipv4.broadcastAddress("192.168.43.1", 24))
        assertEquals("10.0.255.255", Ipv4.broadcastAddress("10.0.0.5", 16))
        assertEquals("172.16.31.255", Ipv4.broadcastAddress("172.16.16.9", 19))
    }

    @Test
    fun `a 31 bit prefix still has a broadcast`() {
        assertEquals("192.168.1.1", Ipv4.broadcastAddress("192.168.1.0", 31))
    }

    @Test
    fun `broadcast is undefined for a host route`() {
        // /32 is a single host, so there is nothing to broadcast to. Returning an address here
        // would make the beacon send to itself and look like it worked.
        assertNull(Ipv4.broadcastAddress("192.168.1.1", 32))
        assertNull(Ipv4.broadcastAddress("192.168.1.1", 0))
    }

    @Test
    fun `same subnet detection`() {
        assertTrue(Ipv4.sameSubnet("192.168.1.10", "192.168.1.200", 24))
        assertFalse(Ipv4.sameSubnet("192.168.1.10", "192.168.2.200", 24))
        assertTrue(Ipv4.sameSubnet("192.168.1.10", "192.168.2.200", 16))
        assertFalse(Ipv4.sameSubnet("10.0.0.1", "192.168.1.1", 8))
    }

    @Test
    fun `android hotspot range is private`() {
        // 192.168.43.x is the classic Android tethering subnet; 172.20.10.x is iOS. Both must be
        // recognised as private, because a "this looks like a public address" guard would reject
        // the hotspot case the product is built for.
        assertTrue(Ipv4.isPrivate("192.168.43.1"))
        assertTrue(Ipv4.isPrivate("172.20.10.2"))
        assertTrue(Ipv4.isPrivate("10.0.0.1"))
        assertTrue(Ipv4.isPrivate("192.168.0.1"))
        assertTrue(Ipv4.isPrivate("172.16.0.1"))
        assertTrue(Ipv4.isPrivate("172.31.255.254"))
    }

    @Test
    fun `public and out-of-range addresses are not private`() {
        assertFalse(Ipv4.isPrivate("8.8.8.8"))
        assertFalse(Ipv4.isPrivate("172.15.0.1"))
        assertFalse(Ipv4.isPrivate("172.32.0.1"))
        assertFalse(Ipv4.isPrivate("192.167.1.1"))
    }

    @Test
    fun `link local is detected`() {
        assertTrue(Ipv4.isLinkLocal("169.254.1.1"))
        assertFalse(Ipv4.isLinkLocal("169.253.1.1"))
        assertFalse(Ipv4.isLinkLocal("192.168.1.1"))
        // Link-local counts as private but is separately flagged, because it usually means DHCP
        // failed and the address will not reach anything.
        assertTrue(Ipv4.isPrivate("169.254.1.1"))
    }

    @Test
    fun `loopback is detected`() {
        assertTrue(Ipv4.isLoopback("127.0.0.1"))
        assertTrue(Ipv4.isLoopback("127.1.2.3"))
        assertFalse(Ipv4.isLoopback("128.0.0.1"))
    }
}
