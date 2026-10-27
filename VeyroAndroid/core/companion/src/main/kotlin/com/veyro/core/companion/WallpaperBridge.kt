package com.veyro.core.companion

import com.veyro.core.common.VeyroLog
import com.veyro.core.link.PeerMessage
import com.veyro.core.model.Capability
import com.veyro.core.model.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Base64

/** The phone's own wallpaper, already fitted to the wire limits. */
public interface WallpaperSource {
    /** Hot "the wallpaper may have changed" signal. Spurious signals are fine; sends are deduped. */
    public val changes: Flow<Unit>

    /**
     * The wallpaper as JPEG within [WallpaperEncoding.MAX_SIDE] and [WallpaperEncoding.MAX_BYTES],
     * or null when it cannot be read (no permission, live wallpaper, OEM refusal).
     */
    public suspend fun currentJpeg(): ByteArray?
}

/** Where the last wallpaper each PC sent is kept, so Home can draw it while disconnected. */
public interface PeerWallpaperCache {
    public suspend fun loadAll(): Map<String, ByteArray>
    /** Null removes the entry. */
    public suspend fun save(deviceId: String, jpeg: ByteArray?)
}

/** Size rules for `wallpaper` (04-PROTOCOL.md 6.2.1), pure so they are JVM-testable. */
public object WallpaperEncoding {
    public const val MAX_SIDE: Int = 720
    public const val MAX_BYTES: Int = 96 * 1024

    /** Longest sides tried in order when the image does not fit [MAX_BYTES] at any quality. */
    public val SIDES: List<Int> = listOf(MAX_SIDE, 540, 360)

    /** JPEG qualities tried in order, best first. */
    public val QUALITIES: List<Int> = listOf(85, 75, 65, 55, 45, 35, 25)

    /**
     * The largest size with the same aspect ratio whose longest side is at most [maxSide].
     * Never upscales; never returns a zero dimension.
     */
    public fun scaledSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "empty image" }
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /**
     * The best encoding under [maxBytes]: the first side in [sides] at which some quality fits,
     * at the highest quality that fits. Null if nothing fits, so the caller sends "no wallpaper"
     * rather than an oversized frame.
     */
    public fun fit(
        sides: List<Int> = SIDES,
        qualities: List<Int> = QUALITIES,
        maxBytes: Int = MAX_BYTES,
        encode: (maxSide: Int, quality: Int) -> ByteArray?,
    ): ByteArray? {
        for (side in sides) {
            for (quality in qualities) {
                val bytes = encode(side, quality) ?: return null
                if (bytes.size <= maxBytes) return bytes
            }
        }
        return null
    }

    /**
     * Whether bytes from a PC may be kept and drawn: a JPEG (SOI marker) no bigger than the limit
     * plus slack for encoders that round differently. Anything else is dropped (untrusted input).
     */
    public fun acceptsIncoming(bytes: ByteArray): Boolean =
        bytes.size in 3..(MAX_BYTES + MAX_BYTES / 2) &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
}

/**
 * Wallpapers in both directions (ADR-038, 04-PROTOCOL.md 6.2.1).
 *
 * Per live session:
 *  - keeps what the PC sends as [peerWallpapers] (by device id, also in [cache]), whether or not
 *    the PC advertises [Capability.WALLPAPER], since accepting a frame cannot hurt an older peer;
 *  - while the PC advertises the capability, sends this phone's wallpaper on connect, on every
 *    change, and after [refresh] (the photo permission was just granted). With [enabled] off, or
 *    when the wallpaper cannot be read, it sends `wallpaper` without `jpeg`. Identical content is
 *    not resent.
 *
 * Wallpapers are user data: their bytes are never logged.
 */
public class WallpaperBridge(
    /** Every live paired session (multiple PCs). */
    private val links: Flow<List<CompanionLink>>,
    private val enabled: Flow<Boolean>,
    private val source: WallpaperSource,
    private val cache: PeerWallpaperCache,
    private val scope: CoroutineScope,
) {

    private companion object {
        const val TAG = "Wallpaper"
    }

    private val _peerWallpapers = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    /** The last wallpaper each PC sent, by device id. Survives disconnects. */
    public val peerWallpapers: StateFlow<Map<String, ByteArray>> = _peerWallpapers.asStateFlow()

    private val pokes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var job: Job? = null

    /** Starts observing. Idempotent; returns the running job. */
    @Synchronized
    public fun start(): Job = job?.takeIf { it.isActive } ?: scope.launch { run() }.also { job = it }

    /** Re-reads and resends the phone's wallpaper, e.g. after the photo permission was granted. */
    public fun refresh() {
        pokes.tryEmit(Unit)
    }

    private suspend fun run() {
        runCatching { cache.loadAll() }
            .onSuccess { cached -> _peerWallpapers.update { cached.filterValues(WallpaperEncoding::acceptsIncoming) + it } }
            .onFailure { VeyroLog.w(TAG) { "Wallpaper cache unreadable: ${it.javaClass.simpleName}" } }
        // One session per connected PC: the phone's wallpaper goes to each capable PC, and each
        // PC's wallpaper is kept under its own id.
        links.forEachLink { link -> serve(link) }
    }

    private suspend fun serve(link: CompanionLink) {
        try {
            coroutineScope {
                launch {
                    link.incoming.collect { message ->
                        if (message is PeerMessage.Wallpaper) receive(link.peerDeviceId, message)
                    }
                }
                link.peerCapabilities
                    .map { Capability.WALLPAPER in it }
                    .distinctUntilChanged()
                    .collectLatest { capable -> if (capable) publishLoop(link) }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "Wallpaper session ended: ${t.javaClass.simpleName}" }
        }
    }

    private suspend fun publishLoop(link: CompanionLink) {
        var lastSent: ByteArray? = null
        var sentOnce = false
        combine(enabled.distinctUntilChanged(), merge(source.changes, pokes).onStart { emit(Unit) }) { on, _ -> on }
            .conflate()
            .collect { on ->
                val jpeg = if (on) runCatching { source.currentJpeg() }.getOrNull() else null
                if (sentOnce && jpeg.contentEqualsNullable(lastSent)) return@collect
                link.send(PeerMessage.Wallpaper(jpeg = jpeg?.let { Base64.getEncoder().encodeToString(it) }))
                VeyroLog.i(TAG) { if (jpeg != null) "Wallpaper sent" else "Wallpaper unavailable; sent none" }
                lastSent = jpeg
                sentOnce = true
            }
    }

    private suspend fun receive(peerId: String?, message: PeerMessage.Wallpaper) {
        val id = peerId?.let { DeviceId.parseOrNull(it)?.value ?: it } ?: return
        val bytes = message.jpeg?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        when {
            message.jpeg == null -> {
                _peerWallpapers.update { it - id }
                runCatching { cache.save(id, null) }
            }
            bytes == null || !WallpaperEncoding.acceptsIncoming(bytes) ->
                VeyroLog.d(TAG) { "Ignored a wallpaper that is not a JPEG within limits" }
            else -> {
                _peerWallpapers.update { it + (id to bytes) }
                runCatching { cache.save(id, bytes) }
                    .onFailure { VeyroLog.w(TAG) { "Wallpaper not cached: ${it.javaClass.simpleName}" } }
            }
        }
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> this.contentEquals(other)
}
