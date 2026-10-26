package com.veyro.core.network

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectLifecycleTest {

    private fun mapFailureReason(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ERROR (0) - System failure or internal driver error"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED (1) - P2P is unsupported or Wi-Fi radio is toggled off"
        WifiP2pManager.BUSY -> "BUSY (2) - Framework is busy with another P2P operation or group"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS (3) - No service discovery requests posted"
        else -> "UNKNOWN_CODE ($reason)"
    }

    @Test
    fun `android p2p failure reason codes map accurately to diagnostic descriptions`() {
        assertTrue(mapFailureReason(0).contains("ERROR"))
        assertTrue(mapFailureReason(1).contains("P2P_UNSUPPORTED"))
        assertTrue(mapFailureReason(2).contains("BUSY"))
        assertTrue(mapFailureReason(3).contains("NO_SERVICE_REQUESTS"))
        assertTrue(mapFailureReason(42).contains("UNKNOWN_CODE"))
    }

    @Test
    fun `WifiDirectLinkInfo preserves complete group credentials and interface info`() {
        val linkInfo = WifiDirectLinkInfo(
            groupOwnerAddress = "192.168.49.1",
            localAddress = "192.168.49.1",
            isGroupOwner = true,
            networkName = "DIRECT-vy-SamsungA20s",
            passphrase = "secretPassphrase123",
            interfaceName = "p2p-wlan0-0",
        )

        assertEquals("192.168.49.1", linkInfo.groupOwnerAddress)
        assertEquals("192.168.49.1", linkInfo.localAddress)
        assertTrue(linkInfo.isGroupOwner)
        assertEquals("DIRECT-vy-SamsungA20s", linkInfo.networkName)
        assertEquals("secretPassphrase123", linkInfo.passphrase)
        assertEquals("p2p-wlan0-0", linkInfo.interfaceName)
    }

    @Test
    fun `client role preserves distinct local and group owner addresses`() {
        val clientInfo = WifiDirectLinkInfo(
            groupOwnerAddress = "192.168.49.1",
            localAddress = "192.168.49.155",
            isGroupOwner = false,
            networkName = "DIRECT-vy-SamsungA20s",
        )

        assertEquals("192.168.49.1", clientInfo.groupOwnerAddress)
        assertEquals("192.168.49.155", clientInfo.localAddress)
        assertFalse(clientInfo.isGroupOwner)
    }

    @Test
    fun `group owner link info without passphrase indicates credentials not available`() {
        // Simulates a case where groupInfo returns null passphrase (API limitation)
        val linkInfo = WifiDirectLinkInfo(
            groupOwnerAddress = "192.168.49.1",
            localAddress = "192.168.49.1",
            isGroupOwner = true,
            networkName = "DIRECT-ab-TestDevice",
            passphrase = null,
            interfaceName = "p2p-wlan0-0",
        )

        assertNull(linkInfo.passphrase)
        assertNotNull(linkInfo.networkName)
        assertTrue(linkInfo.isGroupOwner)
    }

    @Test
    fun `WifiDirectLinkInfo without networkName indicates group not fully formed`() {
        // When group credentials are not available, networkName is null
        val linkInfo = WifiDirectLinkInfo(
            groupOwnerAddress = "192.168.49.1",
            localAddress = "192.168.49.1",
            isGroupOwner = true,
            networkName = null,
            passphrase = null,
        )

        assertNull(linkInfo.networkName)
        assertNull(linkInfo.passphrase)
    }

    @Test
    fun `WifiDirectManager isAvailable requires both isSupported and isEnabled`() {
        // Test the default interface method
        val manager = object : WifiDirectManager {
            override val isSupported = true
            override val isEnabled = false
            override suspend fun prepareP2pLink(isInitiator: Boolean) = null
            override suspend fun closeP2pLink() {}
        }
        assertFalse("isAvailable must be false when isEnabled is false", manager.isAvailable)

        val manager2 = object : WifiDirectManager {
            override val isSupported = false
            override val isEnabled = true
            override suspend fun prepareP2pLink(isInitiator: Boolean) = null
            override suspend fun closeP2pLink() {}
        }
        assertFalse("isAvailable must be false when isSupported is false", manager2.isAvailable)

        val manager3 = object : WifiDirectManager {
            override val isSupported = true
            override val isEnabled = true
            override suspend fun prepareP2pLink(isInitiator: Boolean) = null
            override suspend fun closeP2pLink() {}
        }
        assertTrue("isAvailable must be true when both are true", manager3.isAvailable)
    }
}
