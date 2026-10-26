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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        /** How often a ping is sent while the session is idle. */
        public const val HEARTBEAT_INTERVAL_MS: Long = 5_000L

        /** Silence beyond this and the peer is considered gone. */
        public const val HEARTBEAT_DEAD_MS: Long = 20_000L

        /** Maximum allowed size for a single frame (24 MB) to prevent memory exhaustion DoS. */
        public const val MAX_FRAME_SIZE_BYTES: Long = 24L * 1024 * 1024
    }

    @Volatile
    private var key: SecretKey = initialKey

    @Volatile
    private var encryptFrames: Boolean = initialEncrypt

    private val nonceCache = NonceCache()
    private val seqOut = AtomicLong(0)
    private val lastSeqByPeer = HashMap<String, Long>()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var heartbeatJob: Job? = null

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
        mailboxes.computeIfAbsent(transferId) { TransferMailbox(transferId) }

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

    public suspend fun send(message: LinkMessage): Unit = sendInternal(message, encryptFrames)

    /**
     * Sends one frame sealed regardless of session mode.
     *
     * Exists for `PairOk`, which carries the session secret while the conversation is still in
     * plaintext pairing mode. The secret must not cross the network in the clear, and the bootstrap
     * key is available to seal it with.
     */
    public suspend fun sendSealed(message: LinkMessage): Unit = sendInternal(message, encrypt = true)

    private suspend fun sendInternal(message: LinkMessage, encrypt: Boolean) {
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
            val payloadJson = LinkJson.codec.encodeToString(LinkEnvelope.serializer(), envelope)
            val sealed = FrameCrypto.seal(payloadJson, key, encrypt, localDeviceId)
            val wireText = LinkJson.codec.encodeToString(LinkWireFrame.serializer(), sealed.toWire())

            // Sampled logging for outbound sends (first chunk, last chunk, or every 20 chunks)
            if (message is LinkMessage.Chunk) {
                if (message.chunkIndex == 0 || message.chunkIndex % 20 == 0) {
                    val qSize = outboundQueueBytes() ?: -1L
                    VeyroLog.i(tag) {
                        "[diagnostics:sender-wire] transfer=${message.transferId} chunk #${message.chunkIndex} (seq=$seq, payloadBytes=${message.dataB64.length}, wireBytes=${wireText.length}, inFlightQueue=${qSize}B)"
                    }
                }
            } else if (message !is LinkMessage.Ping && message !is LinkMessage.Pong) {
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
            LinkJson.codec.decodeFromString(LinkWireFrame.serializer(), text)
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
            LinkJson.codec.decodeFromString(LinkEnvelope.serializer(), json)
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

        if (envelope.payload is LinkMessage.Ping) {
            scope.launch {
                runCatching {
                    send(LinkMessage.Pong((envelope.payload as LinkMessage.Ping).nonce))
                }
            }
            return
        }

        // Sampled logging for inbound chunk receives
        if (envelope.payload is LinkMessage.Chunk) {
            val chunk = envelope.payload as LinkMessage.Chunk
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
            is LinkMessage.TransferOffer -> {
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

            is LinkMessage.TransferDecision -> {
                if (completedTransfers.contains(payload.transferId)) {
                    VeyroLog.d(tag) { "Dropping TransferDecision for completed transfer ${payload.transferId}" }
                    return
                }
                mailbox(payload.transferId).deliverDecision(payload)
            }

            is LinkMessage.ItemResult -> {
                if (completedTransfers.contains(payload.transferId)) {
                    VeyroLog.d(tag) { "Dropping ItemResult for completed transfer ${payload.transferId}" }
                    return
                }
                mailbox(payload.transferId).deliverItemResult(payload)
            }

            is LinkMessage.TransferProgressNotice -> {
                if (completedTransfers.contains(payload.transferId)) return
                mailbox(payload.transferId).deliverPayload(payload)
            }

            is LinkMessage.ItemBegin -> {
                if (completedTransfers.contains(payload.transferId)) return
                mailbox(payload.transferId).deliverPayload(payload)
            }

            is LinkMessage.Chunk -> {
                if (completedTransfers.contains(payload.transferId)) return
                mailbox(payload.transferId).deliverPayload(payload)
            }

            is LinkMessage.ItemEnd -> {
                if (completedTransfers.contains(payload.transferId)) return
                mailbox(payload.transferId).deliverPayload(payload)
            }

            is LinkMessage.TransferEnd -> {
                if (completedTransfers.contains(payload.transferId)) return
                mailbox(payload.transferId).deliverPayload(payload)
            }

            is LinkMessage.Cancel -> {
                mailboxes[payload.transferId]?.deliverCancel(payload)
            }

            is LinkMessage.Hello -> {
                _peerCapabilities.value = Capabilities(payload.capabilities)
                handshakeChannel.trySend(envelope)
            }

            is LinkMessage.HelloAck -> {
                _peerCapabilities.value = Capabilities(payload.capabilities)
                handshakeChannel.trySend(envelope)
            }

            is LinkMessage.PairHello,
            is LinkMessage.PairOk,
            is LinkMessage.PairFail -> {
                handshakeChannel.trySend(envelope)
            }

            is LinkMessage.Pong -> {
                // Heartbeat response handled
            }

            else -> Unit
        }
    }

    // ── Heartbeat ──────────────────────────────────────────────────────────────────────────

    private suspend fun heartbeat(onDead: (() -> Unit)?) {
        while (scope.isActive) {
            delay(HEARTBEAT_INTERVAL_MS)
            if (heartbeatPaused) continue
            val now = System.currentTimeMillis()
            val silent = now - lastInboundAt
            if (silent > HEARTBEAT_DEAD_MS) {
                // A device that left Wi-Fi range leaves the TCP connection looking open for
                // minutes. Without this the UI shows a transfer that will never progress.
                VeyroLog.w(tag) { "Peer silent for ${silent}ms (lastInboundAt=$lastInboundAt, now=$now); closing" }
                onDead?.invoke()
                close("heartbeat-timeout")
                return
            }
            // Only ping if outbound has been idle; if active transfer is streaming frames,
            // the connection is actively proven alive and ping avoids interleaving.
            if (now - lastOutboundAt >= HEARTBEAT_INTERVAL_MS) {
                runCatching { send(LinkMessage.Ping(UUID.randomUUID().toString())) }
            }
        }
    }
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
    private val decisionDeferred = CompletableDeferred<LinkMessage.TransferDecision>()
    private val itemResults = ConcurrentHashMap<Int, CompletableDeferred<LinkMessage.ItemResult>>()
    private val payloads = Channel<LinkMessage>(capacity = 64)

    @Volatile public var isCancelled: Boolean = false
        private set

    public val payloadChannel: ReceiveChannel<LinkMessage> get() = payloads

    internal fun deliverDecision(decision: LinkMessage.TransferDecision) {
        decisionDeferred.complete(decision)
    }

    internal fun deliverItemResult(result: LinkMessage.ItemResult) {
        val deferred = itemResults.computeIfAbsent(result.index) { CompletableDeferred() }
        deferred.complete(result)
    }

    internal suspend fun deliverPayload(message: LinkMessage) {
        if (isCancelled) return
        runCatching { payloads.send(message) }
    }

    internal fun deliverCancel(cancel: LinkMessage.Cancel) {
        isCancelled = true
        decisionDeferred.complete(LinkMessage.TransferDecision(cancel.transferId, accept = false, reason = cancel.reason))
        payloads.trySend(cancel)
    }

    public suspend fun awaitDecision(timeoutMs: Long): LinkMessage.TransferDecision? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!decisionDeferred.isCompleted && System.currentTimeMillis() < deadline) {
            if (decisionDeferred.isCompleted) break
            delay(10L)
        }
        return if (decisionDeferred.isCompleted) decisionDeferred.await() else null
    }

    public suspend fun awaitItemResult(index: Int, timeoutMs: Long): LinkMessage.ItemResult? {
        val deferred = itemResults.computeIfAbsent(index) { CompletableDeferred() }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
            if (deferred.isCompleted) break
            delay(10L)
        }
        return if (deferred.isCompleted) deferred.await() else null
    }

    internal fun close() {
        if (!decisionDeferred.isCompleted) {
            decisionDeferred.cancel()
        }
        itemResults.values.forEach { if (!it.isCompleted) it.cancel() }
        payloads.close()
    }
}
