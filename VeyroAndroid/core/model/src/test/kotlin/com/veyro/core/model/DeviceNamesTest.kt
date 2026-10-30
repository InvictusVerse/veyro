package com.veyro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ADR-059: what a device name from the network or a nickname must look like. */
class DeviceNamesTest {

    @Test
    fun `a name is trimmed and kept as typed`() {
        assertEquals("Office PC", DeviceNames.clean("  Office PC \t"))
        assertEquals("Pixel 9 Pro", DeviceNames.clean("Pixel 9 Pro"))
        assertEquals("Wohnzimmer-PC", DeviceNames.clean("Wohnzimmer-PC"))
        assertEquals("書斎のPC", DeviceNames.clean("書斎のPC"))
        // An emoji sequence joined with ZWJ is a normal name, not a control character.
        assertEquals("Family \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", DeviceNames.clean("Family \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"))
    }

    @Test
    fun `1 to 64 characters`() {
        assertEquals("A", DeviceNames.clean("A"))
        assertEquals("A".repeat(64), DeviceNames.clean("A".repeat(64)))
        assertNull(DeviceNames.clean("A".repeat(65)))
        assertNull(DeviceNames.clean(""))
        assertNull(DeviceNames.clean("    "))
        assertNull(DeviceNames.clean(null))
    }

    @Test
    fun `control characters are rejected, not repaired`() {
        listOf(
            "Bell\u0007",
            "Two\nlines",
            "Tab\tinside",
            "Nul\u0000",
            "Delete\u007F",
            "Next line\u0085",
            "Line\u2028sep",
            "Right\u202Eto left",
            "Isolate\u2066x\u2069",
        ).forEach { assertNull("'${it.length} chars' must be rejected", DeviceNames.clean(it)) }
    }
}
