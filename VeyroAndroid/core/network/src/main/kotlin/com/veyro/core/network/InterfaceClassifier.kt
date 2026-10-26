package com.veyro.core.network

/**
 * Classifies a network interface by name.
 *
 * Pure and Android-free so it can be unit-tested against the real interface names seen in the
 * wild, which is the only way to get this right - there is no API that says "this adapter can
 * reach a phone on the same Wi-Fi".
 *
 * ## Why this matters more than it looks
 *
 * A development PC typically has five to ten interfaces up: Wi-Fi, Ethernet, a VirtualBox host
 * adapter, a VMware adapter, WSL's vEthernet, Docker's bridge, and a VPN tunnel. Most of them have
 * a perfectly valid IPv4 address and none of them can reach a phone.
 *
 * Advertising one of those addresses in an mDNS record produces the worst possible failure: a peer
 * that is **visible and permanently unreachable**. The user sees their PC in the list, taps it, and
 * it times out, with no explanation. That is exactly the failure REQ-N06 exists to prevent, and it
 * is why this classification is a named, tested component rather than an `if` inside the advertiser.
 *
 * ## Ordering
 *
 * Checks run most-specific first. `vEthernet (WSL)` contains "ethernet", so a naive Ethernet check
 * would claim it before the virtual check ever ran.
 */
public object InterfaceClassifier {

    /** Virtual adapters from hypervisors and container runtimes. Never reachable by a phone. */
    private val VIRTUAL_MARKERS = listOf(
        "vbox", "virtualbox", "vmnet", "vmware", "wsl", "hyper-v", "vethernet",
        "docker", "br-", "veth", "virbr", "lxc", "tailscale", "zt", "utun",
    )

    /** VPN tunnels. They carry a route but almost never reach the local subnet. */
    private val VPN_MARKERS = listOf("tun", "tap", "ppp", "ipsec", "wg", "nordlynx", "proton")

    /**
     * A soft-AP interface: this device is *running* the hotspot.
     *
     * Android names it inconsistently across vendors - `ap0`, `swlan0`, `wlan1`, `softap0` - and
     * there is no supported API to ask. Peers that joined our hotspot live on this subnet, so
     * getting it wrong means hotspot transfers silently fail, which is one of the three scenarios
     * the whole product exists for.
     */
    private val HOTSPOT_MARKERS = listOf("ap0", "softap", "swlan", "wlan1", "p2p-wlan", "wifi_ap")

    private val WIFI_MARKERS = listOf("wlan", "wl", "wi-fi", "wifi", "wlp")

    private val ETHERNET_MARKERS = listOf("eth", "enp", "ens", "eno", "en0", "ethernet", "lan")

    /**
     * @param name the interface name, e.g. `wlan0` or `vEthernet (WSL)`
     * @param isLoopback from `NetworkInterface.isLoopback`
     * @param isVirtual from `NetworkInterface.isVirtual` - note this is unreliable and returns
     * false for VirtualBox and WSL adapters, which is why the name markers exist at all
     */
    public fun classify(
        name: String,
        isLoopback: Boolean = false,
        isVirtual: Boolean = false,
    ): InterfaceKind {
        if (isLoopback) return InterfaceKind.OTHER
        val n = name.lowercase()

        // Most specific first: "vEthernet (WSL)" contains "ethernet", and "wlan1" contains "wlan".
        if (isVirtual || VIRTUAL_MARKERS.any { it in n }) return InterfaceKind.VIRTUAL
        if (VPN_MARKERS.any { n.startsWith(it) }) return InterfaceKind.VPN
        if (HOTSPOT_MARKERS.any { it in n }) return InterfaceKind.HOTSPOT
        if (WIFI_MARKERS.any { n.startsWith(it) }) return InterfaceKind.WIFI
        if (ETHERNET_MARKERS.any { n.startsWith(it) }) return InterfaceKind.ETHERNET
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp")) {
            return InterfaceKind.CELLULAR
        }
        return InterfaceKind.OTHER
    }

    /**
     * Whether an interface of this kind should be advertised to peers.
     *
     * Cellular is excluded deliberately: a carrier-assigned address is not reachable from a phone
     * on the same Wi-Fi, and advertising it is the "visible but unreachable" failure again.
     */
    public fun isAdvertisable(kind: InterfaceKind): Boolean = when (kind) {
        InterfaceKind.WIFI, InterfaceKind.ETHERNET, InterfaceKind.HOTSPOT -> true
        InterfaceKind.VIRTUAL, InterfaceKind.VPN, InterfaceKind.CELLULAR, InterfaceKind.OTHER -> false
    }

    /**
     * Preference order when several addresses are usable.
     *
     * Hotspot first: if this device is running a hotspot, a peer that joined it can *only* be
     * reached on that interface, whereas a Wi-Fi-connected peer can usually be reached several
     * ways. Putting the narrowest-but-essential route first costs nothing and fixes the hotspot
     * case.
     */
    public fun preference(kind: InterfaceKind): Int = when (kind) {
        InterfaceKind.HOTSPOT -> 0
        InterfaceKind.WIFI -> 1
        InterfaceKind.ETHERNET -> 2
        InterfaceKind.CELLULAR -> 3
        InterfaceKind.OTHER -> 4
        InterfaceKind.VPN -> 5
        InterfaceKind.VIRTUAL -> 6
    }
}

/**
 * IPv4 helpers used to decide whether two hosts can plausibly reach each other, and to compute the
 * directed broadcast address the UDP beacon needs.
 */
public object Ipv4 {

    /** Parses dotted-quad into a 32-bit value. Returns null for anything malformed. */
    public fun parse(address: String): Int? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        var result = 0
        for (part in parts) {
            // Reject "01" and "" as well as out-of-range: a lenient parser here would let a peer
            // claim an address that resolves differently on the two platforms.
            if (part.isEmpty() || part.length > 3) return null
            if (part.length > 1 && part[0] == '0') return null
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            result = (result shl 8) or octet
        }
        return result
    }

    public fun format(value: Int): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"

    /** `/24` becomes `0xFFFFFF00`. */
    public fun maskOf(prefixLength: Int): Int {
        require(prefixLength in 0..32) { "prefix length must be 0..32, was $prefixLength" }
        return if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
    }

    /**
     * The directed broadcast address for a subnet, e.g. `192.168.1.37/24` -> `192.168.1.255`.
     *
     * The UDP beacon sends here as well as to the multicast group, because several access points
     * and hotspot drivers drop multicast while forwarding directed broadcast. Sending to
     * 255.255.255.255 instead would not be routed off the primary interface, so the per-interface
     * address is the one that works on a multi-homed machine.
     */
    public fun broadcastAddress(address: String, prefixLength: Int): String? {
        val addr = parse(address) ?: return null
        if (prefixLength !in 1..31) return null // /32 has no broadcast; /0 is meaningless here
        val mask = maskOf(prefixLength)
        return format((addr and mask) or mask.inv())
    }

    /** True when both addresses sit in the same subnet. Used to diagnose `SUBNET_MISMATCH`. */
    public fun sameSubnet(a: String, b: String, prefixLength: Int): Boolean {
        val x = parse(a) ?: return false
        val y = parse(b) ?: return false
        val mask = maskOf(prefixLength)
        return (x and mask) == (y and mask)
    }

    /** RFC 1918 plus the link-local range. Anything else on a LAN is suspicious. */
    public fun isPrivate(address: String): Boolean {
        val a = parse(address) ?: return false
        val b1 = (a ushr 24) and 0xFF
        val b2 = (a ushr 16) and 0xFF
        return when {
            b1 == 10 -> true
            b1 == 172 && b2 in 16..31 -> true
            b1 == 192 && b2 == 168 -> true
            b1 == 169 && b2 == 254 -> true // link-local, 169.254/16
            else -> false
        }
    }

    /** `169.254.0.0/16`. Usually means DHCP failed, which is worth diagnosing separately. */
    public fun isLinkLocal(address: String): Boolean {
        val a = parse(address) ?: return false
        return ((a ushr 24) and 0xFF) == 169 && ((a ushr 16) and 0xFF) == 254
    }

    public fun isLoopback(address: String): Boolean =
        ((parse(address) ?: return false) ushr 24) and 0xFF == 127
}
