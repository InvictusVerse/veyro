package com.veyro.core.companion

import com.veyro.core.common.VeyroLog
import com.veyro.core.link.PeerMessage
import com.veyro.core.link.OutgoingFile
import com.veyro.core.model.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.Base64

/**
 * A decoded thumbnail, already scaled so its longest side fits the requested size.
 * [encodeJpeg] may be called several times with falling quality; [close] frees the pixels.
 */
public interface PhotoThumbnail : Closeable {
    public fun encodeJpeg(quality: Int): ByteArray
}

/**
 * Where photos come from. On Android, `MediaStore.Images` ([AndroidPhotoSource]).
 *
 * Scope is Phone Link's: camera roll and screenshots only. Every id-taking method answers null
 * for an id outside that scope, so the PC can never reach an arbitrary image by guessing ids.
 */
public interface PhotoSource {

    /** Hot "the image store changed" signal. Spurious signals are fine. */
    public val changes: Flow<Unit>

    /** Whether the read permission is held right now. */
    public fun canRead(): Boolean

    /** Up to [limit] in-scope photos, newest first. */
    public suspend fun photos(limit: Int): List<PeerMessage.PhotoInfo>

    /** In-scope photos with an id greater than [id], ascending by id. */
    public suspend fun photosAfter(id: Long): List<PeerMessage.PhotoInfo>

    /** Every in-scope id right now, for detecting removals. */
    public suspend fun ids(): Set<Long>

    /** The thumbnail of [id] with its longest side at most [size], or null when unreadable. */
    public suspend fun thumbnail(id: Long, size: Int): PhotoThumbnail?

    /** The original of [id] as a sendable file, or null when unknown or out of scope. */
    public suspend fun original(id: Long): OutgoingFile?
}

/** Sends a photo original to the peer over the [link]'s own session, answering [requestId]. */
public fun interface PhotoSender {
    public suspend fun send(link: CompanionLink, file: OutgoingFile, requestId: String): Boolean
}

/**
 * Serves recent photos to the connected PC the way Phone Link does (ADR-034, 04-PROTOCOL 6.4).
 *
 * Per link that advertises [Capability.PHOTOS]:
 *  - `photosReq` is answered with `photos`, newest first, `limit` clamped to 1..[MAX_LIMIT];
 *  - `photoThumbReq` is answered with one `photoThumb` per id (at most [MAX_THUMB_IDS]), served one
 *    request at a time so a scrolling PC cannot flood the socket; each send waits on the socket;
 *  - `photoFetch` makes the phone send the original as a normal transfer over the same session,
 *    with `offer.requestId` set; fetches run one at a time, like any offer on the session;
 *  - while switched on, `photoNew` / `photoRemoved` follow the store, debounced by [DEBOUNCE_MS].
 *
 * Switched off or without the read permission, lists are empty, thumbnails carry no image and
 * fetches are ignored. Nothing is ever answered with an error. File names and images are user
 * data: nothing here logs them, only counts.
 */
public class PhotoBridge(
    /** Every live paired session (multiple PCs). */
    private val links: Flow<List<CompanionLink>>,
    private val enabled: Flow<Boolean>,
    private val source: PhotoSource,
    private val sender: PhotoSender,
    private val scope: CoroutineScope,
) {

    public companion object {
        private const val TAG = "PhotoBridge"

        public const val DEBOUNCE_MS: Long = 500L
        public const val MAX_LIMIT: Int = 2000
        public const val MAX_THUMB_IDS: Int = 50
        public const val MIN_THUMB_SIZE: Int = 64
        public const val MAX_THUMB_SIZE: Int = 512
        public const val MAX_THUMB_BYTES: Int = 48 * 1024

        /** Tried in order until the JPEG fits [MAX_THUMB_BYTES]. */
        internal val JPEG_QUALITIES: IntArray = intArrayOf(85, 70, 55, 40, 25)

        /** Thumbnail requests waiting to be served; beyond this the oldest (scrolled past) go. */
        private const val PENDING_THUMB_REQUESTS = 64
        private const val PENDING_FETCHES = 16
    }

    private var job: Job? = null

    /** Starts observing. Idempotent; returns the running job. */
    @Synchronized
    public fun start(): Job = job?.takeIf { it.isActive } ?: scope.launch { run() }.also { job = it }

    private suspend fun run() {
        // One session per connected PC: new and removed photos are pushed to every capable PC;
        // lists, thumbnails and originals go back to the PC that asked, on its own session.
        links.forEachLink { link ->
            link.peerCapabilities
                .map { Capability.PHOTOS in it }
                .distinctUntilChanged()
                .collectLatest { capable -> if (capable) serve(link) }
        }
    }

    /** What the PC has been told exists, so removals can be reported. One per session. */
    private class Known {
        val mutex = Mutex()
        val ids = HashSet<Long>()

        /** Highest id the PC has been told about; null until the store was first readable. */
        var mark: Long? = null

        /** Tracks [enabled] for this session's per-request checks; written only by [serve]. */
        val on = MutableStateFlow(false)
    }

    private fun available(known: Known): Boolean = known.on.value && source.canRead()

    private suspend fun serve(link: CompanionLink) {
        try {
            coroutineScope {
                val known = Known()
                val on = known.on
                on.value = enabled.first()
                launch { enabled.collect { on.value = it } }
                val thumbs = Channel<PeerMessage.PhotoThumbRequest>(PENDING_THUMB_REQUESTS, BufferOverflow.DROP_OLDEST)
                val fetches = Channel<PeerMessage.PhotoFetch>(PENDING_FETCHES, BufferOverflow.DROP_OLDEST)
                launch { for (request in thumbs) serveThumbs(link, known, request) }
                launch { for (request in fetches) fetch(link, known, request) }
                launch {
                    // Never blocks: the session's incoming buffer drops what a slow collector misses.
                    link.incoming.collect { message ->
                        when (message) {
                            is PeerMessage.PhotosRequest -> launch { link.send(list(message, known)) }
                            is PeerMessage.PhotoThumbRequest -> thumbs.trySend(message)
                            is PeerMessage.PhotoFetch -> fetches.trySend(message)
                            else -> Unit
                        }
                    }
                }
                on.collectLatest { enabled -> if (enabled) push(link, known) }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Usually the socket closing under a send. The next session starts afresh.
            VeyroLog.w(TAG) { "Photo session ended: ${t.javaClass.simpleName}" }
        }
    }

    private suspend fun list(request: PeerMessage.PhotosRequest, known: Known): PeerMessage.Photos {
        if (!available(known)) return PeerMessage.Photos(emptyList())
        val limit = request.limit.coerceIn(1, MAX_LIMIT)
        val items = safely(emptyList()) { source.photos(limit) }.take(limit)
        known.mutex.withLock {
            known.ids.clear()
            items.mapTo(known.ids) { it.id }
            val top = items.maxOfOrNull { it.id }
            if (top != null) known.mark = maxOf(known.mark ?: top, top)
        }
        VeyroLog.d(TAG) { "photos: ${items.size}" }
        return PeerMessage.Photos(items)
    }

    private suspend fun serveThumbs(link: CompanionLink, known: Known, request: PeerMessage.PhotoThumbRequest) {
        val size = request.size.coerceIn(MIN_THUMB_SIZE, MAX_THUMB_SIZE)
        val ids = request.ids.take(MAX_THUMB_IDS)
        val readable = available(known)
        var served = 0
        for (id in ids) {
            val jpeg = if (readable) safely(null) { encodeThumbnail(id, size) } else null
            if (jpeg != null) served++
            // One frame per id, in order; send suspends while the socket is backed up.
            link.send(PeerMessage.PhotoThumb(id = id, jpeg = jpeg))
        }
        VeyroLog.d(TAG) { "photoThumb: $served of ${ids.size} with an image" }
    }

    /** Base64 JPEG within [MAX_THUMB_BYTES], lowering quality as needed; null if it never fits. */
    private suspend fun encodeThumbnail(id: Long, size: Int): String? {
        val image = source.thumbnail(id, size) ?: return null
        return image.use {
            for (quality in JPEG_QUALITIES) {
                val bytes = it.encodeJpeg(quality)
                if (bytes.size <= MAX_THUMB_BYTES) return@use Base64.getEncoder().encodeToString(bytes)
            }
            null
        }
    }

    private suspend fun fetch(link: CompanionLink, known: Known, request: PeerMessage.PhotoFetch) {
        if (!available(known)) return
        val file = safely(null) { source.original(request.id) } ?: run {
            VeyroLog.i(TAG) { "photoFetch for an unknown or out-of-scope photo; ignored" }
            return
        }
        val ok = try {
            sender.send(link, file, request.requestId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "photoFetch send failed: ${t.javaClass.simpleName}" }
            false
        }
        VeyroLog.i(TAG) { "photoFetch answered: ok=$ok" }
    }

    /** `photoNew` for every photo above the mark, `photoRemoved` for every told-about id now gone. */
    private suspend fun push(link: CompanionLink, known: Known) {
        coroutineScope {
            val signals = Channel<Unit>(Channel.CONFLATED)
            // Subscribed before the mark is taken, so nothing inserted in between is missed.
            launch(start = CoroutineStart.UNDISPATCHED) {
                source.changes.collect { signals.trySend(Unit) }
            }
            known.mutex.withLock {
                if (known.mark == null && source.canRead()) known.mark = safely(null) { source.ids().maxOrNull() ?: 0L }
            }
            while (true) {
                signals.receive()
                // Debounce: a burst of provider notifications (insert, then metadata) is one pass.
                delay(DEBOUNCE_MS)
                signals.tryReceive()
                if (!source.canRead()) continue
                known.mutex.withLock { pass(link, known) }
            }
        }
    }

    private suspend fun pass(link: CompanionLink, known: Known) {
        val mark = known.mark
        if (mark == null) {
            // Readable for the first time (permission just granted): start from here.
            known.mark = safely(null) { source.ids().maxOrNull() ?: 0L }
            return
        }
        val fresh = safely(emptyList()) { source.photosAfter(mark) }.sortedBy { it.id }
        var top: Long = mark
        for (item in fresh) {
            if (item.id <= top) continue
            link.send(PeerMessage.PhotoNew(item))
            known.ids += item.id
            top = item.id
        }
        known.mark = top

        var removed = 0
        if (known.ids.isNotEmpty()) {
            val present = safely(null) { source.ids() }
            if (present != null) {
                val gone = known.ids.filterNot { it in present }
                for (id in gone) {
                    link.send(PeerMessage.PhotoRemoved(id))
                    known.ids -= id
                }
                removed = gone.size
            }
        }
        if (fresh.isNotEmpty() || removed > 0) VeyroLog.d(TAG) { "Pushed ${fresh.size} new, $removed removed" }
    }

    private suspend fun <T> safely(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        VeyroLog.w(TAG) { "Photo read failed: ${t.javaClass.simpleName}" }
        fallback
    }
}
