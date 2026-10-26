package com.veyro.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRegistryTest {

    @Test
    fun `open inbound creates window with 6 digit pin and valid expiration`() {
        val registry = PairingRegistry()
        val before = System.currentTimeMillis()
        val window = registry.openInbound("peer-123", "Pixel 8", "local-phone")
        val after = System.currentTimeMillis()

        assertEquals("peer-123", window.peerDeviceId)
        assertEquals("Pixel 8", window.peerDeviceName)
        assertEquals(6, window.pin.length)
        assertTrue(window.pin.all { it.isDigit() })
        assertTrue(window.expiresAt in (before + PairingRegistry.PIN_TTL_MS)..(after + PairingRegistry.PIN_TTL_MS))
        assertTrue(registry.hasOpenWindow())

        val lookedUp = registry.lookup("peer-123")
        assertNotNull(lookedUp)
        assertEquals(window.pin, lookedUp?.pin)
    }

    @Test
    fun `initiator key derived matches inbound target bootstrap key`() {
        val registry = PairingRegistry()
        val initiatorId = "device-initiator"
        val targetId = "device-target"

        // Target opens inbound window (peer = initiator, local = target)
        val window = registry.openInbound(
            peerDeviceId = initiatorId,
            peerDeviceName = "Initiator",
            localDeviceId = targetId,
        )

        // Initiator derives with the same PIN (local = initiator, peer = target)
        val initiatorKey = registry.deriveInitiatorKey(
            pin = window.pin,
            localDeviceId = initiatorId,
            peerDeviceId = targetId,
        )

        assertEquals(
            "Bootstrap key on initiator must match bootstrap key on target",
            window.bootstrapKey,
            initiatorKey,
        )
    }

    @Test
    fun `five failed attempts burns the window`() {
        val registry = PairingRegistry()
        val window = registry.openInbound("hostile-peer", "Bad Actor", "local-phone")

        for (i in 1..4) {
            registry.recordAttempt("hostile-peer")
            assertNotNull("Window must still be active before reaching MAX_ATTEMPTS", registry.lookup("hostile-peer"))
        }

        // 5th failed attempt
        registry.recordAttempt("hostile-peer")
        assertNull("5 failed attempts must burn the window", registry.lookup("hostile-peer"))
        assertFalse(registry.hasOpenWindow())
    }

    @Test
    fun `closing window removes it cleanly`() {
        val registry = PairingRegistry()
        registry.openInbound("peer-a", "Device A", "self")
        assertTrue(registry.hasOpenWindow())

        registry.close("peer-a")
        assertNull(registry.lookup("peer-a"))
        assertFalse(registry.hasOpenWindow())
    }

    @Test
    fun `closeAll empties registry`() {
        val registry = PairingRegistry()
        registry.openInbound("peer-1", "D1", "self")
        registry.openInbound("peer-2", "D2", "self")
        assertTrue(registry.hasOpenWindow())

        registry.closeAll()
        assertFalse(registry.hasOpenWindow())
        assertNull(registry.lookup("peer-1"))
        assertNull(registry.lookup("peer-2"))
    }
}
