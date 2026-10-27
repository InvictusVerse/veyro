package com.veyro.core.companion

import com.veyro.core.common.VeyroLog
import com.veyro.core.link.PeerMessage
import com.veyro.core.model.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.UUID

/** One item in the phone's virtual clipboard (ADR-043). In memory only. */
public data class ClipItem(
    val id: String,
    /** The PC it came from. */
    val peerId: String?,
    val text: String?,
    val image: ByteArray?,
    val atMillis: Long,
) {
    val isImage: Boolean get() = image != null

    override fun equals(other: Any?): Boolean = other is ClipItem && other.id == id && other.peerId == peerId
    override fun hashCode(): Int = id.hashCode() * 31 + (peerId?.hashCode() ?: 0)
}

/** Posts the "PC copied ..." notification with a Copy action. Implemented in :app. */
public fun interface ClipNotifier {
    public fun show(item: ClipItem)
}

/**
 * Clipboard between this phone and its PCs (ADR-043, 04-PROTOCOL.md 6.5).
 *
 *  - [share]: text or a link the user shared or selected ("Send to PC"). The PC puts it on its
 *    clipboard right away (`apply = true`).
 *  - [sendClipboard]: the phone's own clipboard, read while Veyro is open (Android 10+ allows no
 *    other time). The PC keeps it in its virtual clipboard (`apply = false`).
 *  - A PC's own copies arrive as `clip` and land in [history] (the phone's virtual clipboard) with
 *    a notification, while [receiveEnabled] ("Auto-fetch PC clipboard", default on) is on.
 *
 * History is newest first, at most [MAX_ITEMS], in memory only. Contents are never logged.
 */
public class ClipboardBridge(
    private val links: Flow<List<CompanionLink>>,
    private val receiveEnabled: Flow<Boolean>,
    private val notifier: ClipNotifier,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    public companion object {
        public const val MAX_ITEMS: Int = 50
        private const val TAG = "Clipboard"

        /** PNG or JPEG by magic bytes, within the protocol cap. Untrusted input check. */
        public fun isAcceptableImage(bytes: ByteArray?): Boolean {
            if (bytes == null || bytes.size < 4 || bytes.size > PeerMessage.Clip.MAX_IMAGE_BYTES) return false
            val png = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
            return png || jpeg
        }
    }

    private val _history = MutableStateFlow<List<ClipItem>>(emptyList())
    public val history: StateFlow<List<ClipItem>> = _history.asStateFlow()

    private val current = MutableStateFlow<List<CompanionLink>>(emptyList())
    private val seen = LinkedHashSet<String>()
    private var job: Job? = null

    @Synchronized
    public fun start(): Job = job?.takeIf { it.isActive } ?: scope.launch {
        launch { links.collect { current.value = it } }
        links.forEachLink { link -> serve(link) }
    }.also { job = it }

    /**
     * Shared text or link: the PC applies it. [peerId] limits it to one PC (picked in the share
     * sheet's Direct Share row); null sends to every capable PC. Returns how many PCs it reached.
     */
    public suspend fun share(text: String, peerId: String? = null): Int {
        val trimmed = text.take(PeerMessage.Clip.MAX_TEXT_CHARS)
        if (trimmed.isBlank()) return 0
        return broadcast(PeerMessage.Clip(newId(), PeerMessage.Clip.KIND_TEXT, text = trimmed, apply = true, ts = now()), peerId)
    }

    /** The phone's clipboard (text or image): kept in the PC's virtual clipboard. */
    public suspend fun sendClipboard(text: String?, image: ByteArray?): Int {
        val clip = when {
            image != null && isAcceptableImage(image) ->
                PeerMessage.Clip(newId(), PeerMessage.Clip.KIND_IMAGE, image = Base64.getEncoder().encodeToString(image), ts = now())
            !text.isNullOrBlank() ->
                PeerMessage.Clip(newId(), PeerMessage.Clip.KIND_TEXT, text = text.take(PeerMessage.Clip.MAX_TEXT_CHARS), ts = now())
            else -> return 0
        }
        return broadcast(clip)
    }

    public fun remove(item: ClipItem) {
        _history.update { list -> list.filterNot { it == item } }
    }

    public fun clear() {
        _history.value = emptyList()
    }

    private suspend fun broadcast(clip: PeerMessage.Clip, onlyPeer: String? = null): Int {
        var reached = 0
        for (link in current.value) {
            if (onlyPeer != null && !link.peerDeviceId.equals(onlyPeer, ignoreCase = true)) continue
            val capable = runCatching { Capability.CLIPBOARD in link.peerCapabilities.first() }.getOrDefault(false)
            if (!capable) continue
            runCatching { link.send(clip) }
                .onSuccess { reached++ }
                .onFailure { VeyroLog.w(TAG) { "Clip not sent: ${it.javaClass.simpleName}" } }
        }
        return reached
    }

    private suspend fun serve(link: CompanionLink) {
        try {
            link.incoming.collect { message ->
                if (message is PeerMessage.Clip) receive(link.peerDeviceId, message)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "Clipboard session ended: ${t.javaClass.simpleName}" }
        }
    }

    /** A PC's copy. Untrusted: validated and size-capped. */
    internal suspend fun receive(peerId: String?, clip: PeerMessage.Clip) {
        if (!receiveEnabled.first()) return
        if (clip.id.isEmpty() || clip.id.length > 64) return
        val item = when (clip.kind) {
            PeerMessage.Clip.KIND_TEXT -> {
                val text = clip.text?.takeIf { it.isNotEmpty() && it.length <= PeerMessage.Clip.MAX_TEXT_CHARS } ?: return
                ClipItem(clip.id, peerId, text, null, now())
            }
            PeerMessage.Clip.KIND_IMAGE -> {
                val bytes = clip.image?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                if (!isAcceptableImage(bytes)) return
                ClipItem(clip.id, peerId, null, bytes, now())
            }
            else -> return
        }
        synchronized(seen) {
            if (!seen.add("$peerId|${clip.id}")) return
            if (seen.size > 4 * MAX_ITEMS) seen.remove(seen.first())
        }
        _history.update { (listOf(item) + it).take(MAX_ITEMS) }
        runCatching { notifier.show(item) }
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
}
