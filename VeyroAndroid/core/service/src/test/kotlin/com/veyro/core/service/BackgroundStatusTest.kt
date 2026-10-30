package com.veyro.core.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR-061: a problem replaces the status line, so a start from boot never fails silently. */
class BackgroundStatusTest {

    @Test
    fun `a problem replaces the looking line`() {
        val looking = BackgroundStatus(peerDeviceId = "ID", peerName = "Office PC", connected = false)
        assertEquals("Looking for Office PC", looking.title)
        val blocked = looking.copy(problemTitle = "Veyro can't reach your devices", problemText = "Tap to fix it.")
        assertEquals("Veyro can't reach your devices", blocked.title)
        assertEquals("Tap to fix it.", blocked.text)
    }
}
