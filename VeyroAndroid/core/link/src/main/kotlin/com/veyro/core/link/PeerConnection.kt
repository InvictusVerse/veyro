package com.veyro.core.link

import com.veyro.core.common.VeyroLog
import com.veyro.core.model.Capabilities
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

/**
 * An authenticated peer-to-peer connection over a WebSocket.
 *
 * ## Provenance
 *
 * A port of `services/ecosystem/transport/Session.kt` from the author's Invox code, which is a
 * working system. The structure is kept deliberately close to the original because every piece of it
 * exists for a reason that was learned the hard way:
 *
 *  - **Mode-mismatch rejection.** A plaintext frame arriving in an encrypted session is dropped
 *    before anything else. Without it, a peer could downgrade a session to plaintext by simply
 *    sending unencrypted frames.
 *  - **Replay cache.** Per-frame random nonce checked against a bounded LRU.
 *  - **Monotonic `seq` per sender.** Makes reorder and truncation detectable rather than silently
 *    accepted.
 *  - **Heartbeat with a dead-peer timeout.** A TCP connection to a device that walked out of Wi-Fi
 *    range can stay "open" for minutes. Without an application-level heartbeat a transfer hangs
 *    instead of failing.
 *  - **`rekey()`.** The pairing exchange starts under a PIN-derived key and must switch to the
 *    long-lived secret mid-conversation, without tearing the socket down.
 *
 * ## Deliberate improvements
 *
 *  - The incoming flow has `extraBufferCapacity` and `SUSPEND` overflow rather than drop-on-full.
 *    Invox carried control messages only; Veyro carries file chunks, and dropping a chunk because
 *    a buffer was full would corrupt a transfer silently. Suspending applies backpressure to the
 *    socket instead.
 *  - Uses standard `java.util.Base64` rather than `android.util.Base64`, enabling fast host JVM
 *    testing without Android runtime mock dependencies.
 */
public class PeerConnection(
    private val ws: WebSocketSession,
    initialKey: SecretKey,
    initialEncrypt: Boolean,
    public val localDeviceId: String,
    private val tag: String = "PeerConnection",
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    public val peerDeviceId: String? = null,
    public val enableHeartbeat: Boolean = true,
    public var remoteHost: String? = null,
    public var localHost: String? = null,
) {

    public companion object {
        /**
         * Inbound silence after which a ping is sent while a transfer is open on this session.
         * A stalled transfer has to fail within seconds, not minutes.
         */
        public const val HEARTBEAT_ACTIVE_MS: Long = 5_000L

        /**
         * The same with no transfer open. Each ping wakes the Wi-Fi radio on both devices, so an
         * idle session in the background pings at most twice a minute; any message from the peer
         * counts as proof of life and pushes the next ping back.
         */
        public const val HEARTBEAT_IDLE_MS: Long = 30_000L

        /**
         * A ping unanswered for this long and the peer is considered gone. Measured from the ping,
         * not from the last message, so time the phone spent asleep with nothing to say never
         * counts against the peer.
         */
        public const val HEARTBEAT_PONG_TIMEOUT_MS: Long = 15_000L

        /** Floor for one heartbeat sleep, so a burst of wake-ups cannot spin the loop. */
        private const val MIN_HEARTBEAT_WAIT_MS: Long = 500L

        /** Maximum allowed size for a single frame (24 MB) to prevent memory exhaustion DoS. */
        public const val MAX_FRAME_SIZE_BYTES: Long = 24L * 1024 * 1024

        /**
         * How long the read loop holds after a `pairOk` for [rekey] (ADR-056). Saving the pairing
         * takes milliseconds; this only bounds a caller that never rekeys.
         */
        internal const val REKEY_WAIT_MS: Long = 10_000L
    }

    @Volatile
    private var key: SecretKey = initialKey

    @Volatile
    private var encryptFrames: Boolean = initialEncrypt

    /**
     * Set while a `pairOk` waits for [rekey] (ADR-056). The side that sends pairOk switches to the
     * session key at once, so its next frame (another phone's Hello) is already under that key.
     * Read before the rekey, that frame failed authentication and was dropped, and this side
     * never learnt the peer's capabilities: phone-to-phone worked in one direction only.
     */
    @Volatile
    private var rekeyGate: CompletableDeferred<Unit>? = null

    private val nonceCache = NonceCache()
    private val seqOut = AtomicLong(0)
    private val lastSeqByPeer = HashMap<String, Long>()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var heartbeatJob: Job? = null
    private val heartbeatWake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var lastInboundAt: Long = System.currentTimeMillis()

    private val sendMutex = Mutex()

    @Volatile
    private var lastOutboundAt: Long = System.currentTimeMillis()

    @Volatile
    private var isClosedFlag: Boolean = false

    /**
     * While true the heartbeat neither pings nor declares the peer dead.
     *
     * Confirm pairing sets it while the user decides on the other device (ADR-031): the PC sends
     * nothing until Accept, and a ping in flight when both sides rekey would arrive under the wrong
     * key. Clearing it restarts the silence clock.
     */
    @Volatile
    public var heartbeatPaused: Boolean = false
        set(value) {
            if (!value) lastInboundAt = System.currentTimeMillis()
            field = value
        }

    public val isConnected: Boolean
        get() = !isClosedFlag && scope.isActive && ws.isActive

    private val _incoming = MutableSharedFlow<LinkEnvelope>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    public val incoming: SharedFlow<LinkEnvelope> = _incoming.asSharedFlow()

    private val _closed = MutableSharedFlow<String>(replay = 1)
    public val closed: SharedFlow<String> = _closed.asSharedFlow()

    private val _peerCapabilities = MutableStateFlow(Capabilities.NONE)

    /**
     * What the peer said it supports, from the most recent authenticated `hello`/`helloAck`.
     *
     * [Capabilities.NONE] until one arrives, so an older peer that never sends either - or sends
     * `capabilities = 0` - is never sent a message type it cannot decode (ADR-025).
     */
    public val peerCapabilities: StateFlow<Capabilities> = _peerCapabilities.asStateFlow()

    private val _unpaired = CompletableDeferred<Unit>()

    /**
     * Completes when the peer sends an authenticated `unpair` (ADR-042). Kept, not broadcast: a
     * device told right after it connects (ADR-057) must not miss it because the watcher
     * subscribed a moment later.
     */
    public val unpaired: kotlinx.coroutines.Deferred<Unit> get() = _unpaired

    private val mailboxes = ConcurrentHashMap<String, TransferMailbox>()
    private val completedTransfers = Collections.synchronizedSet(
        object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                if (size >= 256) {
                    val oldest = iterator().next()
                    remove(oldest)
                }
                return super.add(element)
            }
        }
    )
    private val offerChannel = Channel<LinkEnvelope>(capacity = 16)
    private val handshakeChannel = Channel<LinkEnvelope>(capacity = 16)

    public fun mailbox(transferId: String): TransferMailbox =
        mailboxes.computeIfAbsent(transferId) {
            // A transfer starts: the heartbeat switches to its short interval now, not after
            // the idle one runs out.
            heartbeatWake.trySend(Unit)
            TransferMailbox(transferId)
        }

    public fun closeMailbox(transferId: String) {
        val mb = mailboxes.remove(transferId)
        mb?.close()
        completedTransfers.add(transferId)
    }

    public suspend fun receiveOffer(): LinkEnvelope = offerChannel.receive()

    public suspend fun receiveHandshake(timeoutMs: Long): LinkEnvelope? = withTimeoutOrNull(timeoutMs) {
        handshakeChannel.receive()
    }

    public fun isTransferActive(transferId: String): Boolean = mailboxes.containsKey(transferId)
    public fun isTransferCompleted(transferId: String): Boolean = completedTransfers.contains(transferId)

    /** Switches from the PIN-derived bootstrap key to the long-lived session secret. */
    public suspend fun rekey(newKey: SecretKey) {
        sendMutex.withLock {
            key = newKey
            encryptFrames = true
            // Cleared because nonces are only meaningful per key: a nonce seen under the bootstrap key
            // is not a replay under the session key, and keeping them would reject valid frames.
            nonceCache.clear()
            seqOut.set(0)
            lastSeqByPeer.clear()
            VeyroLog.i(tag) { "Rekeyed to the session secret; encryption on" }
        }
        rekeyGate?.complete(Unit)
    }

    public fun outboundQueueBytes(): Long? = WebSocketQueueMonitor.getQueueSize(ws)

    public suspend fun flush() {
        runCatching { ws.flush() }
    }

    private val acceptedPayloadFrames = AtomicLong(0)

    /** Starts the read loop and the heartbeat. Returns a job that completes when the socket dies. */
    public fun start(onDead: (() -> Unit)? = null): Job = scope.launch {
        if (enableHeartbeat) {
            heartbeatJob = launch { heartbeat(onDead) }
        }
        var closeTrigger = "unknown"
        try {
            for (frame in ws.incoming) {
                if (frame is Frame.Close) {
                    val cr = (frame as? Frame.Close)?.readReason()
                    val code = cr?.code ?: -1
                    val known = cr?.knownReason?.name ?: "UNKNOWN"
                    val msg = cr?.message ?: ""
                    closeTrigger = "remote-close-frame(code=$code, known=$known, msg='$msg')"
                    VeyroLog.i(tag) { "Received Frame.Close from peer: code=$code ($known), message='$msg'" }
                    close(closeTrigger)
                    break
                }
                if (frame !is Frame.Text) continue
                if (frame.data.size > MAX_FRAME_SIZE_BYTES) {
                    closeTrigger = "oversized-frame(${frame.data.size} > $MAX_FRAME_SIZE_BYTES)"
                    VeyroLog.w(tag) { "Oversized frame received (${frame.data.size} bytes > $MAX_FRAME_SIZE_BYTES); closing session" }
                    close(closeTrigger)
                    break
                }
                handleRaw(frame.readText())
                // ADR-056: after a pairOk the next frame is under the session key; read it only
                // once that key is in place. The frames wait in the socket meanwhile.
                rekeyGate?.let { gate ->
                    if (withTimeoutOrNull(REKEY_WAIT_MS) { gate.await() } == null) {
                        VeyroLog.w(tag) { "No rekey within ${REKEY_WAIT_MS}ms of pairOk; reading on" }
                    }
                    rekeyGate = null
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                closeTrigger = "coroutine-cancellation: ${t.message}"
                VeyroLog.i(tag) { "Read loop cancelled: ${t.message}" }
            } else {
                closeTrigger = "exception: ${t.javaClass.simpleName}(${t.message})"
                VeyroLog.w(tag, t) { "Read loop terminated with exception: ${t.javaClass.name}: ${t.message}" }
            }
        } finally {
            isClosedFlag = true
            heartbeatJob?.cancel()
            offerChannel.close()
            handshakeChannel.close()
            mailboxes.values.forEach { it.close() }
            mailboxes.clear()

            val defaultWs = ws as? io.ktor.websocket.DefaultWebSocketSession
            val underlyingReason = runCatching {
                if (defaultWs != null && defaultWs.closeReason.isCompleted) {
                    defaultWs.closeReason.getCompleted()
                } else null
            }.getOrNull()
            VeyroLog.i(tag) {
                "Read loop exit summary: trigger='$closeTrigger', ws.closeReason=code=${underlyingReason?.code} (${underlyingReason?.knownReason}), msg='${underlyingReason?.message}'"
            }
            _closed.tryEmit(closeTrigger)
        }
    }

    public suspend fun send(message: PeerMessage): Unit = sendInternal(message, encryptFrames)

    /**
     * Sends one frame sealed regardless of session mode.
     *
     * Exists for `PairOk`, which carries the session secret while the conversation is still in
     * plaintext pairing mode. The secret must not cross the network in the clear, and the bootstrap
     * key is available to seal it with.
     */
    public suspend fun sendSealed(message: PeerMessage): Unit = sendInternal(message, encrypt = true)

    private suspend fun sendInternal(message: PeerMessage, encrypt: Boolean) {
        sendMutex.withLock {
            val seq = seqOut.incrementAndGet()
            lastOutboundAt = System.currentTimeMillis()
            val envelope = LinkEnvelope(
                msgId = UUID.randomUUID().toString(),
                from = localDeviceId,
                seq = seq,
                ts = lastOutboundAt,
                payload = message,
            )
            val payloadJson = PeerMessageJson.codec.encodeToString(LinkEnvelope.serializer(), envelope)
            val sealed = FrameCrypto.seal(payloadJson, key, encrypt, localDeviceId)
            val wireText = PeerMessageJson.codec.encodeToString(LinkWireFrame.serializer(), sealed.toWire())

            // Sampled logging for outbound sends (first chunk, last chunk, or every 20 chunks)
            if (message is PeerMessage.Chunk) {
                if (message.chunkIndex == 0 || message.chunkIndex % 20 == 0) {
                    val qSize = outboundQueueBytes() ?: -1L
                    VeyroLog.i(tag) {
                        "[diagnostics:sender-wire] transfer=${message.transferId} chunk #${message.chunkIndex} (seq=$seq, payloadBytes=${message.dataB64.length}, wireBytes=${wireText.length}, inFlightQueue=${qSize}B)"
                    }
                }
            } else if (message !is PeerMessage.Ping && message !is PeerMessage.Pong) {
                VeyroLog.i(tag) {
                    "[diagnostics:sender-ctrl] send ${message.javaClass.simpleName} (seq=$seq, wireBytes=${wireText.length})"
                }
            }

            ws.send(wireText)
        }
    }

    public suspend fun close(reason: String = "normal") {
        if (isClosedFlag) return
        isClosedFlag = true
        val caller = Thread.currentThread().stackTrace.getOrNull(3)?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
        VeyroLog.i(tag) { "Session closing initiated: reason='$reason', caller=$caller" }
        runCatching { ws.close(CloseReason(CloseReason.Codes.NORMAL, reason)) }
        scope.cancel()
        offerChannel.close()
        handshakeChannel.close()
        mailboxes.values.forEach { it.close() }
        mailboxes.clear()
        _closed.tryEmit(reason)
    }

    // ── Receive ────────────────────────────────────────────────────────────────────────────

    private suspend fun handleRaw(text: String) {
        lastInboundAt = System.currentTimeMillis()

        val wire = try {
            PeerMessageJson.codec.decodeFromString(LinkWireFrame.serializer(), text)
        } catch (t: Throwable) {
            VeyroLog.d(tag) { "Bad wire JSON: ${t.message}" }
            return
        }

        // Reject a downgrade attempt before spending any crypto on it.
        if (encryptFrames && wire.ciphertext == null) {
            VeyroLog.w(tag) { "Plaintext frame in an encrypted session; dropped" }
            return
        }

        if (!nonceCache.checkAndStore(wire.nonce)) {
            VeyroLog.w(tag) { "Replayed nonce rejected" }
            return
        }

        val sealed = wire.toSealed() ?: run {
            VeyroLog.d(tag) { "Malformed frame encoding" }
            return
        }

        val json = FrameCrypto.open(sealed, key) ?: run {
            // During pairing this is the normal signature of a wrong PIN.
            VeyroLog.d(tag) { "Frame failed authentication; dropped" }
            return
        }

        val envelope = try {
            PeerMessageJson.codec.decodeFromString(LinkEnvelope.serializer(), json)
        } catch (t: Throwable) {
            VeyroLog.d(tag) { "Envelope decode failed: ${t.message}" }
            return
        }

        // Monotonic per sender. A repeat or out-of-order frame is dropped rather than processed.
        val previous = lastSeqByPeer[envelope.from]
        if (previous != null && envelope.seq <= previous) {
            VeyroLog.w(tag) { "Stale seq from ${envelope.from} (${envelope.seq} <= $previous)" }
            return
        }
        lastSeqByPeer[envelope.from] = envelope.seq

        if (envelope.payload is PeerMessage.Ping) {
            scope.launch {
                runCatching {
                    send(PeerMessage.Pong((envelope.payload as PeerMessage.Ping).nonce))
                }
            }
            return
        }

        // Sampled logging for inbound chunk receives
        if (envelope.payload is PeerMessage.Chunk) {
            val chunk = envelope.payload as PeerMessage.Chunk
            val count = acceptedPayloadFrames.incrementAndGet()
            if (chunk.chunkIndex == 0 || chunk.chunkIndex % 20 == 0) {
                VeyroLog.i(tag) {
                    "[diagnostics:receiver-link] transfer=${chunk.transferId} chunk #${chunk.chunkIndex} accepted (seq=${envelope.seq}, frameBytes=${text.length}, totalAcceptedPayloadFrames=$count)"
                }
            }
        }

        // Broadcast to external observers (replay=0 prevents old frame replay)
        _incoming.tryEmit(envelope)

        // Route message by type and transfer ID
        when (val payload = envelope.payload) {
            is PeerMessage.TransferOffer -> {
                if (completedTransfers.contains(payload.transferId)) {
                    VeyroLog.w(tag) { "Dropping TransferOffer for completed transfer ${payload.transferId}" }
                    return
                }
                if (mailboxes.containsKey(payload.transferId)) {
                    VeyroLog.w(tag) { "Dropping duplicate TransferOffer for active transfer ${payload.transferId}" }
                    return
                }
                offerChannel.trySend(envelope)
            }

            is PeerMessage.TransferDecision -> {
                if (completedTransfers.contains(payload.transferId)) {
                    VeyroLog.d(tag) { "Dropping TransferDecision for completed transfer ${payload.transferId}" }
                    return
                }
                mailbox(payload.transferId).deliverDecision(payload)
            }

            is PeerMessage.ItemResult -> {
                if (completedTransfers.contains(payload.transferId)) {
                    VeyroLog.d(tag) { "Dropping ItemResult for completed transfer ${payload.transferId}" }
                    return
                }
                mailbox(payload.transferId).deliverItemResult(payload)
            }

            is PeerMessage.TransferProgressNotice -> {
                if (completedTransfers.contains(payload.transferId)) return
                mailbox(payload.transferId).deliverPayload(payload)
            }

            is PeerMessage.ItemBegin -> {
                if (completedTransfers.contains(payload.transferId)) return
                mailbox(payload.transferId).deliverPayload(payload)
            }

            is PeerMessage.Chunk -> {
                if (completedTransfers.contains(payload.transferId)) return
                mailbox(payload.transferId).deliverPayload(payload)
            }

            is PeerMessage.ItemEnd -> {
                if (completedTransfers.contains(payload.transferId)) return
                mailbox(payload.transferId).deliverPayload(payload)
            }

            is PeerMessage.TransferEnd -> {
                if (completedTransfers.contains(payload.transferId)) return
                mailbox(payload.transferId).deliverPayload(payload)
            }

            is PeerMessage.Cancel -> {
                mailboxes[payload.transferId]?.deliverCancel(payload)
            }

            is PeerMessage.Hello -> {
                _peerCapabilities.value = Capabilities(payload.capabilities)
                handshakeChannel.trySend(envelope)
            }

            is PeerMessage.HelloAck -> {
                _peerCapabilities.value = Capabilities(payload.capabilities)
                handshakeChannel.trySend(envelope)
            }

            is PeerMessage.PairOk -> {
                // Armed before the hand-off, so a rekey that follows at once is not missed.
                if (!encryptFrames) rekeyGate = CompletableDeferred()
                handshakeChannel.trySend(envelope)
            }

            is PeerMessage.PairHello,
            is PeerMessage.PairFail -> {
                handshakeChannel.trySend(envelope)
            }

            is PeerMessage.Pong -> {
                // Heartbeat response handled
            }

            is PeerMessage.Unpair -> {
                if (peerDeviceId == null || envelope.from == peerDeviceId) _unpaired.complete(Unit)
            }

            else -> Unit
        }
    }

    // ── Heartbeat ──────────────────────────────────────────────────────────────────────────

    /**
     * Pings only after [heartbeatIntervalMs] of inbound silence, and declares the peer gone only
     * when a ping stays unanswered for [HEARTBEAT_PONG_TIMEOUT_MS]. Sleeps until the next thing
     * that can change the answer, so an idle session wakes the CPU about twice a minute.
     */
    private suspend fun heartbeat(onDead: (() -> Unit)?) {
        var pingSentAt = 0L
        var wait = heartbeatIntervalMs()
        while (scope.isActive) {
            withTimeoutOrNull(wait.coerceAtLeast(MIN_HEARTBEAT_WAIT_MS)) { heartbeatWake.receive() }
            if (heartbeatPaused) {
                pingSentAt = 0L
                wait = heartbeatIntervalMs()
                continue
            }
            val now = System.currentTimeMillis()
            if (pingSentAt != 0L && lastInboundAt < pingSentAt) {
                val unanswered = now - pingSentAt
                if (unanswered >= HEARTBEAT_PONG_TIMEOUT_MS) {
                    // A device that left Wi-Fi range leaves the TCP connection looking open for
                    // minutes. Without this the UI shows a transfer that will never progress.
                    VeyroLog.w(tag) { "Ping unanswered for ${unanswered}ms (lastInboundAt=$lastInboundAt); closing" }
                    onDead?.invoke()
                    close("heartbeat-timeout")
                    return
                }
                wait = HEARTBEAT_PONG_TIMEOUT_MS - unanswered
                continue
            }
            pingSentAt = 0L
            val interval = heartbeatIntervalMs()
            val silent = now - lastInboundAt
            if (silent >= interval) {
                // Sent on its own coroutine: a send stuck behind a full socket to a vanished peer
                // must not stop this loop from noticing the missing answer.
                scope.launch { runCatching { send(PeerMessage.Ping(UUID.randomUUID().toString())) } }
                pingSentAt = now
                wait = HEARTBEAT_PONG_TIMEOUT_MS
            } else {
                wait = interval - silent
            }
        }
    }

    private fun heartbeatIntervalMs(): Long =
        if (mailboxes.isEmpty()) HEARTBEAT_IDLE_MS else HEARTBEAT_ACTIVE_MS
}

/** Legacy alias for backwards compatibility. */
public typealias LinkSession = PeerConnection

// ── wire encoding ──────────────────────────────────────────────────────────────────────────

internal fun SealedFrame.toWire(): LinkWireFrame {
    val n = Base64.getEncoder().encodeToString(nonce)
    val m = Base64.getEncoder().encodeToString(mac)
    return if (encrypted) {
        LinkWireFrame(
            from = fromDeviceId,
            nonce = n,
            ciphertext = Base64.getEncoder().encodeToString(body),
            mac = m,
        )
    } else {
        LinkWireFrame(
            from = fromDeviceId,
            nonce = n,
            plaintext = String(body, Charsets.UTF_8),
            mac = m,
        )
    }
}

internal fun LinkWireFrame.toSealed(): SealedFrame? {
    val nonceBytes = decodeBase64(nonce) ?: return null
    val macBytes = decodeBase64(mac) ?: return null
    return when {
        ciphertext != null -> SealedFrame(
            nonce = nonceBytes,
            body = decodeBase64(ciphertext) ?: return null,
            mac = macBytes,
            encrypted = true,
            fromDeviceId = from,
        )
        plaintext != null -> SealedFrame(
            nonce = nonceBytes,
            body = plaintext.toByteArray(Charsets.UTF_8),
            mac = macBytes,
            encrypted = false,
            fromDeviceId = from,
        )
        else -> null
    }
}

private fun decodeBase64(value: String): ByteArray? =
    runCatching { Base64.getDecoder().decode(value) }.getOrNull()

/**
 * Dedicated per-transfer mailbox that routes incoming messages by type for a specific [transferId].
 *
 * Prevents message stealing between concurrent sender waits and receiver processing loops,
 * buffers payloads without dropping frames, and provides point-to-point delivery without
 * SharedFlow replay issues.
 */
public class TransferMailbox(public val transferId: String) {
    private val decisionDeferred = CompletableDeferred<PeerMessage.TransferDecision>()
    private val itemResults = ConcurrentHashMap<Int, CompletableDeferred<PeerMessage.ItemResult>>()
    private val payloads = Channel<PeerMessage>(capacity = 64)
    private val cancelSignal = CompletableDeferred<Unit>()

    @Volatile public var isCancelled: Boolean = false
        private set

    public val payloadChannel: ReceiveChannel<PeerMessage> get() = payloads

    internal fun deliverDecision(decision: PeerMessage.TransferDecision) {
        decisionDeferred.complete(decision)
    }

    internal fun deliverItemResult(result: PeerMessage.ItemResult) {
        val deferred = itemResults.computeIfAbsent(result.index) { CompletableDeferred() }
        deferred.complete(result)
    }

    internal suspend fun deliverPayload(message: PeerMessage) {
        if (isCancelled) return
        runCatching { payloads.send(message) }
    }

    internal fun deliverCancel(cancel: PeerMessage.Cancel) {
        isCancelled = true
        cancelSignal.complete(Unit)
        decisionDeferred.complete(PeerMessage.TransferDecision(cancel.transferId, accept = false, reason = cancel.reason))
        payloads.trySend(cancel)
    }

    /**
     * True once the peer cancelled, waiting up to [timeoutMs] for its Cancel (ADR-055). A peer
     * that cancels closes its data socket and sends Cancel on the session at the same moment, and
     * the close often arrives first: a broken data stream asks this before it counts as a failure.
     */
    public suspend fun awaitCancel(timeoutMs: Long): Boolean =
        isCancelled || withTimeoutOrNull(timeoutMs) { cancelSignal.await() } != null

    // Suspend on the deferred instead of polling it: a 10 ms poll woke the CPU 100 times a second
    // for as long as the other side took to decide or to verify a file. A deferred cancelled by
    // close() still throws CancellationException from await(), as it did before.
    public suspend fun awaitDecision(timeoutMs: Long): PeerMessage.TransferDecision? =
        awaitWithin(decisionDeferred, timeoutMs)

    /** The receiver's verdict on item [index]; null on timeout or when the peer cancels (ADR-055). */
    public suspend fun awaitItemResult(index: Int, timeoutMs: Long): PeerMessage.ItemResult? {
        val result = itemResults.computeIfAbsent(index) { CompletableDeferred() }
        if (result.isCompleted) return result.await()
        return withTimeoutOrNull(timeoutMs) {
            select<PeerMessage.ItemResult?> {
                result.onAwait { it }
                cancelSignal.onAwait { null }
            }
        }
    }

    private suspend fun <T> awaitWithin(deferred: CompletableDeferred<T>, timeoutMs: Long): T? =
        // An answer that already arrived is returned even with no time left.
        if (deferred.isCompleted) deferred.await() else withTimeoutOrNull(timeoutMs) { deferred.await() }

    internal fun close() {
        if (!decisionDeferred.isCompleted) {
            decisionDeferred.cancel()
        }
        itemResults.values.forEach { if (!it.isCompleted) it.cancel() }
        payloads.close()
    }
}
