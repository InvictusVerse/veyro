package com.veyro.core.companion

import com.veyro.core.link.PeerMessage
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** [ClipboardBridge] with fakes (ADR-043). */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardBridgeTest {

    private class FakeLink(caps: Capabilities, override val peerDeviceId: String = "PC-A") : CompanionLink {
        val inbound = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 64)
        val sent = mutableListOf<PeerMessage>()
        override val peerCapabilities: Flow<Capabilities> = MutableStateFlow(caps)
        override val incoming: Flow<PeerMessage> get() = inbound
        override suspend fun send(message: PeerMessage) {
            sent += message
        }
        val clips get() = sent.filterIsInstance<PeerMessage.Clip>()
    }

    private class Rig(
        val bridge: ClipboardBridge,
        val links: MutableStateFlow<List<CompanionLink>>,
        val enabled: MutableStateFlow<Boolean>,
        val shown: MutableList<ClipItem>,
    )

    private fun TestScope.rig(vararg links: CompanionLink): Rig {
        val flow = MutableStateFlow(links.toList())
        val enabled = MutableStateFlow(true)
        val shown = mutableListOf<ClipItem>()
        val bridge = ClipboardBridge(flow, enabled, { shown += it }, backgroundScope, now = { 1_000L })
        bridge.start()
        runCurrent()
        return Rig(bridge, flow, enabled, shown)
    }

    private val clipCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.CLIPBOARD)

    @Test
    fun `share goes to every capable PC with apply, and skips the rest`() = runTest {
        val a = FakeLink(clipCaps, "PC-A")
        val b = FakeLink(clipCaps, "PC-B")
        val old = FakeLink(Capabilities.of(Capability.FILE_TRANSFER), "PC-OLD")
        val rig = rig(a, b, old)

        assertEquals(2, rig.bridge.share("https://youtu.be/x"))
        assertEquals(listOf("https://youtu.be/x"), a.clips.map { it.text })
        assertTrue(a.clips.single().apply)
        assertEquals(1, b.clips.size)
        assertTrue(old.sent.isEmpty())
    }

    @Test
    fun `a share picked for one PC goes only to that PC`() = runTest {
        val a = FakeLink(clipCaps, "PC-A")
        val b = FakeLink(clipCaps, "PC-B")
        val rig = rig(a, b)
        assertEquals(1, rig.bridge.share("hello", peerId = "pc-b"))
        assertTrue(a.clips.isEmpty())
        assertEquals(listOf("hello"), b.clips.map { it.text })
        assertEquals(0, rig.bridge.share("hello", peerId = "PC-GONE"))
    }

    @Test
    fun `blank shares send nothing and long text is capped`() = runTest {
        val a = FakeLink(clipCaps)
        val rig = rig(a)
        assertEquals(0, rig.bridge.share("   "))
        rig.bridge.share("x".repeat(PeerMessage.Clip.MAX_TEXT_CHARS + 10))
        assertEquals(PeerMessage.Clip.MAX_TEXT_CHARS, a.clips.single().text!!.length)
    }

    @Test
    fun `sendClipboard is stored on the PC, not applied, and prefers a valid image`() = runTest {
        val a = FakeLink(clipCaps)
        val rig = rig(a)
        assertEquals(1, rig.bridge.sendClipboard("note", null))
        assertEquals(1, rig.bridge.sendClipboard("ignored", PNG))
        assertEquals(1, rig.bridge.sendClipboard("fallback", "not an image".toByteArray()))
        assertEquals(0, rig.bridge.sendClipboard(null, null))

        val clips = a.clips
        assertTrue(clips.none { it.apply })
        assertEquals(PeerMessage.Clip.KIND_TEXT, clips[0].kind)
        assertEquals(PeerMessage.Clip.KIND_IMAGE, clips[1].kind)
        assertArrayEquals(PNG, Base64.getDecoder().decode(clips[1].image))
        assertEquals("fallback", clips[2].text)
    }

    @Test
    fun `no PC connected reaches nobody`() = runTest {
        val rig = rig()
        assertEquals(0, rig.bridge.share("hello"))
    }

    @Test
    fun `PC copies land newest first with a notification, once per id`() = runTest {
        val a = FakeLink(clipCaps)
        val rig = rig(a)
        a.inbound.emit(PeerMessage.Clip("1", PeerMessage.Clip.KIND_TEXT, text = "first"))
        a.inbound.emit(PeerMessage.Clip("2", PeerMessage.Clip.KIND_IMAGE, image = Base64.getEncoder().encodeToString(PNG)))
        a.inbound.emit(PeerMessage.Clip("1", PeerMessage.Clip.KIND_TEXT, text = "first"))
        runCurrent()

        val history = rig.bridge.history.value
        assertEquals(listOf("2", "1"), history.map { it.id })
        assertTrue(history[0].isImage)
        assertEquals("PC-A", history[1].peerId)
        assertEquals(2, rig.shown.size)
    }

    @Test
    fun `text another phone shared arrives as a sent clip with a notification`() = runTest {
        // ADR-054: "Send with Veyro" on a phone reaches another phone like a PC copy does.
        val phone = FakeLink(clipCaps, "PHONE-B")
        val rig = rig(phone)
        phone.inbound.emit(PeerMessage.Clip("s1", PeerMessage.Clip.KIND_TEXT, text = "https://example.com", apply = true))
        phone.inbound.emit(PeerMessage.Clip("c1", PeerMessage.Clip.KIND_TEXT, text = "copied"))
        runCurrent()

        val history = rig.bridge.history.value
        assertEquals(listOf("c1", "s1"), history.map { it.id })
        assertTrue("shared on purpose", history[1].sent)
        assertFalse("a copy", history[0].sent)
        assertEquals(2, rig.shown.size)
    }

    @Test
    fun `invalid or oversized items from the PC are dropped`() = runTest {
        val a = FakeLink(clipCaps)
        val rig = rig(a)
        a.inbound.emit(PeerMessage.Clip("1", PeerMessage.Clip.KIND_TEXT, text = ""))
        a.inbound.emit(PeerMessage.Clip("2", PeerMessage.Clip.KIND_TEXT, text = "x".repeat(PeerMessage.Clip.MAX_TEXT_CHARS + 1)))
        a.inbound.emit(PeerMessage.Clip("3", PeerMessage.Clip.KIND_IMAGE, image = Base64.getEncoder().encodeToString("<svg/>".toByteArray())))
        a.inbound.emit(PeerMessage.Clip("4", PeerMessage.Clip.KIND_IMAGE, image = "%%% not base64"))
        a.inbound.emit(PeerMessage.Clip("5", "file", text = "x"))
        a.inbound.emit(PeerMessage.Clip("", PeerMessage.Clip.KIND_TEXT, text = "no id"))
        runCurrent()
        assertTrue(rig.bridge.history.value.isEmpty())
        assertTrue(rig.shown.isEmpty())
    }

    @Test
    fun `auto-fetch off keeps nothing`() = runTest {
        val a = FakeLink(clipCaps)
        val rig = rig(a)
        rig.enabled.value = false
        a.inbound.emit(PeerMessage.Clip("1", PeerMessage.Clip.KIND_TEXT, text = "secret"))
        runCurrent()
        assertTrue(rig.bridge.history.value.isEmpty())
        assertFalse(rig.shown.isNotEmpty())
    }

    @Test
    fun `history keeps the newest 50, and remove and clear work`() = runTest {
        val a = FakeLink(clipCaps)
        val rig = rig(a)
        repeat(60) { a.inbound.emit(PeerMessage.Clip("id$it", PeerMessage.Clip.KIND_TEXT, text = "t$it")) }
        runCurrent()
        val history = rig.bridge.history.value
        assertEquals(ClipboardBridge.MAX_ITEMS, history.size)
        assertEquals("id59", history.first().id)

        rig.bridge.remove(history.first())
        assertEquals("id58", rig.bridge.history.value.first().id)
        rig.bridge.clear()
        assertTrue(rig.bridge.history.value.isEmpty())
    }

    @Test
    fun `images beyond the memory budget drop out oldest first, text stays`() {
        fun image(id: String, bytes: Int) = ClipItem(id, null, null, ByteArray(bytes), 0L)
        fun text(id: String) = ClipItem(id, null, "t", null, 0L)
        val mb2 = 2 * 1024 * 1024
        // Newest first: five 2 MB images (10 MB) with text in between.
        val items = listOf(image("i5", mb2), text("t3"), image("i4", mb2), image("i3", mb2), text("t2"),
            image("i2", mb2), image("i1", mb2), text("t1"))

        val kept = ClipboardBridge.trim(items).map { it.id }

        assertEquals(listOf("i5", "t3", "i4", "i3", "t2", "i2", "t1"), kept)
        // The newest image stays even when it alone is over the budget.
        val big = image("big", ClipboardBridge.MAX_IMAGE_BYTES_TOTAL + 1)
        assertEquals(listOf("big", "t1"), ClipboardBridge.trim(listOf(big, image("old", 10), text("t1"))).map { it.id })
    }

    @Test
    fun `image check accepts only PNG or JPEG within the cap`() {
        assertTrue(ClipboardBridge.isAcceptableImage(PNG))
        assertTrue(ClipboardBridge.isAcceptableImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0)))
        assertFalse(ClipboardBridge.isAcceptableImage(null))
        assertFalse(ClipboardBridge.isAcceptableImage("GIF89a".toByteArray()))
        val huge = ByteArray(PeerMessage.Clip.MAX_IMAGE_BYTES + 1).also { PNG.copyInto(it) }
        assertFalse(ClipboardBridge.isAcceptableImage(huge))
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
