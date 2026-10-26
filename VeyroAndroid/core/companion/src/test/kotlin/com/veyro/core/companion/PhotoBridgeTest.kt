package com.veyro.core.companion

import com.veyro.core.link.LinkMessage
import com.veyro.core.link.OutgoingFile
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

/** [PhotoBridge] gating, clamps, thumbnail batching and size cap, fetch routing and live diffing. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoBridgeTest {

    private class FakeLink(caps: Capabilities) : CompanionLink {
        val caps = MutableStateFlow(caps)
        val inbound = MutableSharedFlow<LinkMessage>(extraBufferCapacity = 16)
        val sent = mutableListOf<LinkMessage>()
        override val peerCapabilities: Flow<Capabilities> get() = caps
        override val incoming: Flow<LinkMessage> get() = inbound
        override suspend fun send(message: LinkMessage) {
            sent += message
        }
        inline fun <reified T : LinkMessage> of(): List<T> = sent.filterIsInstance<T>()
    }

    /** JPEG sizes per quality; a quality not listed encodes to [defaultBytes]. */
    private class FakeThumb(val sizes: Map<Int, Int>, val defaultBytes: Int, val tried: MutableList<Int>) : PhotoThumbnail {
        var closed = false
        override fun encodeJpeg(quality: Int): ByteArray {
            tried += quality
            return ByteArray(sizes[quality] ?: defaultBytes) { quality.toByte() }
        }
        override fun close() {
            closed = true
        }
    }

    private class FakeSource : PhotoSource {
        val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        var readable = true
        val store = mutableListOf<LinkMessage.PhotoInfo>()
        val listLimits = mutableListOf<Int>()
        val thumbSizes = mutableListOf<Int>()
        val tried = mutableListOf<Int>()
        var thumbSizesByQuality: Map<Int, Int> = emptyMap()
        var thumbDefaultBytes = 1024
        val thumbs = mutableListOf<FakeThumb>()
        var afterCalls = 0

        override val changes: Flow<Unit> get() = changeFlow
        override fun canRead(): Boolean = readable
        override suspend fun photos(limit: Int): List<LinkMessage.PhotoInfo> {
            listLimits += limit
            return store.sortedByDescending { it.ts }.take(limit)
        }
        override suspend fun photosAfter(id: Long): List<LinkMessage.PhotoInfo> {
            afterCalls++
            return store.filter { it.id > id }.sortedBy { it.id }
        }
        override suspend fun ids(): Set<Long> = store.map { it.id }.toSet()
        override suspend fun thumbnail(id: Long, size: Int): PhotoThumbnail? {
            thumbSizes += size
            if (store.none { it.id == id }) return null
            return FakeThumb(thumbSizesByQuality, thumbDefaultBytes, tried).also { thumbs += it }
        }
        override suspend fun original(id: Long): OutgoingFile? {
            val photo = store.firstOrNull { it.id == id } ?: return null
            return OutgoingFile(photo.name, 3, photo.ts) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        }
    }

    private class FakeSender : PhotoSender {
        val sends = mutableListOf<Pair<String, String>>()
        override suspend fun send(link: CompanionLink, file: OutgoingFile, requestId: String): Boolean {
            sends += file.effectiveName to requestId
            return true
        }
    }

    private val photoCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.PHOTOS)
    private val plainCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.MESSAGING)

    private fun photo(id: Long, ts: Long = id * 1000) =
        LinkMessage.PhotoInfo(id = id, name = "IMG_$id.jpg", ts = ts, mime = "image/jpeg", bucket = "camera")

    private class Rig(
        val links: MutableStateFlow<CompanionLink?>,
        val enabled: MutableStateFlow<Boolean>,
        val source: FakeSource,
        val sender: FakeSender,
    )

    private fun TestScope.rig(enabled: Boolean = true, photos: List<LinkMessage.PhotoInfo> = emptyList()): Rig {
        val links = MutableStateFlow<CompanionLink?>(null)
        val on = MutableStateFlow(enabled)
        val source = FakeSource().apply { store += photos }
        val sender = FakeSender()
        PhotoBridge(links, on, source, sender, backgroundScope).start()
        runCurrent()
        return Rig(links, on, source, sender)
    }

    private fun TestScope.connect(rig: Rig, caps: Capabilities = photoCaps): FakeLink {
        val link = FakeLink(caps)
        rig.links.value = link
        runCurrent()
        return link
    }

    private suspend fun TestScope.request(link: FakeLink, message: LinkMessage) {
        link.inbound.emit(message)
        runCurrent()
    }

    // ── Gating ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `nothing is served without the capability bit`() = runTest {
        val rig = rig(photos = listOf(photo(1)))
        val link = connect(rig, plainCaps)
        request(link, LinkMessage.PhotosRequest())
        request(link, LinkMessage.PhotoThumbRequest(listOf(1)))
        request(link, LinkMessage.PhotoFetch(1, "r-1"))
        assertTrue(link.sent.isEmpty())
        assertTrue(rig.source.listLimits.isEmpty())
        assertTrue(rig.sender.sends.isEmpty())
    }

    @Test
    fun `switched off answers empty lists, imageless thumbs and ignores fetches`() = runTest {
        val rig = rig(enabled = false, photos = listOf(photo(1), photo(2)))
        val link = connect(rig)
        request(link, LinkMessage.PhotosRequest())
        request(link, LinkMessage.PhotoThumbRequest(listOf(1, 2)))
        request(link, LinkMessage.PhotoFetch(1, "r-1"))

        assertEquals(listOf(LinkMessage.Photos(emptyList())), link.of<LinkMessage.Photos>())
        assertEquals(listOf(LinkMessage.PhotoThumb(1), LinkMessage.PhotoThumb(2)), link.of<LinkMessage.PhotoThumb>())
        assertTrue("the store is not read while switched off", rig.source.listLimits.isEmpty() && rig.source.thumbSizes.isEmpty())
        assertTrue(rig.sender.sends.isEmpty())
    }

    @Test
    fun `without the permission answers are empty too, never errors`() = runTest {
        val rig = rig(photos = listOf(photo(1)))
        rig.source.readable = false
        val link = connect(rig)
        request(link, LinkMessage.PhotosRequest())
        request(link, LinkMessage.PhotoThumbRequest(listOf(1)))
        request(link, LinkMessage.PhotoFetch(1, "r-1"))

        assertEquals(listOf(LinkMessage.Photos(emptyList())), link.of<LinkMessage.Photos>())
        assertEquals(listOf(LinkMessage.PhotoThumb(1)), link.of<LinkMessage.PhotoThumb>())
        assertTrue(rig.sender.sends.isEmpty())
    }

    @Test
    fun `turning the switch on serves the list newest first`() = runTest {
        val rig = rig(enabled = false, photos = listOf(photo(1), photo(3), photo(2)))
        val link = connect(rig)
        rig.enabled.value = true
        runCurrent()
        request(link, LinkMessage.PhotosRequest())
        assertEquals(listOf(3L, 2L, 1L), link.of<LinkMessage.Photos>().single().items.map { it.id })
    }

    // ── Clamps and batching ────────────────────────────────────────────────────────────────

    @Test
    fun `list limit is clamped to 1 to 2000`() = runTest {
        val rig = rig(photos = listOf(photo(1), photo(2)))
        val link = connect(rig)
        request(link, LinkMessage.PhotosRequest(limit = 0))
        request(link, LinkMessage.PhotosRequest(limit = 50_000))
        assertEquals(listOf(1, PhotoBridge.MAX_LIMIT), rig.source.listLimits)
        assertEquals(1, link.of<LinkMessage.Photos>().first().items.size)
    }

    @Test
    fun `thumbnail size is clamped to 64 to 512`() = runTest {
        val rig = rig(photos = listOf(photo(1)))
        val link = connect(rig)
        request(link, LinkMessage.PhotoThumbRequest(listOf(1), size = 8))
        request(link, LinkMessage.PhotoThumbRequest(listOf(1), size = 4096))
        assertEquals(listOf(PhotoBridge.MIN_THUMB_SIZE, PhotoBridge.MAX_THUMB_SIZE), rig.source.thumbSizes)
    }

    @Test
    fun `one thumb per id, at most 50 per request, in order`() = runTest {
        val photos = (1L..80L).map { photo(it) }
        val rig = rig(photos = photos)
        val link = connect(rig)
        request(link, LinkMessage.PhotoThumbRequest(photos.map { it.id }))

        val thumbs = link.of<LinkMessage.PhotoThumb>()
        assertEquals(PhotoBridge.MAX_THUMB_IDS, thumbs.size)
        assertEquals((1L..50L).toList(), thumbs.map { it.id })
        assertTrue(thumbs.all { it.jpeg != null })
        assertTrue("every decoded thumbnail is released", rig.source.thumbs.all { it.closed })
    }

    @Test
    fun `an unreadable id gets a thumb without an image`() = runTest {
        val rig = rig(photos = listOf(photo(1)))
        val link = connect(rig)
        request(link, LinkMessage.PhotoThumbRequest(listOf(1, 999)))
        val thumbs = link.of<LinkMessage.PhotoThumb>()
        assertEquals(listOf(1L, 999L), thumbs.map { it.id })
        assertTrue(thumbs[0].jpeg != null)
        assertNull(thumbs[1].jpeg)
    }

    // ── Size cap ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `quality is lowered until the jpeg fits 48 KiB`() = runTest {
        val rig = rig(photos = listOf(photo(1)))
        rig.source.thumbSizesByQuality = mapOf(85 to 90_000, 70 to 60_000, 55 to PhotoBridge.MAX_THUMB_BYTES)
        val link = connect(rig)
        request(link, LinkMessage.PhotoThumbRequest(listOf(1)))

        assertEquals(listOf(85, 70, 55), rig.source.tried)
        val jpeg = Base64.getDecoder().decode(link.of<LinkMessage.PhotoThumb>().single().jpeg)
        assertEquals(PhotoBridge.MAX_THUMB_BYTES, jpeg.size)
        assertTrue("the 55 encoding was the one sent", jpeg.all { it == 55.toByte() })
    }

    @Test
    fun `a thumbnail that never fits is omitted`() = runTest {
        val rig = rig(photos = listOf(photo(1)))
        rig.source.thumbDefaultBytes = PhotoBridge.MAX_THUMB_BYTES + 1
        val link = connect(rig)
        request(link, LinkMessage.PhotoThumbRequest(listOf(1)))

        assertEquals(PhotoBridge.JPEG_QUALITIES.toList(), rig.source.tried)
        assertEquals(listOf(LinkMessage.PhotoThumb(1)), link.of<LinkMessage.PhotoThumb>())
        assertTrue(rig.source.thumbs.single().closed)
    }

    // ── Fetch ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `photoFetch sends the original with the request id`() = runTest {
        val rig = rig(photos = listOf(photo(4021)))
        val link = connect(rig)
        request(link, LinkMessage.PhotoFetch(4021, "r-1"))
        assertEquals(listOf("IMG_4021.jpg" to "r-1"), rig.sender.sends)
        assertTrue("the original goes as a transfer, not a companion message", link.sent.isEmpty())
    }

    @Test
    fun `photoFetch for an unknown or out-of-scope id sends nothing`() = runTest {
        val rig = rig(photos = listOf(photo(1)))
        val link = connect(rig)
        request(link, LinkMessage.PhotoFetch(77, "r-2"))
        assertTrue(rig.sender.sends.isEmpty())
        assertTrue(link.sent.isEmpty())
    }

    // ── Live updates ───────────────────────────────────────────────────────────────────────

    @Test
    fun `new photos above the mark and removed listed ids are pushed after the debounce`() = runTest {
        val rig = rig(photos = listOf(photo(1), photo(2), photo(3)))
        val link = connect(rig)
        request(link, LinkMessage.PhotosRequest())

        rig.source.store.removeAll { it.id == 2L }
        rig.source.store += photo(4)
        rig.source.changeFlow.emit(Unit)
        runCurrent()
        assertTrue("nothing before the debounce", link.of<LinkMessage.PhotoNew>().isEmpty())

        advanceTimeBy(PhotoBridge.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(listOf(4L), link.of<LinkMessage.PhotoNew>().map { it.item.id })
        assertEquals(listOf(LinkMessage.PhotoRemoved(2)), link.of<LinkMessage.PhotoRemoved>())

        // A removed photo the PC was told about via photoNew is reported too; nothing twice.
        rig.source.store.removeAll { it.id == 4L }
        rig.source.changeFlow.emit(Unit)
        advanceTimeBy(PhotoBridge.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(listOf(LinkMessage.PhotoRemoved(2), LinkMessage.PhotoRemoved(4)), link.of<LinkMessage.PhotoRemoved>())
        assertEquals(1, link.of<LinkMessage.PhotoNew>().size)
    }

    @Test
    fun `a burst of changes is one pass and photos present at start are not new`() = runTest {
        val rig = rig(photos = listOf(photo(1), photo(2)))
        val link = connect(rig)

        rig.source.store += photo(3)
        repeat(5) { rig.source.changeFlow.emit(Unit) }
        advanceTimeBy(PhotoBridge.DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(1, rig.source.afterCalls)
        assertEquals(listOf(3L), link.of<LinkMessage.PhotoNew>().map { it.item.id })
        assertTrue("nothing listed yet, so nothing can be removed", link.of<LinkMessage.PhotoRemoved>().isEmpty())
    }

    @Test
    fun `no live updates while switched off`() = runTest {
        val rig = rig(enabled = false, photos = listOf(photo(1)))
        val link = connect(rig)
        rig.source.store += photo(2)
        rig.source.changeFlow.emit(Unit)
        advanceTimeBy(PhotoBridge.DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(link.sent.isEmpty())
        assertEquals(0, rig.source.afterCalls)
    }
}
