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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The wallpaper size cap (04-PROTOCOL.md 6.2.1) and [WallpaperBridge] with fakes (ADR-038). */
@OptIn(ExperimentalCoroutinesApi::class)
class WallpaperTest {

    // ── Size cap ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `scaled size keeps the aspect ratio, caps the longest side and never upscales`() {
        assertEquals(324 to 720, WallpaperEncoding.scaledSize(1440, 3200))
        assertEquals(720 to 405, WallpaperEncoding.scaledSize(3840, 2160))
        assertEquals(500 to 300, WallpaperEncoding.scaledSize(500, 300))
        assertEquals(1 to 720, WallpaperEncoding.scaledSize(1, 10_000))
        assertEquals(360 to 203, WallpaperEncoding.scaledSize(3840, 2160, maxSide = 360))
    }

    @Test
    fun `fit picks the best quality under 96 KiB at the largest side that fits`() {
        val tried = mutableListOf<Pair<Int, Int>>()
        // A fake encoder: size grows with side and quality.
        val result = WallpaperEncoding.fit { side, quality ->
            tried += side to quality
            ByteArray(side * quality * 2)
        }
        // 720 * q * 2 <= 98304  =>  q <= 68, so 65 is the first that fits at 720.
        assertEquals(720 * 65 * 2, result!!.size)
        assertTrue(result.size <= WallpaperEncoding.MAX_BYTES)
        assertEquals(listOf(720 to 85, 720 to 75, 720 to 65), tried)
    }

    @Test
    fun `fit falls back to a smaller side, and gives up rather than exceed the cap`() {
        val smaller = WallpaperEncoding.fit { side, _ -> ByteArray(if (side >= 540) 200_000 else 50_000) }
        assertEquals(50_000, smaller!!.size)

        val never = WallpaperEncoding.fit { _, _ -> ByteArray(WallpaperEncoding.MAX_BYTES + 1) }
        assertNull(never)

        assertNull("an encoder failure is not retried forever", WallpaperEncoding.fit { _, _ -> null })
    }

    @Test
    fun `only JPEGs within limits are accepted from the PC`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertTrue(WallpaperEncoding.acceptsIncoming(jpeg))
        assertFalse(WallpaperEncoding.acceptsIncoming("PNG".toByteArray()))
        assertFalse(WallpaperEncoding.acceptsIncoming(ByteArray(0)))
        val huge = ByteArray(WallpaperEncoding.MAX_BYTES * 2).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        assertFalse(WallpaperEncoding.acceptsIncoming(huge))
    }

    // ── Bridge ──────────────────────────────────────────────────────────────────────────────

    private class FakeLink(caps: Capabilities) : CompanionLink {
        override val peerDeviceId: String = PC_ID
        val caps = MutableStateFlow(caps)
        val inbound = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 16)
        val sent = mutableListOf<PeerMessage>()
        override val peerCapabilities: Flow<Capabilities> get() = caps
        override val incoming: Flow<PeerMessage> get() = inbound
        override suspend fun send(message: PeerMessage) {
            sent += message
        }
        val wallpapers get() = sent.filterIsInstance<PeerMessage.Wallpaper>()
    }

    private class FakeSource : WallpaperSource {
        val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        var jpeg: ByteArray? = JPEG_A
        override val changes: Flow<Unit> get() = changeFlow
        override suspend fun currentJpeg(): ByteArray? = jpeg
    }

    private class FakeCache : PeerWallpaperCache {
        val saved = mutableMapOf<String, ByteArray?>()
        var initial: Map<String, ByteArray> = emptyMap()
        override suspend fun loadAll(): Map<String, ByteArray> = initial
        override suspend fun save(deviceId: String, jpeg: ByteArray?) {
            saved[deviceId] = jpeg
        }
    }

    private class Rig(
        val bridge: WallpaperBridge,
        val links: MutableStateFlow<CompanionLink?>,
        val enabled: MutableStateFlow<Boolean>,
        val source: FakeSource,
        val cache: FakeCache,
    )

    private fun TestScope.rig(enabled: Boolean = true, cached: Map<String, ByteArray> = emptyMap()): Rig {
        val links = MutableStateFlow<CompanionLink?>(null)
        val on = MutableStateFlow(enabled)
        val source = FakeSource()
        val cache = FakeCache().apply { initial = cached }
        val bridge = WallpaperBridge(links.asLinkList(), on, source, cache, backgroundScope)
        bridge.start()
        runCurrent()
        return Rig(bridge, links, on, source, cache)
    }

    private val wallpaperCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.WALLPAPER)

    @Test
    fun `sends the wallpaper on connect and on change, never twice the same`() = runTest {
        val rig = rig()
        val link = FakeLink(wallpaperCaps)
        rig.links.value = link
        runCurrent()
        assertEquals(listOf(b64(JPEG_A)), link.wallpapers.map { it.jpeg })

        rig.source.changeFlow.emit(Unit) // spurious
        runCurrent()
        rig.source.jpeg = JPEG_B
        rig.source.changeFlow.emit(Unit)
        runCurrent()
        assertEquals(listOf(b64(JPEG_A), b64(JPEG_B)), link.wallpapers.map { it.jpeg })
    }

    @Test
    fun `unreadable or switched off sends wallpaper without jpeg`() = runTest {
        val rig = rig()
        rig.source.jpeg = null
        val link = FakeLink(wallpaperCaps)
        rig.links.value = link
        runCurrent()
        assertEquals(listOf<String?>(null), link.wallpapers.map { it.jpeg })

        rig.source.jpeg = JPEG_A
        rig.bridge.refresh() // e.g. the photo permission was just granted
        runCurrent()
        rig.enabled.value = false
        runCurrent()
        assertEquals(listOf(null, b64(JPEG_A), null), link.wallpapers.map { it.jpeg })
    }

    @Test
    fun `nothing is sent to a PC without the capability, but its wallpaper is kept`() = runTest {
        val rig = rig()
        val link = FakeLink(Capabilities.of(Capability.FILE_TRANSFER))
        rig.links.value = link
        runCurrent()
        assertTrue(link.wallpapers.isEmpty())

        link.inbound.emit(PeerMessage.Wallpaper(jpeg = b64(JPEG_B)))
        runCurrent()
        assertArrayEquals(JPEG_B, rig.bridge.peerWallpapers.value[PC_ID])
        assertArrayEquals(JPEG_B, rig.cache.saved[PC_ID])

        link.inbound.emit(PeerMessage.Wallpaper(jpeg = null))
        runCurrent()
        assertNull(rig.bridge.peerWallpapers.value[PC_ID])
        assertTrue(rig.cache.saved.containsKey(PC_ID) && rig.cache.saved[PC_ID] == null)
    }

    @Test
    fun `a non-JPEG from the PC is ignored, and the cached one survives a disconnect`() = runTest {
        val rig = rig(cached = mapOf(PC_ID to JPEG_A))
        assertArrayEquals(JPEG_A, rig.bridge.peerWallpapers.value[PC_ID])

        val link = FakeLink(wallpaperCaps)
        rig.links.value = link
        runCurrent()
        link.inbound.emit(PeerMessage.Wallpaper(jpeg = Base64.getEncoder().encodeToString("<svg/>".toByteArray())))
        runCurrent()
        rig.links.value = null
        runCurrent()
        assertArrayEquals(JPEG_A, rig.bridge.peerWallpapers.value[PC_ID])
    }

    private companion object {
        // A canonical DeviceId, as the session reports it.
        val PC_ID: String = com.veyro.core.model.DeviceId.fromPublicKey(ByteArray(32) { 7 }).value
        val JPEG_A = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val JPEG_B = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 8, 7, 6)
        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}
