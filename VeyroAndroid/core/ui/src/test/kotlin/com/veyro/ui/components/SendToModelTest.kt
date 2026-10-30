package com.veyro.ui.components

import com.veyro.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-072: the "Send to" sheet's summary line, device order and TalkBack text. */
class SendToModelTest {

    @Test
    fun `summary names what is sent`() {
        assertEquals("2 photos", sendSummary(SendContent.Files(listOf("image/jpeg", "image/png"))))
        assertEquals("1 photo", sendSummary(SendContent.Files(listOf("image/heic"))))
        assertEquals("3 videos", sendSummary(SendContent.Files(listOf("video/mp4", "video/mp4", "video/webm"))))
        assertEquals("2 photos and videos", sendSummary(SendContent.Files(listOf("image/jpeg", "video/mp4"))))
        assertEquals("2 files", sendSummary(SendContent.Files(listOf("image/jpeg", "application/pdf"))))
        assertEquals("1 file", sendSummary(SendContent.Files(listOf(null))))
        assertEquals("Link", sendSummary(SendContent.Text("https://example.com/a?b=1")))
        assertEquals("Text", sendSummary(SendContent.Text("see https://example.com")))
        assertEquals("Text", sendSummary(SendContent.Text("hello")))
        assertEquals("Folder Trip", sendSummary(SendContent.Folder("Trip")))
        assertEquals("A folder, chosen next", sendSummary(SendContent.Folder(null)))
        assertEquals("Files, chosen next", sendSummary(SendContent.FilesToPick))
    }

    @Test
    fun `link detection`() {
        assertTrue(isLink("  http://a.b  "))
        assertFalse(isLink("https://"))
        assertFalse(isLink("ftp://a.b"))
        assertFalse(isLink(""))
    }

    @Test
    fun `the preferred device comes first, the rest keep their order`() {
        val a = SendTarget("a", "Office PC", Platform.WINDOWS)
        val b = SendTarget("b", "Pixel", Platform.ANDROID)
        val c = SendTarget("c", "Laptop", Platform.WINDOWS)
        assertEquals(listOf("b", "a", "c"), orderSendTargets(listOf(a, b, c), "b").map { it.id })
        assertEquals(listOf("a", "b", "c"), orderSendTargets(listOf(a, b, c), null).map { it.id })
        assertEquals(listOf("a", "b", "c"), orderSendTargets(listOf(a, b, c), "gone").map { it.id })
        assertEquals(listOf("a", "b"), orderSendTargets(listOf(a, b, a), null).map { it.id })
    }

    @Test
    fun `talkback reads name, kind, state and battery`() {
        assertEquals("Office PC, PC, connected", sendTargetDescription(SendTarget("a", "Office PC", Platform.WINDOWS)))
        assertEquals(
            "Pixel, phone, connected, battery 80 percent, charging",
            sendTargetDescription(SendTarget("b", "Pixel", Platform.ANDROID, battery = 80, charging = true)),
        )
        assertEquals("All connected (3)", allConnectedLabel(3))
    }
}
