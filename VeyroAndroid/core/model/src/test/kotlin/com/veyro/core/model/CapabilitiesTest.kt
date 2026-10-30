package com.veyro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-052: what one phone uses with another, whatever the other advertises. */
class CapabilitiesTest {

    @Test
    fun `with a phone only files, media and battery are used`() {
        val used = Capabilities.usedWith(Platform.ANDROID, Capabilities.ANDROID_CURRENT)
        assertEquals(Capabilities.PHONE_TO_PHONE, used)
        // ADR-054: shared text and links reach another phone as a clipboard notification.
        listOf(Capability.FILE_TRANSFER, Capability.MEDIA_CONTROL, Capability.DEVICE_STATUS, Capability.CLIPBOARD)
            .forEach { assertTrue(it in used) }
        listOf(
            Capability.NOTIFICATION_MIRROR,
            Capability.MESSAGING,
            Capability.CALLS,
            Capability.PHOTOS,
            Capability.WALLPAPER,
            // ADR-060: another phone never drives this phone's flashlight, volume or brightness.
            Capability.PHONE_CONTROLS,
        ).forEach { assertFalse("$it must not flow between phones", it in used) }
        // ADR-059: names are harmless between phones and a rename there should show too.
        assertTrue(Capability.DEVICE_INFO in used)
    }

    @Test
    fun `the new bits keep their assigned numbers and Android advertises them`() {
        assertEquals(0x40000, Capability.PHONE_CONTROLS.bit)
        assertEquals(0x200000, Capability.DEVICE_INFO.bit)
        assertTrue(Capability.PHONE_CONTROLS in Capabilities.ANDROID_CURRENT)
        assertTrue(Capability.DEVICE_INFO in Capabilities.ANDROID_CURRENT)
        // Append-only: no two capabilities share a bit.
        assertEquals(Capability.entries.size, Capability.entries.map { it.bit }.toSet().size)
    }

    @Test
    fun `a phone that advertises less gets no more, and a PC keeps everything`() {
        val filesOnly = Capabilities.of(Capability.FILE_TRANSFER)
        assertEquals(filesOnly, Capabilities.usedWith(Platform.ANDROID, filesOnly))
        assertEquals(Capabilities.ANDROID_CURRENT, Capabilities.usedWith(Platform.WINDOWS, Capabilities.ANDROID_CURRENT))
        assertEquals(Capabilities.ANDROID_CURRENT, Capabilities.usedWith(Platform.UNKNOWN, Capabilities.ANDROID_CURRENT))
    }
}
