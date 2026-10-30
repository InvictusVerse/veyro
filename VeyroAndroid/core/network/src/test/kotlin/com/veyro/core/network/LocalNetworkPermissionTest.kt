package com.veyro.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-061: when Veyro asks for ACCESS_LOCAL_NETWORK and what Home shows without it. */
class LocalNetworkPermissionTest {

    private fun state(sdk: Int, granted: Boolean = false, asked: Boolean = false, rationale: Boolean = false) =
        LocalNetworkPermission.state(sdk, granted, asked, rationale)

    @Test
    fun `below Android 17 it is never needed, whatever the grant says`() {
        for (sdk in listOf(29, 30, 33, 34, 35, 36)) {
            assertEquals(LocalNetworkState.NOT_NEEDED, state(sdk))
            assertEquals(LocalNetworkState.NOT_NEEDED, state(sdk, asked = true, rationale = true))
            assertFalse(LocalNetworkPermission.requestAtLaunch(state(sdk)))
            assertFalse(LocalNetworkPermission.requestOnPairPage(state(sdk)))
            assertFalse(LocalNetworkPermission.showNotice(state(sdk)))
            assertEquals(LocalNetworkFix.NONE, LocalNetworkPermission.fix(state(sdk)))
        }
    }

    @Test
    fun `on Android 17 a fresh install is asked once at launch`() {
        val fresh = state(37)
        assertEquals(LocalNetworkState.NOT_ASKED, fresh)
        assertTrue(fresh.isBlocked)
        assertTrue(LocalNetworkPermission.requestAtLaunch(fresh))
        assertTrue(LocalNetworkPermission.requestOnPairPage(fresh))
        assertEquals(LocalNetworkFix.REQUEST, LocalNetworkPermission.fix(fresh))
        assertEquals("Allow", LocalNetworkPermission.actionLabel(fresh))
    }

    @Test
    fun `granted needs nothing and shows nothing`() {
        val granted = state(37, granted = true, asked = true)
        assertEquals(LocalNetworkState.GRANTED, granted)
        assertFalse(granted.isBlocked)
        assertFalse(LocalNetworkPermission.requestAtLaunch(granted))
        assertFalse(LocalNetworkPermission.showNotice(granted))
        assertNull(LocalNetworkPermission.actionLabel(granted))
        // Granted through the group (Nearby devices) before Veyro ever asked: still granted.
        assertEquals(LocalNetworkState.GRANTED, state(38, granted = true))
    }

    @Test
    fun `a first denial asks again from the notice, not at launch`() {
        val denied = state(37, asked = true, rationale = true)
        assertEquals(LocalNetworkState.DENIED_CAN_ASK, denied)
        assertTrue(denied.isBlocked)
        assertFalse("launch does not nag", LocalNetworkPermission.requestAtLaunch(denied))
        assertFalse(LocalNetworkPermission.requestOnPairPage(denied))
        assertTrue(LocalNetworkPermission.showNotice(denied))
        assertEquals(LocalNetworkFix.REQUEST, LocalNetworkPermission.fix(denied))
    }

    @Test
    fun `a final denial or a revoke sends the user to App info`() {
        val blocked = state(37, asked = true, rationale = false)
        assertEquals(LocalNetworkState.DENIED_SETTINGS, blocked)
        assertTrue(blocked.isBlocked)
        assertTrue(LocalNetworkPermission.showNotice(blocked))
        assertEquals(LocalNetworkFix.OPEN_SETTINGS, LocalNetworkPermission.fix(blocked))
        assertEquals("Open settings", LocalNetworkPermission.actionLabel(blocked))
        assertTrue(LocalNetworkPermission.explanation(blocked).contains("Nearby devices"))
    }

    @Test
    fun `never asked still shows the notice, so a dismissed launch request is explained`() {
        assertTrue(LocalNetworkPermission.showNotice(state(37)))
    }
}
