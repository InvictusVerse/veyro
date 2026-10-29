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
        listOf(Capability.FILE_TRANSFER, Capability.MEDIA_CONTROL, Capability.DEVICE_STATUS).forEach { assertTrue(it in used) }
        listOf(
            Capability.NOTIFICATION_MIRROR,
            Capability.MESSAGING,
            Capability.CALLS,
            Capability.PHOTOS,
            Capability.WALLPAPER,
            Capability.CLIPBOARD,
        ).forEach { assertFalse("$it must not flow between phones", it in used) }
    }

    @Test
    fun `a phone that advertises less gets no more, and a PC keeps everything`() {
        val filesOnly = Capabilities.of(Capability.FILE_TRANSFER)
        assertEquals(filesOnly, Capabilities.usedWith(Platform.ANDROID, filesOnly))
        assertEquals(Capabilities.ANDROID_CURRENT, Capabilities.usedWith(Platform.WINDOWS, Capabilities.ANDROID_CURRENT))
        assertEquals(Capabilities.ANDROID_CURRENT, Capabilities.usedWith(Platform.UNKNOWN, Capabilities.ANDROID_CURRENT))
    }
}
