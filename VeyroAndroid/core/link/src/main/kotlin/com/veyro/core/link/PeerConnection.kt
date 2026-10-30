package com.veyro.core.link

import com.veyro.core.common.VeyroLog
import com.veyro.core.crypto.ConnectionKeySchedule
import com.veyro.core.crypto.LinkKeys
import com.veyro.core.crypto.LinkMode
import com.veyro.core.crypto.LinkRole
import com.veyro.core.model.Capabilities
import com.veyro.core.model.DeviceNames
import com.veyro.core.protocol.ProtocolConstants
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * An authenticated peer-to-peer connection over a WebSocket (link protocol v3, ADR-058).
 *
 * ## Provenance
 *
 * The structure (heartbeat, mailboxes, the pairing hand-over) is a port of
 * `services/ecosystem/transport/Session.kt` from the author's Invox code. The frame protection is
 * v3: per-connection direction keys from [LinkKeys], ChaCha20-Poly1305 with an implicit counter
 * nonce ([SessionSealer], [SessionOpener]).
 *
 *  - **Fail closed.** Any frame that fails its tag, is malformed, out of order, of a type not
 *    allowed in the current phase, or binary closes the connection and dispatches nothing
 *    ([closeCause] says why). Replays, reorders and reflections all fail the tag.
 *  - **Established.** The connection is established when a frame opens under session keys
 *    ([awaitEstablished]). Only then may the owner claim it, report it, or export keys.
 *  - **Pairing switch.** [sendPairOkAndSwitch] (responder) and [switchToSession] (initiator) move
 *    the same socket from the bootstrap keys to the session keys (04-PROTOCOL.md 4.3).
 *  - **Heartbeat with a dead-peer timeout.** A TCP connection to a device that walked out of Wi-Fi
 *    range can stay "open" for minutes. Without an application-level heartbeat a transfer hangs
 *    instead of failing.
 *
 * Uses standard `java.util.Base64` rather than `android.util.Base64`, enabling fast host JVM
 * testing without Android runtime mock dependencies.
 */
public class PeerConnection(
    private val ws: WebSocketSession,
    keys: LinkKeys,
    public val localDeviceId: String,
    private val tag: String = "PeerConnection",
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    public val enableHeartbeat: Boolean = true,
    public var remoteHost: String? = null,
    public var localHost: String? = null,
    /** FR-C3: the peer's first frame under keys it has not used yet must open within this. */
    private val firstFrameTimeoutMs: Long = ProtocolConstants.HANDSHAKE_TIMEOUT_MILLIS,
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
         * How long the read loop holds after a `pairOk` for [switchToSession] (ADR-056), and after
         * the establishing frame for [admit]. Both take milliseconds; this only bounds a caller
         * that never calls them. The admission gate fails closed.
         */
        internal const val REKEY_WAIT_MS: Long = 10_000L

        /** Serial names of every [PeerMessage] this build can decode (04-PROTOCOL.md 6). */
        internal val KNOWN_PAYLOAD_TYPES: Set<String> =
            PeerMessage.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()

        private val PAIRING_TYPES = setOf("pairHello", "pairOk", "pairFail")
    }

    /** Why the connection closed, first cause wins. Null while open. */
    public enum class CloseCause { AUTH_FAILED, PROTOCOL, PEER_CLOSED, TIMEOUT, LOCAL }

    @Volatile
    public var closeCause: CloseCause? = null
        private set

    /** The id this connection's keys are bound to. */
    public val peerDeviceId: String? = keys.peerId

    private val role: LinkRole = keys.role
    private val sendDir: Byte = if (keys.role == LinkRole.INITIATOR) FrameCrypto.DIR_I2R else FrameCrypto.DIR_R2I
    private val recvDir: Byte = if (keys.role == LinkRole.INITIATOR) FrameCrypto.DIR_R2I else FrameCrypto.DIR_I2R

    /** Receive state: replaced as a whole, read once per frame after the socket read. */
    private class RecvState(val opener: SessionOpener, val mode: LinkMode)

    /** Guarded by [sendMutex]. */
    private var currentKeys: LinkKeys = keys
    private var sealer: SessionSealer = SessionSealer(keys.sendKey, localDeviceId, sendDir)

    @Volatile
    private var recv: RecvState = RecvState(SessionOpener(keys.recvKey, keys.peerId, recvDir), keys.mode)

    @Volatile
    private var expSecret: ByteArray? = keys.expSecret

    /** True until the first frame opens under the current receive keys (pairing responder: `pairHello`). */
    @Volatile
    private var awaitingFirstFrame: Boolean = true
    private var sawPairHello = false
    private var firstFrameTimer: Job? = null

    /**
     * Set while a `pairOk` waits for [switchToSession] (ADR-056). The responder switches as it
     * sends `pairOk`, so its next frame is already under the session keys; read before the switch,
     * that frame would fail and close the connection.
     */
    @Volatile
    private var rekeyGate: CompletableDeferred<Unit>? = null

    private val _established = CompletableDeferred<Unit>()
    private val admitted = CompletableDeferred<Unit>()
    private val closedSignal = CompletableDeferred<Unit>()

    /** Completes when a frame opens under session keys (04-PROTOCOL.md 4.4). */
    public val established: Deferred<Unit> get() = _established

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
     * Set in every pairing mode (ADR-031, ADR-058): the responder sends nothing until its user
     * decides, and a ping in flight across the key switch would close the connection. Received
     * pings are still answered. Clearing it restarts the silence clock.
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

    private val _peerAnnouncedName = MutableStateFlow<String?>(null)

    /**
     * The name the peer gives itself, from its latest authenticated `hello`, `helloAck` or
     * `deviceInfo` (ADR-059), already through [DeviceNames.clean]; an invalid name is ignored.
     * Null until one arrives. Kept, not broadcast, so a watcher that subscribes after the
     * `hello` still sees it. User data: never log it.
     */
    public val peerAnnouncedName: StateFlow<String?> = _peerAnnouncedName.asStateFlow()

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

    /** True once a frame opened under session keys and the connection has not closed. */
    public val isEstablished: Boolean get() = _established.isCompleted && !isClosedFlag

    /** True when established; false when the connection closes first. */
    public suspend fun awaitEstablished(): Boolean {
        if (_established.isCompleted) return true
        select<Unit> {
            _established.onAwait { }
            closedSignal.onAwait { }
        }
        return _established.isCompleted
    }

    /** Lets the read loop go on past the establishing frame (04-PROTOCOL.md 4.4). Call after claiming the slot. */
    public fun admit() {
        admitted.complete(Unit)
    }

    /**
     * The key exporter (04-PROTOCOL.md 5.4). Only on an established session-mode connection;
     * throws [IllegalStateException] otherwise and [IllegalArgumentException] for bad arguments.
     */
    public fun exportKey(label: String, context: ByteArray, length: Int): ByteArray {
        val secret = expSecret
        check(_established.isCompleted && !isClosedFlag && secret != null && recv.mode == LinkMode.SESSION) {
            "key export needs an established session"
        }
        return ConnectionKeySchedule.export(secret, label, context, length)
    }

    /**
     * Responder side of the pairing switch: one operation under the send lock. The session
     * receive keys are installed first, so the initiator's first session frame can never be read
     * under the bootstrap keys; then `pairOk` is sealed under the bootstrap send key and written;
     * then the session send keys replace it with the counter at 0. The bootstrap keys are wiped.
     */
    public suspend fun sendPairOkAndSwitch(pairOk: PeerMessage.PairOk, newPairingSecret: ByteArray) {
        sendMutex.withLock {
            val boot = currentKeys
            check(boot.mode != LinkMode.SESSION) { "not in a pairing phase" }
            val session = ConnectionKeySchedule.afterPairing(boot, newPairingSecret)
            val oldRecv = recv
            awaitingFirstFrame = true
            recv = RecvState(SessionOpener(session.recvKey, session.peerId, recvDir), LinkMode.SESSION)
            sendLocked(pairOk)
            val oldSealer = sealer
            sealer = SessionSealer(session.sendKey, localDeviceId, sendDir)
            currentKeys = session
            expSecret = session.expSecret
            oldSealer.wipe()
            oldRecv.opener.wipe()
            boot.wipe()
        }
        armFirstFrameTimer()
        VeyroLog.i(tag) { "Switched to session keys after pairOk" }
    }

    /** Initiator side of the pairing switch, after `pairOk` arrived (ADR-056 gate holds the read loop). */
    public suspend fun switchToSession(newPairingSecret: ByteArray) {
        sendMutex.withLock {
            val boot = currentKeys
            check(boot.mode != LinkMode.SESSION) { "not in a pairing phase" }
            val session = ConnectionKeySchedule.afterPairing(boot, newPairingSecret)
            val oldRecv = recv
            val oldSealer = sealer
            awaitingFirstFrame = true
            recv = RecvState(SessionOpener(session.recvKey, session.peerId, recvDir), LinkMode.SESSION)
            sealer = SessionSealer(session.sendKey, localDeviceId, sendDir)
            currentKeys = session
            expSecret = session.expSecret
            oldSealer.wipe()
            oldRecv.opener.wipe()
            boot.wipe()
        }
        armFirstFrameTimer()
        VeyroLog.i(tag) { "Switched to session keys after pairOk" }
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
        // FR-C3: in session mode and for the pairing responder the peer's first frame is due now.
        // The pairing initiator is exempt: the responder is silent while its user decides.
        if (recv.mode == LinkMode.SESSION || role == LinkRole.RESPONDER) armFirstFrameTimer()
        var closeTrigger = "unknown"
        try {
            for (frame in ws.incoming) {
                if (frame is Frame.Close) {
                    val cr = (frame as? Frame.Close)?.readReason()
                    val code = cr?.code ?: -1
                    val known = cr?.knownReason?.name ?: "UNKNOWN"
                    closeTrigger = "remote-close-frame(code=$code, known=$known)"
                    VeyroLog.i(tag) { "Received Frame.Close from peer: code=$code ($known)" }
                    setCause(CloseCause.PEER_CLOSED)
                    close(closeTrigger)
                    break
                }
                if (frame is Frame.Ping || frame is Frame.Pong) continue
                if (frame !is Frame.Text) {
                    closeTrigger = "non-text-frame"
                    VeyroLog.w(tag) { "Binary frame on the link; closing" }
                    fail(CloseCause.PROTOCOL, closeTrigger)
                    break
                }
                if (frame.data.size > MAX_FRAME_SIZE_BYTES) {
                    closeTrigger = "oversized-frame(${frame.data.size} > $MAX_FRAME_SIZE_BYTES)"
                    VeyroLog.w(tag) { "Oversized frame received (${frame.data.size} bytes); closing session" }
                    fail(CloseCause.PROTOCOL, closeTrigger)
                    break
                }
                val establishing = handleRaw(frame.readText())
                if (establishing == null) {
                    closeTrigger = "bad-frame(${closeCause})"
                    break
                }
                // After the frame that established the connection, wait until the owner claimed
                // it, so frames that follow are not emitted before anyone can subscribe. Fails closed.
                if (establishing) {
                    if (withTimeoutOrNull(REKEY_WAIT_MS) { admitted.await() } == null) {
                        VeyroLog.w(tag) { "Connection not admitted within ${REKEY_WAIT_MS}ms of establishment; closing" }
                        closeTrigger = "not-admitted"
                        fail(CloseCause.LOCAL, closeTrigger)
                        break
                    }
                }
                // ADR-056: after a pairOk the next frame is under the session keys; read it only
                // once those keys are in place. The frames wait in the socket meanwhile.
                rekeyGate?.let { gate ->
                    if (withTimeoutOrNull(REKEY_WAIT_MS) { gate.await() } == null) {
                        VeyroLog.w(tag) { "No key switch within ${REKEY_WAIT_MS}ms of pairOk; closing" }
                        closeTrigger = "no-switch"
                        fail(CloseCause.LOCAL, closeTrigger)
                    }
                    rekeyGate = null
                }
                if (isClosedFlag) break
            }
            setCause(CloseCause.PEER_CLOSED)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                closeTrigger = "coroutine-cancellation"
                VeyroLog.i(tag) { "Read loop cancelled" }
                setCause(CloseCause.LOCAL)
            } else {
                closeTrigger = "exception: ${t.javaClass.simpleName}"
                VeyroLog.w(tag) { "Read loop terminated with ${t.javaClass.simpleName}" }
                setCause(CloseCause.PEER_CLOSED)
            }
        } finally {
            isClosedFlag = true
            heartbeatJob?.cancel()
            firstFrameTimer?.cancel()
            offerChannel.close()
            handshakeChannel.close()
            mailboxes.values.forEach { it.close() }
            mailboxes.clear()
            closedSignal.complete(Unit)
            wipeKeys()
            VeyroLog.i(tag) { "Read loop exit summary: trigger='$closeTrigger', cause=$closeCause" }
            _closed.tryEmit(closeTrigger)
        }
    }

    public suspend fun send(message: PeerMessage) {
        sendMutex.withLock { sendLocked(message) }
    }

    /** Call under [sendMutex]: allocates the counter, seals and writes as one step (FR-F4). */
    private suspend fun sendLocked(message: PeerMessage) {
        if (isClosedFlag) throw java.io.IOException("connection closed")
        val counter = sealer.nextCounter
        lastOutboundAt = System.currentTimeMillis()
        val envelope = LinkEnvelope(
            msgId = UUID.randomUUID().toString(),
            from = localDeviceId,
            seq = counter + 1,
            ts = lastOutboundAt,
            payload = message,
        )
        val payloadJson = PeerMessageJson.codec.encodeToString(LinkEnvelope.serializer(), envelope)
        val wireText = try {
            sealer.seal(payloadJson)
        } catch (e: com.veyro.core.protocol.AeadStreamException) {
            VeyroLog.w(tag) { "Send counter exhausted; closing" }
            scope.launch { fail(CloseCause.LOCAL, "counter-exhausted") }
            throw e
        }

        // Sampled logging for outbound sends (first chunk, last chunk, or every 20 chunks)
        if (message is PeerMessage.Chunk) {
            if (message.chunkIndex == 0 || message.chunkIndex % 20 == 0) {
                val qSize = outboundQueueBytes() ?: -1L
                VeyroLog.i(tag) {
                    "[diagnostics:sender-wire] transfer=${message.transferId} chunk #${message.chunkIndex} (payloadBytes=${message.dataB64.length}, wireBytes=${wireText.length}, inFlightQueue=${qSize}B)"
                }
            }
        } else if (message !is PeerMessage.Ping && message !is PeerMessage.Pong) {
            VeyroLog.i(tag) {
                "[diagnostics:sender-ctrl] send ${message.javaClass.simpleName} (wireBytes=${wireText.length})"
            }
        }

        ws.send(wireText)
    }

    public suspend fun close(reason: String = "normal") {
        if (isClosedFlag) return
        isClosedFlag = true
        setCause(CloseCause.LOCAL)
        VeyroLog.i(tag) { "Session closing initiated: reason='$reason'" }
        runCatching { ws.close(CloseReason(CloseReason.Codes.NORMAL, reason)) }
        scope.cancel()
        offerChannel.close()
        handshakeChannel.close()
        mailboxes.values.forEach { it.close() }
        mailboxes.clear()
        closedSignal.complete(Unit)
        _closed.tryEmit(reason)
    }

    private fun setCause(cause: CloseCause) {
        if (closeCause == null) closeCause = cause
    }

    private suspend fun fail(cause: CloseCause, reason: String) {
        setCause(cause)
        close(reason)
    }

    private fun armFirstFrameTimer() {
        firstFrameTimer?.cancel()
        firstFrameTimer = scope.launch {
            delay(firstFrameTimeoutMs)
            if (awaitingFirstFrame && !isClosedFlag) {
                VeyroLog.w(tag) { "No authenticated frame within ${firstFrameTimeoutMs}ms; closing" }
                fail(CloseCause.TIMEOUT, "first-frame-timeout")
            }
        }
    }

    /** Best effort: the keys of a closed connection are of no further use. */
    private fun wipeKeys() {
        expSecret = null
        runCatching { recv.opener.wipe() }
        runCatching { sealer.wipe() }
        runCatching { currentKeys.wipe() }
    }

    // ── Receive ────────────────────────────────────────────────────────────────────────────

    /**
     * Opens and dispatches one frame. Returns null when the frame was rejected (the connection is
     * then closed), true when it established the connection, false otherwise.
     */
    private suspend fun handleRaw(text: String): Boolean? {
        lastInboundAt = System.currentTimeMillis()
        val state = recv
        val counter = state.opener.nextCounter

        val json = try {
            state.opener.open(text)
        } catch (e: SessionFrameException) {
            VeyroLog.w(tag) { if (e.authFailed) "Frame failed authentication; closing" else "Malformed frame; closing" }
            fail(if (e.authFailed) CloseCause.AUTH_FAILED else CloseCause.PROTOCOL, if (e.authFailed) "auth-failed" else "malformed-frame")
            return null
        }
        awaitingFirstFrame = false
        firstFrameTimer?.cancel()

        val obj = try {
            PeerMessageJson.codec.parseToJsonElement(json).jsonObject
        } catch (t: Throwable) {
            return protocolFault("malformed envelope")
        }
        val v = (obj["v"] as? JsonPrimitive)?.intOrNull
        val from = (obj["from"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val seq = (obj["seq"] as? JsonPrimitive)?.longOrNull
        if (v != LINK_PROTOCOL_VERSION || from != peerDeviceId || seq != counter + 1) {
            return protocolFault("envelope header mismatch")
        }
        val type = ((obj["payload"] as? JsonObject)?.get("t") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return protocolFault("envelope without a payload type")

        val establishing = state.mode == LinkMode.SESSION && !_established.isCompleted

        if (type !in KNOWN_PAYLOAD_TYPES) {
            if (establishing) _established.complete(Unit)
            // Authenticated but from a newer build: consumed (its counter counted) and skipped.
            if (type.length in 1..32 && type.all { it.isLetterOrDigit() && it.code < 0x80 }) {
                VeyroLog.i(tag) { "Skipping unknown message type $type" }
            }
            return establishing
        }
        if (!allowed(type, state.mode)) return protocolFault("message type not allowed in this phase")

        val envelope = try {
            PeerMessageJson.codec.decodeFromJsonElement(LinkEnvelope.serializer(), obj)
        } catch (t: Throwable) {
            return protocolFault("known message failed to decode")
        }
        if (establishing) _established.complete(Unit)
        dispatch(envelope, state.mode)
        return establishing
    }

    private suspend fun protocolFault(what: String): Boolean? {
        VeyroLog.w(tag) { "Protocol fault ($what); closing" }
        fail(CloseCause.PROTOCOL, "protocol-fault")
        return null
    }

    /** The per-phase allowlist (04-PROTOCOL.md 5.3). */
    private fun allowed(type: String, mode: LinkMode): Boolean {
        if (mode == LinkMode.SESSION) return type !in PAIRING_TYPES
        return when (role) {
            LinkRole.RESPONDER -> when (type) {
                "pairHello" -> if (sawPairHello) false else true.also { sawPairHello = true }
                "pairFail", "ping", "pong" -> sawPairHello
                else -> false
            }
            LinkRole.INITIATOR -> type == "pairOk" || type == "pairFail" || type == "ping" || type == "pong"
        }
    }

    private suspend fun dispatch(envelope: LinkEnvelope, mode: LinkMode) {
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
                    "[diagnostics:receiver-link] transfer=${chunk.transferId} chunk #${chunk.chunkIndex} accepted (totalAcceptedPayloadFrames=$count)"
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
                DeviceNames.clean(payload.deviceName)?.let { _peerAnnouncedName.value = it }
                handshakeChannel.trySend(envelope)
            }

            is PeerMessage.HelloAck -> {
                _peerCapabilities.value = Capabilities(payload.capabilities)
                DeviceNames.clean(payload.deviceName)?.let { _peerAnnouncedName.value = it }
                handshakeChannel.trySend(envelope)
            }

            is PeerMessage.DeviceInfo -> {
                DeviceNames.clean(payload.name)?.let { _peerAnnouncedName.value = it }
            }

            is PeerMessage.PairOk -> {
                // Armed before the hand-off, so a switch that follows at once is not missed.
                if (mode != LinkMode.SESSION) rekeyGate = CompletableDeferred()
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
                // Session mode only (allowlist): the sender is the authenticated peer.
                _unpaired.complete(Unit)
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
                    VeyroLog.w(tag) { "Ping unanswered for ${unanswered}ms; closing" }
                    onDead?.invoke()
                    fail(CloseCause.TIMEOUT, "heartbeat-timeout")
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
