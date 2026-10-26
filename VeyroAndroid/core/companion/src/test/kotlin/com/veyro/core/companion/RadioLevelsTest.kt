package com.veyro.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `devStatus` radio fields (ADR-038, 04-PROTOCOL.md 6.2.1) as the phone computes them. */
class RadioLevelsTest {

    @Test
    fun `wifi bars follow the RSSI thresholds and unknown readings stay unknown`() {
        assertEquals(4, RadioLevels.wifiLevelFromRssi(-40))
        assertEquals(4, RadioLevels.wifiLevelFromRssi(-55))
        assertEquals(3, RadioLevels.wifiLevelFromRssi(-60))
        assertEquals(2, RadioLevels.wifiLevelFromRssi(-70))
        assertEquals(1, RadioLevels.wifiLevelFromRssi(-85))
        assertEquals(0, RadioLevels.wifiLevelFromRssi(-95))
        assertNull(RadioLevels.wifiLevelFromRssi(null))
        assertNull(RadioLevels.wifiLevelFromRssi(-127))
        assertNull(RadioLevels.wifiLevelFromRssi(Int.MIN_VALUE))
    }

    @Test
    fun `wifi is -1 when not connected and never reads as off while connected`() {
        assertEquals(-1, RadioLevels.wifiField(wifiConnected = false, level = 3))
        assertEquals(3, RadioLevels.wifiField(wifiConnected = true, level = 3))
        assertEquals(0, RadioLevels.wifiField(wifiConnected = true, level = null))
    }

    @Test
    fun `cell is -1 without a ready SIM or a level`() {
        assertEquals(-1, RadioLevels.cellField(simReady = false, level = 4))
        assertEquals(-1, RadioLevels.cellField(simReady = true, level = null))
        assertEquals(2, RadioLevels.cellField(simReady = true, level = 2))
        assertEquals(4, RadioLevels.cellField(simReady = true, level = 9))
    }

    @Test
    fun `platform levels are rescaled to 0 to 4`() {
        assertEquals(4, RadioLevels.scale(4, 4))
        assertEquals(2, RadioLevels.scale(1, 2))
        assertEquals(0, RadioLevels.scale(0, 3))
        assertEquals(4, RadioLevels.scale(3, 3))
        assertEquals(3, RadioLevels.scale(2, 3))
    }
}
