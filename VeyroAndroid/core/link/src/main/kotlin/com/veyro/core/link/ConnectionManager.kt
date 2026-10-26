package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.crypto.PairConfirm
import com.veyro.core.model.Capabilities
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.model.Platform
import com.veyro.core.model.TrustedPeer
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.UUID

/**
 * Orchestrates device-to-device connections: listen, dial, branch on pairing state, run the
 * pairing exchange, and hand back an established [PeerConnection].
 *
 * ## Provenance
 *
 * The flow is ported from `EcosystemManager.handleInbound` / `runOutboundFlow` in the author's
 * Invox code. The branch structure is the part that matters and is kept intact, because it is what
 * makes two devices actually connect:
 *
 * ```
 *   dial  ──► send preamble {id, name}
 *             │
 *             ▼  read PreambleAck
 *        ┌────┴──────────────┬──────────────┐
 *     "session"          "pairing"       "busy"/"refused"
 *        │                   │                │
 *   key = stored        user types PIN      give up
 *   secret, encrypt     ──► PairHello
 *                       ◄── PairOk (sealed)
 *                       rekey to the secret
 * ```
 *
 * The preamble exists because the receiver cannot choose a key until it knows who is calling, and
 * it cannot know that from an encrypted frame it has no key for. It is explicitly **unauthenticated
 * and non-authoritative** - it only selects which gate to run.
 *
 * ## Confirm pairing (ADR-031)
 *
 * Pairing from the nearby list sends `pair = "confirm"`. The receiver answers `pairing-confirm`
 * with its key and a commitment, both sides exchange nonces, and each derives the same 6-digit
 * code and a bootstrap key from X25519 between the static identity keys (04-PROTOCOL.md 4.3).
 * The user compares the code and accepts on the receiver; nothing is typed. The legacy
 * `pairing` branch above (PIN) remains for tests and for `pair` absent.
 *
 * ## Who mints the secret
 *
 * The **target** mints the 32-byte session secret and sends it sealed under the bootstrap key.
 * Not the initiator: the target is the device whose user accepted (or read the PIN aloud), so it
 * is the side that has already demonstrated physical presence.
 */
public class ConnectionManager(
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
    private val localDeviceId: suspend () -> String,
    private val localDeviceName: suspend () -> String,
    private val trustedPeer: suspend (String) -> TrustedPeer?,
    private val saveTrustedPeer: suspend (TrustedPeer) -> Unit,
    private val client: ConnectionClient = ConnectionClient(),
    private val localPublicKey: suspend () -> ByteArray = { ByteArray(32) },
    /**
     * `HKDF-SHA256(X25519(identityPrivate, peerPublicKey), salt, info, 32)`; in production
     * `IdentityManager.deriveSharedKey`, so the private key and the raw DH output stay inside the
     * identity. Needed only for confirm pairing.
     */
    private val deriveSharedKey: suspend (peerPublicKey: ByteArray, salt: ByteArray, info: ByteArray) -> ByteArray =
        { _, _, _ -> throw IllegalStateException("confirm pairing is not configured") },
) {

    /**
     * Invoked once per established connection, on both the inbound and outbound side.
     *
     * This is how transfer plugs in without `ConnectionManager` depending on the transfer runner: the
     * coordinator sets this to its receive loop, which runs on every authenticated session whoever
     * dialled, because the PC sends files over the phone's own session too. Outgoing sends drive
     * themselves through per-transfer mailboxes, so both coexist. Set once, after construction,
     * before any connection.
     */
    public var sessionDriver: (suspend (connection: PeerConnection, inbound: Boolean) -> Unit)? = null

    private companion object {
        const val TAG = "ConnectionManager"
        const val PREAMBLE_TIMEOUT_MS = 10_000L
        const val CONSENT_TIMEOUT_MS = 30_000L
        const val ACK_TIMEOUT_MS = 35_000L
        const val PAIR_EXCHANGE_TIMEOUT_MS = 60_000L
        const val MAX_PREAMBLE_BYTES = 64 * 1024

        /** The user's time to compare and accept, on both sides (04-PROTOCOL.md 4.3). */
        const val CONFIRM_TIMEOUT_MS = 120_000L

        /** The initiator waits a little longer, so the receiver's own timeout is what it reports. */
        const val CONFIRM_GRACE_MS = 5_000L

        /** `pairHello` follows the nonces immediately; this only bounds a stalled peer. */
        const val CONFIRM_HELLO_TIMEOUT_MS = 30_000L
    }

    /** Overridable by tests; production uses [CONFIRM_TIMEOUT_MS]. */
    internal var confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS

    /** What the pairing UI needs to render. */
    public data class PendingInbound(
        val peerDeviceId: String,
        val peerDeviceName: String,
        /** Legacy flow: `"..."` until the user consents and a PIN is minted. Confirm flow: the code. */
        val pin: String,
        val expiresAt: Long,
        /** Six digits when this is a confirm pairing (ADR-031): show it with Accept / Decline. */
        val confirmCode: String? = null,
    ) {
        override fun toString(): String = "PendingInbound(peerDeviceId=$peerDeviceId)"
    }

    public data class PendingOutbound(
        val peerDeviceId: String,
        val peerDeviceName: String,
        /** Six digits once the confirm exchange computed them; the user compares with the PC. */
        val confirmCode: String? = null,
    ) {
        override fun toString(): String = "PendingOutbound(peerDeviceId=$peerDeviceId)"
    }

    public sealed interface ConnectionEvent {
        public data class Connected(val peerDeviceId: String, val peerName: String) : ConnectionEvent
        public data class Paired(val peer: TrustedPeer) : ConnectionEvent
        public data class Failed(val reason: String) : ConnectionEvent
        public data object Disconnected : ConnectionEvent
    }

    /** Legacy alias for backwards compatibility. */
    public typealias LinkEvent = ConnectionEvent

    /**
     * Progress of a pairing started from a scanned QR code ([pairWithCode], ADR-030).
     *
     * Emitted in order: [Connecting], then [Securing] once the PC answered in pairing mode, then
     * exactly one terminal value ([Paired], [AlreadyPaired] or [Failed]).
     */
    public sealed interface CodePairingProgress {
        public data object Connecting : CodePairingProgress
        public data object Securing : CodePairingProgress

        /**
         * Confirm pairing only ([pairWithConfirm]): both screens now show [code]; the user accepts
         * on the other device. Ends in a terminal value, or [Failed] with `CANCELLED`.
         */
        public data class AwaitingConfirmation(val code: String) : CodePairingProgress {
            override fun toString(): String = "AwaitingConfirmation"
        }

        public data class Paired(val peer: TrustedPeer) : CodePairingProgress

        /** The PC already trusts this phone and a normal session opened instead. */
        public data class AlreadyPaired(val peerName: String) : CodePairingProgress
        public data class Failed(val failure: CodePairingFailure, val message: String) : CodePairingProgress
    }

    public enum class CodePairingFailure {
        /** No endpoint accepted a connection, or the peer never answered the preamble. */
        UNREACHABLE,

        /** The PC refused or is busy (pairing window closed, another session). */
        NOT_ACCEPTING,

        /** The PIN bootstrap failed: wrong or stale code, or the PC rejected it. */
        CODE_REJECTED,

        /**
         * The answering key does not hash to the id that was scanned or dialled, or (confirm
         * pairing) the PC's nonce does not match its commitment. Nothing was stored.
         */
        IDENTITY_MISMATCH,

        /** Confirm pairing: the user tapped Decline on the other device. */
        DECLINED,

        /** Confirm pairing: nobody accepted within the 120 s window. */
        TIMEOUT,

        /** Confirm pairing: cancelled on this device. */
        CANCELLED,

        /** Anything else (local persistence failure, lost pairing, protocol error). */
        OTHER,
    }

    /** What one dial attempt ended in. Internal so `connect` keeps its Boolean contract. */
    private sealed interface DialOutcome {
        val isSuccess: Boolean

        data object Session : DialOutcome {
            override val isSuccess: Boolean = true
        }

        data class Paired(val peer: TrustedPeer) : DialOutcome {
            override val isSuccess: Boolean = true
        }

        data class Failed(val failure: CodePairingFailure) : DialOutcome {
            override val isSuccess: Boolean = false
        }
    }

    private val pairingWindows = PairingRegistry()

    /**
     * One dial at a time per peer, so a simultaneous redial of the same PC cannot open two
     * sessions, while dials to different PCs run in parallel (multiple PCs).
     */
    private val dialLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /** Pairing is still one at a time: the pending prompts and cancel handles are single. */
    private val pairingLock = Mutex()

    private fun dialLock(peerDeviceId: String): Mutex = dialLocks.getOrPut(peerDeviceId) { Mutex() }

    /**
     * Guards [_connections], [sideSessions], [selectedPeerId] and [_activeConnection] in
     * [claimSlot] / [releaseSlot] / [selectPeer].
     */
    private val slotLock = Any()

    /**
     * Live sessions with an already-connected peer that run alongside its entry in [connections],
     * not instead of it (a PC's parallel transfer connection, ADR-035).
     */
    private val sideSessions = ArrayList<PeerConnection>()

    /** The PC picked on Home; [activeConnection] follows it while it is connected. */
    private var selectedPeerId: String? = null

    private var server: ConnectionServer? = null

    private val _pendingInbound = MutableStateFlow<PendingInbound?>(null)
    public val pendingInbound: StateFlow<PendingInbound?> = _pendingInbound.asStateFlow()

    private val _pendingOutbound = MutableStateFlow<PendingOutbound?>(null)
    public val pendingOutbound: StateFlow<PendingOutbound?> = _pendingOutbound.asStateFlow()

    private val _events = MutableStateFlow<ConnectionEvent?>(null)
    public val events: StateFlow<ConnectionEvent?> = _events.asStateFlow()

    private val _connections = MutableStateFlow<Map<String, PeerConnection>>(emptyMap())

    /**
     * One live companion session per paired peer, by peer device id, oldest first. Every
     * companion feature fans out over this; a second PC connecting never displaces the first.
     */
    public val connections: StateFlow<Map<String, PeerConnection>> = _connections.asStateFlow()

    private val _activeConnection = MutableStateFlow<PeerConnection?>(null)

    /**
     * The selected connection, for callers that act on one PC: the PC picked with [selectPeer]
     * while it is connected, else the most recently connected one. Derived from [connections].
     */
    public val activeConnection: StateFlow<PeerConnection?> = _activeConnection.asStateFlow()

    /** Home's PC switcher. Null selects the most recently connected PC. */
    public fun selectPeer(peerDeviceId: String?) {
        synchronized(slotLock) {
            selectedPeerId = peerDeviceId
            recomputeActive()
        }
    }

    /** The live session with [peerDeviceId], or null. */
    public fun connectionFor(peerDeviceId: String): PeerConnection? =
        _connections.value[peerDeviceId]?.takeIf { it.isConnected }

    /** Call under [slotLock]. */
    private fun recomputeActive() {
        val map = _connections.value
        _activeConnection.value = selectedPeerId?.let { map[it] } ?: map.values.lastOrNull()
    }

    /** Test seam: registers an already-started session as if it had been established. */
    internal fun adoptForTest(session: PeerConnection): Boolean = claimSlot(session)

    /** Legacy alias. */
    public val activeSession: StateFlow<PeerConnection?> get() = activeConnection

    /** Resolved when the local user answers an inbound pairing prompt. */
    private var inboundConsent: CompletableDeferred<Boolean>? = null

    /** Resolved when the local user types the PIN for an outbound pairing (legacy, tests only). */
    private var outboundPin: CompletableDeferred<String?>? = null

    /** Completed by [cancelOutboundPairing] to abort a confirm pairing this device started. */
    @Volatile private var outboundConfirmCancel: CompletableDeferred<Unit>? = null

    /** Holds active inbound WebSocket session to enable clean cancellation and replacement. */
    @Volatile private var pendingInboundWs: DefaultWebSocketSession? = null

    public val listeningPort: Int get() = server?.port ?: 0

    /** True while a PIN window is open, so discovery can advertise accordingly. */
    public fun isPairingWindowOpen(): Boolean =
        pairingWindows.hasOpenWindow() || _pendingInbound.value != null

    // ── Listening ──────────────────────────────────────────────────────────────────────────

    public fun startListening(): Int {
        server?.let { if (it.isRunning) return it.port }
        val created = ConnectionServer(onConnection = ::handleInbound)
        val port = created.start()
        server = created
        return port
    }

    public fun stopListening() {
        server?.stop()
        server = null
        pairingWindows.closeAll()
    }

    // ── Inbound ────────────────────────────────────────────────────────────────────────────

    /**
     * Handles one inbound connection for its whole lifetime.
     *
     * **This must not return early.** Ktor closes the socket the moment it does, so returning after
     * setting something up drops the peer - the symptom being a connection that appears to succeed
     * and then vanishes.
     */
    internal suspend fun handleInbound(
        ws: DefaultWebSocketSession,
        remoteHost: String? = null,
        localHost: String? = null,
    ) {
        val connId = "in-" + UUID.randomUUID().toString().take(8)
        VeyroLog.i(TAG) { "[$connId] Inbound TCP connection accepted (remote=$remoteHost, local=$localHost), WebSocket upgrade completed" }

        val preamble = readPreamble(ws, connId) ?: return
        val selfId = localDeviceId()

        if (preamble.id == selfId) {
            // Our own beacon reflected back, or a loopback. Not an error.
            VeyroLog.d(TAG) { "[$connId] Loopback connection detected from self ($selfId); closing" }
            ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "self"))
            return
        }

        // A live session with this same peer. It is kept, not superseded: the PC may dial a second
        // connection just for a transfer, and closing the first would take every companion
        // feature down with it (ADR-035). Decided below, once trust is known. Sessions with other
        // peers are irrelevant here: each paired PC keeps its own session (multiple PCs).
        val liveSamePeer: PeerConnection? = connectionFor(preamble.id)

        // Guard against concurrent inbound + outbound dial collision with the same peer:
        // Use deterministic tie-breaker on device IDs.
        val pendingOut = _pendingOutbound.value
        if (pendingOut != null && pendingOut.peerDeviceId == preamble.id) {
            if (selfId > preamble.id) {
                VeyroLog.i(TAG) { "[$connId] Simultaneous dial detected with ${preamble.id}. Local ID ($selfId) > peer ID (${preamble.id}): rejecting inbound to let outbound win." }
                sendAck(ws, LinkPreambleAck(LinkPreambleAck.MODE_BUSY, "simultaneous-dial-yield"), connId)
                ws.close(CloseReason(CloseReason.Codes.NORMAL, "busy"))
                return
            } else {
                VeyroLog.i(TAG) { "[$connId] Simultaneous dial detected with ${preamble.id}. Local ID ($selfId) <= peer ID (${preamble.id}): yielding outbound to accept inbound." }
                outboundPin?.complete(null)
                outboundConfirmCancel?.complete(Unit)
                _pendingOutbound.value = null
            }
        }

        // A trusted peer opens its session even while another device's pairing prompt is up.
        val known = trustedPeer(preamble.id)?.takeUnless { it.revoked }
        if (known != null) {
            if (liveSamePeer != null) {
                VeyroLog.i(TAG) { "[$connId] Second session from already-connected peer (${preamble.id}); keeping the existing one and running this alongside it" }
            }
            sendAck(ws, LinkPreambleAck(LinkPreambleAck.MODE_SESSION), connId)
            runConnection(ws, known, encrypt = true, inbound = true, connId = connId, remoteHost = remoteHost, localHost = localHost)
            return
        }

        // Check if a pairing request is already awaiting user consent
        val currentPending = _pendingInbound.value
        if (currentPending != null) {
            if (currentPending.peerDeviceId == preamble.id) {
                VeyroLog.i(TAG) { "[$connId] Inbound pairing from same peer (${preamble.id}) retried; superseding previous pending request" }
                val oldWs = pendingInboundWs
                pendingInboundWs = ws
                inboundConsent?.complete(false)
                try {
                    oldWs?.close(CloseReason(CloseReason.Codes.NORMAL, "superseded"))
                } catch (_: Throwable) {}
            } else {
                VeyroLog.w(TAG) { "[$connId] Inbound pairing rejected: another pairing request is already awaiting user consent" }
                sendAck(ws, LinkPreambleAck(LinkPreambleAck.MODE_BUSY, "pairing-in-progress"), connId)
                ws.close(CloseReason(CloseReason.Codes.NORMAL, "busy"))
                return
            }
        }

        val stale = liveSamePeer
        if (stale != null) {
            // No longer trusted (revoked mid-session): the old session has no standing either.
            VeyroLog.i(TAG) { "[$connId] Inbound connection from untrusted peer with a live session (${preamble.id}); closing the old session" }
            scope.launch { stale.close("superseded-by-inbound") }
        }

        when (preamble.pair) {
            LinkPreamble.PAIR_CONFIRM -> {
                runInboundConfirm(ws, preamble, connId, remoteHost, localHost)
                return
            }
            LinkPreamble.PAIR_QR -> {
                // A phone shows no QR code, so there is never a window to pair through.
                VeyroLog.i(TAG) { "[$connId] QR pairing requested but this device has no pairing window" }
                sendAck(ws, LinkPreambleAck(LinkPreambleAck.MODE_REFUSED, "no-pairing-window"), connId)
                ws.close(CloseReason(CloseReason.Codes.NORMAL, "refused"))
                return
            }
        }

        // Unknown peer. Ask the local user before minting anything: a PIN prompt that appears
        // without consent would let any device on the network pop a dialog on this one.
        val consent = CompletableDeferred<Boolean>()
        inboundConsent = consent
        pendingInboundWs = ws
        _pendingInbound.value = PendingInbound(
            peerDeviceId = preamble.id,
            peerDeviceName = preamble.name,
            pin = "...",
            expiresAt = System.currentTimeMillis() + CONSENT_TIMEOUT_MS,
        )

        val approved = try {
            coroutineScope {
                val disconnectWatcher = launch {
                    try {
                        ws.incoming.receive()
                        // If any frame arrives or closes early, not waiting silently
                        consent.complete(false)
                    } catch (_: ClosedReceiveChannelException) {
                        VeyroLog.i(TAG) { "[$connId] Peer disconnected while awaiting consent" }
                        consent.complete(false)
                    } catch (_: CancellationException) {
                        // Normal cancellation when user responds or scope exits
                    } catch (_: Throwable) {
                        consent.complete(false)
                    }
                }
                val res = withTimeoutOrNull(CONSENT_TIMEOUT_MS) { consent.await() } ?: false
                disconnectWatcher.cancel()
                res
            }
        } catch (_: Throwable) {
            false
        } finally {
            if (inboundConsent === consent) {
                inboundConsent = null
            }
        }

        if (!approved) {
            if (pendingInboundWs === ws) {
                _pendingInbound.value = null
                pendingInboundWs = null
            }
            VeyroLog.i(TAG) { "[$connId] Inbound pairing declined by user or timed out" }
            try {
                sendAck(ws, LinkPreambleAck(LinkPreambleAck.MODE_REFUSED, "declined"), connId)
                ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "declined"))
            } catch (_: Throwable) {}
            return
        }

        val window = pairingWindows.openInbound(preamble.id, preamble.name, selfId)
        _pendingInbound.value = PendingInbound(
            peerDeviceId = preamble.id,
            peerDeviceName = preamble.name,
            pin = window.pin,
            expiresAt = window.expiresAt,
        )

        sendAck(ws, LinkPreambleAck(LinkPreambleAck.MODE_PAIRING), connId)
        try {
            runInboundPairing(ws, preamble, window, connId, remoteHost, localHost)
        } finally {
            if (pendingInboundWs === ws) {
                _pendingInbound.value = null
                pendingInboundWs = null
            }
        }
    }

    private suspend fun runInboundPairing(
        ws: DefaultWebSocketSession,
        preamble: LinkPreamble,
        window: PairingRegistry.Window,
        connId: String,
        remoteHost: String? = null,
        localHost: String? = null,
    ) {
        val selfId = localDeviceId()
        val session = PeerConnection(
            ws = ws,
            initialKey = window.bootstrapKey,
            initialEncrypt = false,
            localDeviceId = selfId,
            tag = "Connection(pair-in-$connId)",
            dispatcher = dispatchers.io,
            peerDeviceId = preamble.id,
            remoteHost = remoteHost,
            localHost = localHost,
        )
        val readJob = session.start()

        VeyroLog.i(TAG) { "[$connId] Inbound pairing exchange started with ${preamble.name}, awaiting PairHello" }
        val envelope = session.receiveHandshake(PAIR_EXCHANGE_TIMEOUT_MS)
        val hello = envelope?.payload as? LinkMessage.PairHello
        if (hello == null) {
            // Either the PIN was wrong - every frame then fails its MAC and never reaches us - or
            // the peer gave up. Indistinguishable from here, deliberately.
            VeyroLog.i(TAG) { "[$connId] Handshake failed: No PairHello arrived; PIN mismatch or peer timed out" }
            pairingWindows.recordAttempt(preamble.id)
            _events.value = ConnectionEvent.Failed("The PIN did not match")
            session.close("pair-timeout")
            return
        }

        val peerDeviceId = DeviceId.parseOrNull(hello.deviceId) ?: run {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: invalid peer device ID in PairHello" }
            session.close("bad-id")
            _events.value = ConnectionEvent.Failed("Invalid peer device ID")
            return
        }

        val peerPk = hello.publicKeyB64?.let {
            runCatching { Base64.getDecoder().decode(it) }.getOrNull()
        }
        if (peerPk == null || peerPk.size != 32 || !DeviceId.matches(peerDeviceId, peerPk)) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: refusing to pair with a peer whose deviceId does not match its public key" }
            session.close("identity-mismatch")
            _events.value = ConnectionEvent.Failed("Peer identity does not match public key")
            return
        }

        val secret = FrameCrypto.newSessionSecret()
        val peer = TrustedPeer(
            deviceId = peerDeviceId,
            staticPublicKey = peerPk,
            pairingSecret = secret.copyOf(),
            name = hello.deviceName,
            platform = Platform.fromWire(hello.platform),
            pairedAtEpochMillis = System.currentTimeMillis(),
        )

        try {
            saveTrustedPeer(peer)
        } catch (t: Throwable) {
            VeyroLog.e(TAG, t) { "[$connId] Trust persistence failed: could not save trusted peer (${t.message})" }
            session.close("persistence-failed")
            _events.value = ConnectionEvent.Failed("Could not save pairing on this device")
            return
        }

        val localPk = localPublicKey()
        // Sealed, always. The secret must not cross the network in the clear, and the bootstrap key
        // is available to seal it with.
        session.sendSealed(
            LinkMessage.PairOk(
                deviceId = selfId,
                deviceName = localDeviceName(),
                platform = Platform.ANDROID.toWire(),
                sessionSecretB64 = Base64.getEncoder().encodeToString(secret),
                publicKeyB64 = Base64.getEncoder().encodeToString(localPk),
            ),
        )

        session.rekey(FrameCrypto.sessionKeyFromBytes(secret))
        pairingWindows.close(preamble.id)
        VeyroLog.i(TAG) { "[$connId] Cryptographic authentication completed (pairing-mode, peer=${hello.deviceName})" }
        VeyroLog.i(TAG) { "[$connId] Session established with ${hello.deviceName}" }
        _events.value = ConnectionEvent.Paired(peer)
        claimSlot(session)

        // Launch the session driver so the receiver can handle file transfers over this session
        val driverJob = sessionDriver?.let { driver ->
            scope.launch { runCatching { driver(session, true) } }
        }

        // Hold the connection open for the lifetime of the conversation.
        readJob.join()
        driverJob?.cancel()
        releaseSlot(session)
        _events.value = ConnectionEvent.Disconnected
        VeyroLog.i(TAG) { "[$connId] Session closed with ${hello.deviceName} (inbound, isConnected=${session.isConnected})" }
    }

    // ── Outbound ───────────────────────────────────────────────────────────────────────────

    /**
     * Dials a peer, branching on whether it already knows us.
     *
     * Races nothing: the caller supplies the endpoints in preference order and the first that
     * accepts a WebSocket wins. A peer advertising several addresses will have the reachable one
     * tried within a second of the unreachable one timing out.
     */
    public suspend fun connect(
        peerDeviceId: String,
        peerName: String,
        endpoints: List<Endpoint>,
        forceReconnect: Boolean = false,
    ): Boolean = dial(
        peerDeviceId = peerDeviceId,
        peerName = peerName,
        endpoints = endpoints,
        forceReconnect = forceReconnect,
        pairMode = LinkPreamble.PAIR_CONFIRM,
        presetCode = null,
        expectedPeerId = null,
        onProgress = {},
    ).isSuccess

    /**
     * Pairs with a device picked from the nearby list by comparing a code (04-PROTOCOL.md 4.3,
     * ADR-031). Nothing is typed.
     *
     * Emits [CodePairingProgress.Connecting], [CodePairingProgress.Securing], then
     * [CodePairingProgress.AwaitingConfirmation] with the 6 digits to show while the user accepts on
     * the other device, then one terminal value. [cancelOutboundPairing] aborts and ends the flow
     * with `CANCELLED`. If the peer already trusts this device a session opens instead
     * ([CodePairingProgress.AlreadyPaired]).
     */
    public fun pairWithConfirm(
        peerDeviceId: String,
        peerName: String,
        endpoints: List<Endpoint>,
    ): Flow<CodePairingProgress> = channelFlow {
        send(CodePairingProgress.Connecting)
        val outcome = dial(
            peerDeviceId = peerDeviceId,
            peerName = peerName,
            endpoints = endpoints,
            forceReconnect = false,
            pairMode = LinkPreamble.PAIR_CONFIRM,
            presetCode = null,
            expectedPeerId = null,
            onProgress = { send(it) },
        )
        send(terminalProgress(outcome, peerDeviceId, peerName))
    }

    private suspend fun terminalProgress(
        outcome: DialOutcome,
        peerDeviceId: String,
        peerName: String,
    ): CodePairingProgress = when (outcome) {
        is DialOutcome.Paired -> CodePairingProgress.Paired(outcome.peer)
        DialOutcome.Session -> CodePairingProgress.AlreadyPaired(trustedPeer(peerDeviceId)?.name ?: peerName)
        is DialOutcome.Failed -> CodePairingProgress.Failed(
            outcome.failure,
            (_events.value as? ConnectionEvent.Failed)?.reason ?: "Pairing failed",
        )
    }

    /**
     * Pairs with a PC whose QR code was scanned (04-PROTOCOL.md section 4.2, ADR-030).
     *
     * Same dial and same PIN bootstrap as [connect], with two differences: the scanned [code] is
     * used instead of prompting, and after `pairOk` the peer's public key must hash to
     * [peerDeviceId]. A device answering at the scanned address with any other identity is
     * dropped before anything is persisted. [code] is never logged.
     */
    public fun pairWithCode(
        peerDeviceId: String,
        peerName: String,
        endpoints: List<Endpoint>,
        code: String,
    ): Flow<CodePairingProgress> = channelFlow {
        send(CodePairingProgress.Connecting)
        if (code.length != 6 || !code.all { it in '0'..'9' }) {
            send(CodePairingProgress.Failed(CodePairingFailure.CODE_REJECTED, "The pairing code is not valid"))
            return@channelFlow
        }
        val outcome = dial(
            peerDeviceId = peerDeviceId,
            peerName = peerName,
            endpoints = endpoints,
            forceReconnect = false,
            pairMode = LinkPreamble.PAIR_QR,
            presetCode = code,
            expectedPeerId = peerDeviceId,
            onProgress = { send(it) },
        )
        send(terminalProgress(outcome, peerDeviceId, peerName))
    }

    private suspend fun dial(
        peerDeviceId: String,
        peerName: String,
        endpoints: List<Endpoint>,
        forceReconnect: Boolean,
        pairMode: String?,
        presetCode: String?,
        expectedPeerId: String?,
        onProgress: suspend (CodePairingProgress) -> Unit,
    ): DialOutcome = dialLock(peerDeviceId).withLock {
        val connId = "out-" + UUID.randomUUID().toString().take(8)
        if (endpoints.isEmpty()) {
            VeyroLog.w(TAG) { "[$connId] Connect failed: no address for $peerName" }
            _events.value = ConnectionEvent.Failed("No address for $peerName")
            return DialOutcome.Failed(CodePairingFailure.UNREACHABLE)
        }
        // Only this peer's session matters: other PCs keep theirs (multiple PCs).
        val existing = _connections.value[peerDeviceId]
        if (existing != null) {
            if (existing.isConnected && !forceReconnect) {
                VeyroLog.i(TAG) { "[$connId] Connect reused existing session with $peerName ($peerDeviceId)" }
                return DialOutcome.Session
            }
            if (existing.isConnected) {
                VeyroLog.i(TAG) { "[$connId] Force reconnect requested for $peerName ($peerDeviceId); closing existing session" }
                scope.launch { runCatching { existing.close("force-reconnect") } }
            } else {
                VeyroLog.i(TAG) { "[$connId] Clearing inactive session with $peerName before connecting" }
            }
            dropSlot(existing)
        }
        startListening()

        VeyroLog.i(TAG) { "[$connId] Starting outbound connection to $peerName ($peerDeviceId) across ${endpoints.size} endpoint(s)" }
        val dialSuccess = dialAny(endpoints, connId) ?: run {
            VeyroLog.w(TAG) { "[$connId] Connect failed: could not reach $peerName on any endpoint" }
            _events.value = ConnectionEvent.Failed("Could not reach $peerName")
            return DialOutcome.Failed(CodePairingFailure.UNREACHABLE)
        }
        val ws = dialSuccess.ws
        val remoteHost = dialSuccess.remoteHost

        VeyroLog.i(TAG) { "[$connId] TCP connection established and WebSocket handshake completed with $peerName (remoteHost=$remoteHost)" }

        val selfId = localDeviceId()
        val selfName = localDeviceName()
        // The key goes in the preamble only for confirm pairing; the receiver needs it to commit.
        val selfPk = if (pairMode == LinkPreamble.PAIR_CONFIRM) b64(localPublicKey()) else null
        VeyroLog.i(TAG) { "[$connId] Client preamble sent (id=$selfId, name=$selfName, pair=$pairMode)" }
        ws.send(
            Frame.Text(
                LinkJson.codec.encodeToString(
                    LinkPreamble.serializer(),
                    LinkPreamble(selfId, selfName, pair = pairMode, pk = selfPk),
                ),
            ),
        )

        val ack = readAck(ws, connId, ACK_TIMEOUT_MS) ?: run {
            ws.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "no-ack"))
            VeyroLog.w(TAG) { "[$connId] Handshake failed: no preamble ack received from $peerName within ${ACK_TIMEOUT_MS}ms" }
            _events.value = ConnectionEvent.Failed("$peerName did not answer")
            return DialOutcome.Failed(CodePairingFailure.UNREACHABLE)
        }
        VeyroLog.i(TAG) { "[$connId] Client acknowledgement received: mode=${ack.mode}, reason=${ack.reason}" }

        return when (ack.mode) {
            LinkPreambleAck.MODE_SESSION -> {
                val known = trustedPeer(peerDeviceId)?.takeUnless { it.revoked }
                if (known == null) {
                    // The peer thinks we are paired and we do not. Almost always means this device
                    // reset its identity; telling the user to re-pair is the actionable answer.
                    VeyroLog.w(TAG) { "[$connId] Handshake failed: remote peer expects session mode but local pairing secret is missing" }
                    ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no-secret"))
                    _events.value = ConnectionEvent.Failed("Pairing was lost. Pair with $peerName again.")
                    DialOutcome.Failed(CodePairingFailure.OTHER)
                } else {
                    scope.launch { runConnection(ws, known, encrypt = true, inbound = false, connId = connId, remoteHost = remoteHost) }
                    DialOutcome.Session
                }
            }

            LinkPreambleAck.MODE_PAIRING_CONFIRM -> {
                if (pairMode != LinkPreamble.PAIR_CONFIRM) {
                    VeyroLog.w(TAG) { "[$connId] Peer answered pairing-confirm to a ${pairMode ?: "legacy"} preamble; aborting" }
                    ws.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "unexpected-mode"))
                    _events.value = ConnectionEvent.Failed("$peerName answered in an unexpected way")
                    return DialOutcome.Failed(CodePairingFailure.OTHER)
                }
                VeyroLog.i(TAG) { "[$connId] Proceeding with confirm pairing with $peerName" }
                pairingLock.withLock {
                    runOutboundConfirm(ws, peerDeviceId, peerName, ack, connId, remoteHost, onProgress)
                }
            }

            LinkPreambleAck.MODE_PAIRING -> {
                VeyroLog.i(TAG) { "[$connId] Proceeding with outbound pairing exchange with $peerName" }
                if (presetCode != null) onProgress(CodePairingProgress.Securing)
                pairingLock.withLock {
                    runOutboundPairing(ws, peerDeviceId, peerName, connId, remoteHost, presetCode, expectedPeerId)
                }
            }

            else -> {
                VeyroLog.w(TAG) { "[$connId] Peer $peerName rejected connection with mode '${ack.mode}' (reason: ${ack.reason})" }
                ws.close(CloseReason(CloseReason.Codes.NORMAL, ack.mode))
                _events.value = ConnectionEvent.Failed(
                    ack.reason ?: "$peerName is not accepting connections right now",
                )
                DialOutcome.Failed(CodePairingFailure.NOT_ACCEPTING)
            }
        }
    }

    /**
     * Runs the initiator side of PIN pairing.
     *
     * [presetCode] non-null means a scanned QR code supplies the PIN, so the user is not prompted.
     * [expectedPeerId] non-null adds the ADR-030 check: the key in `pairOk` must hash to it.
     */
    private suspend fun runOutboundPairing(
        ws: DefaultWebSocketSession,
        peerDeviceId: String,
        peerName: String,
        connId: String,
        remoteHost: String? = null,
        presetCode: String? = null,
        expectedPeerId: String? = null,
    ): DialOutcome {
        val pin = if (presetCode != null) {
            presetCode
        } else {
            _pendingOutbound.value = PendingOutbound(peerDeviceId, peerName)
            val pinDeferred = CompletableDeferred<String?>()
            outboundPin = pinDeferred

            val typed = withTimeoutOrNull(PairingRegistry.PIN_TTL_MS) { pinDeferred.await() }
            outboundPin = null
            _pendingOutbound.value = null
            typed
        }

        if (pin.isNullOrBlank()) {
            VeyroLog.w(TAG) { "[$connId] Outbound pairing cancelled: empty PIN or user cancelled" }
            ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no-pin"))
            _events.value = ConnectionEvent.Failed("Pairing cancelled")
            return DialOutcome.Failed(CodePairingFailure.OTHER)
        }

        val selfId = localDeviceId()
        // (initiator, target) order - we are the initiator here.
        val bootstrapKey = pairingWindows.deriveInitiatorKey(pin, selfId, peerDeviceId)
        val session = PeerConnection(
            ws = ws,
            initialKey = bootstrapKey,
            initialEncrypt = false,
            localDeviceId = selfId,
            tag = "Connection(pair-out-$connId)",
            dispatcher = dispatchers.io,
            peerDeviceId = peerDeviceId,
            remoteHost = remoteHost,
        )
        val readJob = session.start()

        val localPk = localPublicKey()
        VeyroLog.i(TAG) { "[$connId] Outbound PairHello sent to $peerName under bootstrap key" }
        session.send(
            LinkMessage.PairHello(
                deviceId = selfId,
                deviceName = localDeviceName(),
                platform = Platform.ANDROID.toWire(),
                appVersion = "0.1.0",
                publicKeyB64 = Base64.getEncoder().encodeToString(localPk),
            ),
        )

        val envelope = session.receiveHandshake(PAIR_EXCHANGE_TIMEOUT_MS)

        return when (val payload = envelope?.payload) {
            is LinkMessage.PairOk -> {
                val secret = runCatching { Base64.getDecoder().decode(payload.sessionSecretB64) }
                    .getOrNull()
                    ?.takeIf { it.size == 32 }
                if (secret == null) {
                    VeyroLog.w(TAG) { "[$connId] Handshake failed: invalid session secret in PairOk" }
                    session.close("bad-secret")
                    _events.value = ConnectionEvent.Failed("The other device sent an invalid key")
                    return DialOutcome.Failed(CodePairingFailure.OTHER)
                }
                val id = DeviceId.parseOrNull(payload.deviceId) ?: run {
                    VeyroLog.w(TAG) { "[$connId] Handshake failed: invalid device ID in PairOk" }
                    session.close("bad-id")
                    _events.value = ConnectionEvent.Failed("Invalid peer device ID")
                    return DialOutcome.Failed(CodePairingFailure.IDENTITY_MISMATCH)
                }
                val peerPk = payload.publicKeyB64?.let {
                    runCatching { Base64.getDecoder().decode(it) }.getOrNull()
                }
                if (peerPk == null || peerPk.size != 32 || !DeviceId.matches(id, peerPk)) {
                    VeyroLog.w(TAG) { "[$connId] Handshake failed: refusing to pair with a peer whose deviceId does not match its public key" }
                    session.close("identity-mismatch")
                    _events.value = ConnectionEvent.Failed("Peer identity does not match public key")
                    return DialOutcome.Failed(CodePairingFailure.IDENTITY_MISMATCH)
                }
                // ADR-030: whoever answered at the scanned address must be the PC whose id was in
                // the QR code. A self-consistent but different identity is someone else.
                if (expectedPeerId != null && DeviceId.fromPublicKey(peerPk).value != expectedPeerId) {
                    VeyroLog.w(TAG) { "[$connId] Handshake failed: peer public key does not match the scanned device id; nothing stored" }
                    session.close("identity-mismatch")
                    _events.value = ConnectionEvent.Failed("This is not the PC whose code was scanned")
                    return DialOutcome.Failed(CodePairingFailure.IDENTITY_MISMATCH)
                }
                val peer = TrustedPeer(
                    deviceId = id,
                    staticPublicKey = peerPk,
                    pairingSecret = secret.copyOf(),
                    name = payload.deviceName,
                    platform = Platform.fromWire(payload.platform),
                    pairedAtEpochMillis = System.currentTimeMillis(),
                )
                try {
                    saveTrustedPeer(peer)
                } catch (t: Throwable) {
                    VeyroLog.e(TAG, t) { "[$connId] Trust persistence failed: could not save trusted peer (${t.message})" }
                    session.close("persistence-failed")
                    _events.value = ConnectionEvent.Failed("Could not save pairing on this device")
                    return DialOutcome.Failed(CodePairingFailure.OTHER)
                }
                session.rekey(FrameCrypto.sessionKeyFromBytes(secret))
                VeyroLog.i(TAG) { "[$connId] Cryptographic authentication completed (pairing-mode, peer=${payload.deviceName})" }
                VeyroLog.i(TAG) { "[$connId] Session established with ${payload.deviceName}" }
                _events.value = ConnectionEvent.Paired(peer)
                claimSlot(session)
                val driverJob = sessionDriver?.let { driver ->
                    scope.launch { runCatching { driver(session, false) } }
                }
                scope.launch {
                    readJob.join()
                    driverJob?.cancel()
                    releaseSlot(session)
                    _events.value = ConnectionEvent.Disconnected
                    VeyroLog.i(TAG) { "[$connId] Session closed with ${payload.deviceName} (outbound, isConnected=${session.isConnected})" }
                }
                DialOutcome.Paired(peer)
            }

            is LinkMessage.PairFail -> {
                VeyroLog.w(TAG) { "[$connId] Pairing rejected by peer: ${payload.reason}" }
                session.close("pair-fail")
                _events.value = ConnectionEvent.Failed(payload.reason)
                DialOutcome.Failed(CodePairingFailure.CODE_REJECTED)
            }

            else -> {
                VeyroLog.w(TAG) { "[$connId] Handshake failed: PairOk timed out or PIN mismatch" }
                session.close("pair-timeout")
                // The overwhelmingly likely cause, and the one the user can act on.
                _events.value = ConnectionEvent.Failed("The PIN did not match. Check it and try again.")
                DialOutcome.Failed(CodePairingFailure.CODE_REJECTED)
            }
        }
    }

    // ── Confirm pairing (04-PROTOCOL.md 4.3, ADR-031) ──────────────────────────────────────
    //
    // A = initiator, B = receiver. Nonces, codes and keys are never logged.

    private enum class InboundDecision { ACCEPT, DECLINE, ABANDONED, TIMEOUT }

    private sealed interface OutboundAnswer {
        data class Reply(val payload: LinkMessage) : OutboundAnswer
        data object Cancelled : OutboundAnswer
        data object Lost : OutboundAnswer
        data object TimedOut : OutboundAnswer
    }

    /**
     * Initiator side. The receiver's key must hash to the id we dialled; its nonce must match the
     * commitment it sent before seeing ours. Only then is the code shown and `pairHello` sent.
     */
    private suspend fun runOutboundConfirm(
        ws: DefaultWebSocketSession,
        peerDeviceId: String,
        peerName: String,
        ack: LinkPreambleAck,
        connId: String,
        remoteHost: String?,
        onProgress: suspend (CodePairingProgress) -> Unit,
    ): DialOutcome {
        val selfId = localDeviceId()
        val pkA = localPublicKey()
        val pkB = decodeKey(ack.pk)
        val commit = decodeB64OrNull(ack.commit)?.takeIf { it.size == PairConfirm.COMMIT_SIZE }
        val dialled = DeviceId.parseOrNull(peerDeviceId)
        if (pkB == null || commit == null || dialled == null || !DeviceId.matches(dialled, pkB)) {
            VeyroLog.w(TAG) { "[$connId] Confirm pairing aborted: the answering key does not match the dialled device id; nothing stored" }
            ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "identity-mismatch"))
            _events.value = ConnectionEvent.Failed("The device that answered is not $peerName. Nothing was saved.")
            return DialOutcome.Failed(CodePairingFailure.IDENTITY_MISMATCH)
        }
        val idB = dialled.value

        val cancel = CompletableDeferred<Unit>()
        outboundConfirmCancel = cancel
        _pendingOutbound.value = PendingOutbound(idB, peerName)
        var session: PeerConnection? = null
        try {
            onProgress(CodePairingProgress.Securing)
            val nA = PairConfirm.newNonce()
            sendPairNonce(ws, nA)
            val nB = readPairNonce(ws, connId) ?: run {
                ws.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "no-nonce"))
                _events.value = ConnectionEvent.Failed("$peerName stopped answering")
                return DialOutcome.Failed(CodePairingFailure.OTHER)
            }
            if (!PairConfirm.verifyCommitment(commit, pkB, pkA, nB)) {
                VeyroLog.w(TAG) { "[$connId] Confirm pairing aborted: the peer's nonce does not match its commitment; nothing stored" }
                ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "commitment-mismatch"))
                _events.value = ConnectionEvent.Failed("$peerName could not be verified. Nothing was saved.")
                return DialOutcome.Failed(CodePairingFailure.IDENTITY_MISMATCH)
            }

            val code = PairConfirm.code(pkA, pkB, nA, nB)
            val key = try {
                deriveSharedKey(pkB, PairConfirm.bootstrapSalt(nA, nB), PairConfirm.bootstrapInfo(selfId, idB))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                VeyroLog.w(TAG) { "[$connId] Confirm pairing aborted: key agreement rejected (${t.javaClass.simpleName})" }
                ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad-key"))
                _events.value = ConnectionEvent.Failed("$peerName sent an unusable key. Nothing was saved.")
                return DialOutcome.Failed(CodePairingFailure.IDENTITY_MISMATCH)
            }
            val pairing = PeerConnection(
                ws = ws,
                initialKey = FrameCrypto.sessionKeyFromBytes(key),
                initialEncrypt = false,
                localDeviceId = selfId,
                tag = "Connection(confirm-out-$connId)",
                dispatcher = dispatchers.io,
                peerDeviceId = idB,
                remoteHost = remoteHost,
            )
            key.fill(0)
            session = pairing
            // The receiver sends nothing until its user decides; see PeerConnection.heartbeatPaused.
            pairing.heartbeatPaused = true
            val readJob = pairing.start()
            pairing.send(
                LinkMessage.PairHello(
                    deviceId = selfId,
                    deviceName = localDeviceName(),
                    platform = Platform.ANDROID.toWire(),
                    appVersion = "0.1.0",
                    publicKeyB64 = b64(pkA),
                ),
            )
            _pendingOutbound.value = PendingOutbound(idB, peerName, confirmCode = code)
            VeyroLog.i(TAG) { "[$connId] Confirm code ready; waiting for $peerName to accept" }
            onProgress(CodePairingProgress.AwaitingConfirmation(code))

            val answer = awaitOutboundConfirm(pairing, cancel)
            _pendingOutbound.value = null
            return when (answer) {
                is OutboundAnswer.Reply -> when (val payload = answer.payload) {
                    is LinkMessage.PairOk -> completeOutboundConfirm(pairing, readJob, payload, dialled, pkB, connId)
                    is LinkMessage.PairFail -> {
                        val failure = when (payload.reason) {
                            "declined" -> CodePairingFailure.DECLINED
                            "timeout" -> CodePairingFailure.TIMEOUT
                            else -> CodePairingFailure.NOT_ACCEPTING
                        }
                        VeyroLog.i(TAG) { "[$connId] Confirm pairing ended by $peerName: ${payload.reason}" }
                        pairing.close("pair-fail")
                        _events.value = ConnectionEvent.Failed(
                            when (failure) {
                                CodePairingFailure.DECLINED -> "Pairing was declined on $peerName"
                                CodePairingFailure.TIMEOUT -> "Nobody accepted on $peerName in time"
                                else -> "$peerName did not pair"
                            },
                        )
                        DialOutcome.Failed(failure)
                    }
                    else -> {
                        pairing.close("unexpected")
                        _events.value = ConnectionEvent.Failed("$peerName answered in an unexpected way")
                        DialOutcome.Failed(CodePairingFailure.OTHER)
                    }
                }
                OutboundAnswer.Cancelled -> {
                    VeyroLog.i(TAG) { "[$connId] Confirm pairing cancelled on this device" }
                    runCatching { pairing.send(LinkMessage.PairFail("cancelled")) }
                    pairing.close("cancelled")
                    _events.value = ConnectionEvent.Failed("Pairing cancelled")
                    DialOutcome.Failed(CodePairingFailure.CANCELLED)
                }
                OutboundAnswer.TimedOut -> {
                    VeyroLog.i(TAG) { "[$connId] Confirm pairing timed out" }
                    pairing.close("timeout")
                    _events.value = ConnectionEvent.Failed("Nobody accepted on $peerName in time")
                    DialOutcome.Failed(CodePairingFailure.TIMEOUT)
                }
                OutboundAnswer.Lost -> {
                    VeyroLog.i(TAG) { "[$connId] Confirm pairing lost the connection" }
                    pairing.close("lost")
                    _events.value = ConnectionEvent.Failed("The connection to $peerName was lost")
                    DialOutcome.Failed(CodePairingFailure.OTHER)
                }
            }
        } catch (e: CancellationException) {
            // The caller went away (screen closed). Do not leave the receiver's prompt hanging.
            withContext(NonCancellable) {
                val open = session
                if (open != null) {
                    runCatching { open.send(LinkMessage.PairFail("cancelled")) }
                    open.close("cancelled")
                } else {
                    runCatching { ws.close(CloseReason(CloseReason.Codes.NORMAL, "cancelled")) }
                }
            }
            throw e
        } finally {
            if (outboundConfirmCancel === cancel) outboundConfirmCancel = null
            if (_pendingOutbound.value?.peerDeviceId == idB) _pendingOutbound.value = null
        }
    }

    private suspend fun awaitOutboundConfirm(
        session: PeerConnection,
        cancel: CompletableDeferred<Unit>,
    ): OutboundAnswer = coroutineScope {
        val reply = async {
            var result: LinkMessage? = null
            while (result == null) {
                val envelope = session.handshakeOrNull(Long.MAX_VALUE) ?: break
                val payload = envelope.payload
                if (payload is LinkMessage.PairOk || payload is LinkMessage.PairFail) result = payload
            }
            result
        }
        try {
            withTimeoutOrNull(confirmTimeoutMs + CONFIRM_GRACE_MS) {
                select<OutboundAnswer> {
                    reply.onAwait { payload -> payload?.let { OutboundAnswer.Reply(it) } ?: OutboundAnswer.Lost }
                    cancel.onAwait { OutboundAnswer.Cancelled }
                }
            } ?: OutboundAnswer.TimedOut
        } finally {
            reply.cancel()
        }
    }

    private suspend fun completeOutboundConfirm(
        session: PeerConnection,
        readJob: Job,
        payload: LinkMessage.PairOk,
        dialled: DeviceId,
        pkB: ByteArray,
        connId: String,
    ): DialOutcome {
        val secret = decodeB64OrNull(payload.sessionSecretB64)?.takeIf { it.size == 32 }
        val answeredId = DeviceId.parseOrNull(payload.deviceId)
        val answeredPk = decodeKey(payload.publicKeyB64)
        if (secret == null || answeredId != dialled || answeredPk == null || !answeredPk.contentEquals(pkB)) {
            VeyroLog.w(TAG) { "[$connId] Confirm pairing aborted: pairOk does not match the confirmed identity; nothing stored" }
            session.close("identity-mismatch")
            _events.value = ConnectionEvent.Failed("Peer identity does not match public key")
            return DialOutcome.Failed(CodePairingFailure.IDENTITY_MISMATCH)
        }
        val peer = TrustedPeer(
            deviceId = dialled,
            staticPublicKey = pkB,
            pairingSecret = secret.copyOf(),
            name = payload.deviceName,
            platform = Platform.fromWire(payload.platform),
            pairedAtEpochMillis = System.currentTimeMillis(),
        )
        try {
            saveTrustedPeer(peer)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            VeyroLog.e(TAG, t) { "[$connId] Trust persistence failed: could not save trusted peer (${t.message})" }
            session.close("persistence-failed")
            _events.value = ConnectionEvent.Failed("Could not save pairing on this device")
            return DialOutcome.Failed(CodePairingFailure.OTHER)
        }
        session.rekey(FrameCrypto.sessionKeyFromBytes(secret))
        secret.fill(0)
        session.heartbeatPaused = false
        VeyroLog.i(TAG) { "[$connId] Confirm pairing completed; session established with ${payload.deviceName}" }
        _events.value = ConnectionEvent.Paired(peer)
        claimSlot(session)
        val driverJob = sessionDriver?.let { driver ->
            scope.launch { runCatching { driver(session, false) } }
        }
        scope.launch {
            readJob.join()
            driverJob?.cancel()
            releaseSlot(session)
            _events.value = ConnectionEvent.Disconnected
            VeyroLog.i(TAG) { "[$connId] Session closed with ${payload.deviceName} (outbound, isConnected=${session.isConnected})" }
        }
        return DialOutcome.Paired(peer)
    }

    /**
     * Receiver side. Commit to nB, learn nA, reveal nB, check the initiator's `pairHello` under K,
     * then show the code with Accept / Decline. `pairOk` is sent only after Accept.
     */
    private suspend fun runInboundConfirm(
        ws: DefaultWebSocketSession,
        preamble: LinkPreamble,
        connId: String,
        remoteHost: String?,
        localHost: String?,
    ) {
        val pkA = decodeKey(preamble.pk)
        val claimed = DeviceId.parseOrNull(preamble.id)
        if (pkA == null || claimed == null || !DeviceId.matches(claimed, pkA)) {
            VeyroLog.w(TAG) { "[$connId] Confirm pairing refused: the preamble key does not match its device id" }
            sendAck(ws, LinkPreambleAck(LinkPreambleAck.MODE_REFUSED, "identity-mismatch"), connId)
            ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "identity-mismatch"))
            _events.value = ConnectionEvent.Failed("${preamble.name} sent an identity that does not match its key")
            return
        }
        val idA = claimed.value
        val selfId = localDeviceId()
        val pkB = localPublicKey()
        val nB = PairConfirm.newNonce()
        sendAck(
            ws,
            LinkPreambleAck(
                LinkPreambleAck.MODE_PAIRING_CONFIRM,
                pk = b64(pkB),
                commit = b64(PairConfirm.commitment(pkB, pkA, nB)),
            ),
            connId,
        )
        val nA = readPairNonce(ws, connId) ?: run {
            ws.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "no-nonce"))
            return
        }
        sendPairNonce(ws, nB)

        val code = PairConfirm.code(pkA, pkB, nA, nB)
        val key = try {
            deriveSharedKey(pkA, PairConfirm.bootstrapSalt(nA, nB), PairConfirm.bootstrapInfo(idA, selfId))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "[$connId] Confirm pairing refused: key agreement rejected (${t.javaClass.simpleName})" }
            ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad-key"))
            return
        }
        val session = PeerConnection(
            ws = ws,
            initialKey = FrameCrypto.sessionKeyFromBytes(key),
            initialEncrypt = false,
            localDeviceId = selfId,
            tag = "Connection(confirm-in-$connId)",
            dispatcher = dispatchers.io,
            peerDeviceId = idA,
            remoteHost = remoteHost,
            localHost = localHost,
        )
        key.fill(0)
        val readJob = session.start()

        // A pairHello that verifies under K proves the initiator holds the private key behind pkA.
        val hello = session.handshakeOrNull(CONFIRM_HELLO_TIMEOUT_MS)?.payload as? LinkMessage.PairHello
        val helloPk = decodeKey(hello?.publicKeyB64)
        if (hello == null || DeviceId.parseOrNull(hello.deviceId) != claimed || helloPk == null || !helloPk.contentEquals(pkA)) {
            VeyroLog.w(TAG) { "[$connId] Confirm pairing failed: no matching pairHello under the confirm key" }
            session.close("bad-hello")
            _events.value = ConnectionEvent.Failed("${preamble.name} could not be verified")
            return
        }

        val consent = CompletableDeferred<Boolean>()
        inboundConsent = consent
        pendingInboundWs = ws
        _pendingInbound.value = PendingInbound(
            peerDeviceId = idA,
            peerDeviceName = hello.deviceName,
            pin = code,
            expiresAt = System.currentTimeMillis() + confirmTimeoutMs,
            confirmCode = code,
        )
        VeyroLog.i(TAG) { "[$connId] Confirm code ready; waiting for the user to accept ${hello.deviceName}" }
        val decision = try {
            awaitInboundConfirm(consent, session, readJob)
        } finally {
            if (inboundConsent === consent) inboundConsent = null
            if (pendingInboundWs === ws) {
                _pendingInbound.value = null
                pendingInboundWs = null
            }
        }

        when (decision) {
            InboundDecision.ACCEPT -> finishInboundPairing(session, readJob, hello, claimed, pkA, connId)
            InboundDecision.DECLINE -> {
                VeyroLog.i(TAG) { "[$connId] Confirm pairing declined by the user" }
                runCatching { session.send(LinkMessage.PairFail("declined")) }
                session.close("declined")
                _events.value = ConnectionEvent.Failed("Pairing declined")
            }
            InboundDecision.TIMEOUT -> {
                VeyroLog.i(TAG) { "[$connId] Confirm pairing timed out" }
                runCatching { session.send(LinkMessage.PairFail("timeout")) }
                session.close("timeout")
                _events.value = ConnectionEvent.Failed("Pairing timed out")
            }
            InboundDecision.ABANDONED -> {
                VeyroLog.i(TAG) { "[$connId] Confirm pairing abandoned by ${hello.deviceName}" }
                session.close("abandoned")
                _events.value = ConnectionEvent.Failed("${hello.deviceName} cancelled pairing")
            }
        }
    }

    private suspend fun awaitInboundConfirm(
        consent: CompletableDeferred<Boolean>,
        session: PeerConnection,
        readJob: Job,
    ): InboundDecision = coroutineScope {
        // The initiator cancelling sends pairFail; it dropping the socket ends the read loop.
        val peerGaveUp = async {
            while (true) {
                val envelope = session.handshakeOrNull(Long.MAX_VALUE) ?: break
                if (envelope.payload is LinkMessage.PairFail) break
            }
        }
        try {
            withTimeoutOrNull(confirmTimeoutMs) {
                select<InboundDecision> {
                    consent.onAwait { if (it) InboundDecision.ACCEPT else InboundDecision.DECLINE }
                    readJob.onJoin { InboundDecision.ABANDONED }
                    peerGaveUp.onAwait { InboundDecision.ABANDONED }
                }
            } ?: InboundDecision.TIMEOUT
        } finally {
            peerGaveUp.cancel()
        }
    }

    /** Mints the secret, persists trust, answers `pairOk` sealed under the bootstrap key, runs the session. */
    private suspend fun finishInboundPairing(
        session: PeerConnection,
        readJob: Job,
        hello: LinkMessage.PairHello,
        peerDeviceId: DeviceId,
        peerPk: ByteArray,
        connId: String,
    ) {
        val secret = FrameCrypto.newSessionSecret()
        val peer = TrustedPeer(
            deviceId = peerDeviceId,
            staticPublicKey = peerPk,
            pairingSecret = secret.copyOf(),
            name = hello.deviceName,
            platform = Platform.fromWire(hello.platform),
            pairedAtEpochMillis = System.currentTimeMillis(),
        )
        try {
            saveTrustedPeer(peer)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            VeyroLog.e(TAG, t) { "[$connId] Trust persistence failed: could not save trusted peer (${t.message})" }
            runCatching { session.send(LinkMessage.PairFail("error")) }
            session.close("persistence-failed")
            _events.value = ConnectionEvent.Failed("Could not save pairing on this device")
            return
        }
        session.sendSealed(
            LinkMessage.PairOk(
                deviceId = localDeviceId(),
                deviceName = localDeviceName(),
                platform = Platform.ANDROID.toWire(),
                sessionSecretB64 = b64(secret),
                publicKeyB64 = b64(localPublicKey()),
            ),
        )
        session.rekey(FrameCrypto.sessionKeyFromBytes(secret))
        secret.fill(0)
        VeyroLog.i(TAG) { "[$connId] Confirm pairing completed; session established with ${hello.deviceName}" }
        _events.value = ConnectionEvent.Paired(peer)
        claimSlot(session)
        val driverJob = sessionDriver?.let { driver ->
            scope.launch { runCatching { driver(session, true) } }
        }
        readJob.join()
        driverJob?.cancel()
        releaseSlot(session)
        _events.value = ConnectionEvent.Disconnected
        VeyroLog.i(TAG) { "[$connId] Session closed with ${hello.deviceName} (inbound, isConnected=${session.isConnected})" }
    }

    /** Null on timeout, a closed socket, or anything that is not a well-formed 32-byte nonce. */
    private suspend fun readPairNonce(ws: DefaultWebSocketSession, connId: String): ByteArray? {
        val frame = try {
            withTimeout(PREAMBLE_TIMEOUT_MS) { ws.incoming.receive() }
        } catch (e: TimeoutCancellationException) {
            VeyroLog.w(TAG) { "[$connId] Confirm pairing: no pairNonce within ${PREAMBLE_TIMEOUT_MS}ms" }
            return null
        } catch (e: ClosedReceiveChannelException) {
            VeyroLog.w(TAG) { "[$connId] Confirm pairing: peer closed before pairNonce" }
            return null
        }
        if (frame !is Frame.Text || frame.data.size > MAX_PREAMBLE_BYTES) {
            VeyroLog.w(TAG) { "[$connId] Confirm pairing: unexpected frame instead of pairNonce" }
            return null
        }
        val message = runCatching {
            LinkJson.codec.decodeFromString(LinkPairNonce.serializer(), frame.readText())
        }.getOrNull()
        if (message == null || message.t != LinkPairNonce.TYPE) {
            VeyroLog.w(TAG) { "[$connId] Confirm pairing: malformed pairNonce" }
            return null
        }
        return decodeB64OrNull(message.n)?.takeIf { it.size == PairConfirm.NONCE_SIZE }
            ?: run {
                VeyroLog.w(TAG) { "[$connId] Confirm pairing: pairNonce has the wrong length" }
                null
            }
    }

    private suspend fun sendPairNonce(ws: DefaultWebSocketSession, nonce: ByteArray) {
        ws.send(Frame.Text(LinkJson.codec.encodeToString(LinkPairNonce.serializer(), LinkPairNonce(n = b64(nonce)))))
    }

    private suspend fun PeerConnection.handshakeOrNull(timeoutMs: Long): LinkEnvelope? = try {
        receiveHandshake(timeoutMs)
    } catch (e: ClosedReceiveChannelException) {
        null
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decodeB64OrNull(value: String?): ByteArray? =
        value?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    private fun decodeKey(value: String?): ByteArray? = decodeB64OrNull(value)?.takeIf { it.size == 32 }

    // ── User answers ───────────────────────────────────────────────────────────────────────

    /** Answers the inbound prompt: consent (legacy) or Accept / Decline of a confirm code. */
    public fun respondToInbound(accept: Boolean) {
        VeyroLog.i(TAG) { "User responded to inbound pairing consent: accept=$accept" }
        inboundConsent?.complete(accept)
    }

    /** Cancels an active or pending inbound pairing (consent prompt or PIN display). */
    public fun cancelInboundPairing() {
        VeyroLog.i(TAG) { "User cancelled inbound pairing" }
        inboundConsent?.complete(false)
        val ws = pendingInboundWs
        pendingInboundWs = null
        if (ws != null) {
            scope.launch {
                try {
                    ws.close(CloseReason(CloseReason.Codes.NORMAL, "user-cancelled"))
                } catch (_: Throwable) {}
            }
        }
        _pendingInbound.value = null
    }

    /** Supplies the PIN the user typed for an outbound pairing. Null cancels. */
    public fun submitPin(pin: String?) {
        outboundPin?.complete(pin)
    }

    /** Cancels a pending outbound pairing prompt. */
    public fun cancelOutboundPairing() {
        VeyroLog.i(TAG) { "User cancelled outbound pairing" }
        outboundPin?.complete(null)
        outboundConfirmCancel?.complete(Unit)
        _pendingOutbound.value = null
    }

    /** Disconnects every session, with every PC, and cancels any in-progress pairing. */
    public fun disconnect() {
        cancelInboundPairing()
        cancelOutboundPairing()
        val sessions = synchronized(slotLock) { sideSessions.toList() + _connections.value.values }
        closeAll(sessions, "user-disconnect")
    }

    /** Disconnects only the sessions with [peerDeviceId]; other PCs stay connected. */
    public fun disconnect(peerDeviceId: String) {
        val sessions = synchronized(slotLock) {
            sideSessions.filter { it.peerDeviceId == peerDeviceId } + listOfNotNull(_connections.value[peerDeviceId])
        }
        closeAll(sessions, "user-disconnect")
    }

    private fun closeAll(sessions: List<PeerConnection>, reason: String) {
        for (session in sessions) {
            scope.launch {
                try {
                    session.close(reason)
                } catch (_: Throwable) {}
            }
        }
    }

    public fun clearEvent() {
        _events.value = null
    }

    // ── Session ────────────────────────────────────────────────────────────────────────────

    private suspend fun runConnection(
        ws: DefaultWebSocketSession,
        peer: TrustedPeer,
        encrypt: Boolean,
        inbound: Boolean,
        connId: String,
        remoteHost: String? = null,
        localHost: String? = null,
    ) {
        val selfId = localDeviceId()
        VeyroLog.i(TAG) { "[$connId] Cryptographic authentication completed (session-mode, peer=${peer.name})" }
        val session = PeerConnection(
            ws = ws,
            initialKey = FrameCrypto.sessionKeyFromBytes(peer.pairingSecret),
            initialEncrypt = encrypt,
            localDeviceId = selfId,
            tag = "Connection(session-$connId)",
            dispatcher = dispatchers.io,
            peerDeviceId = peer.deviceId.value,
            remoteHost = remoteHost,
            localHost = localHost,
        )
        val readJob = session.start()
        val primary = claimSlot(session)
        if (primary) {
            _events.value = ConnectionEvent.Connected(peer.deviceId.value, peer.name)
        }
        VeyroLog.i(TAG) { "[$connId] Session established with ${peer.name} (remoteHost=$remoteHost, localHost=$localHost, primary=$primary)" }

        sendHello(session, selfId)

        // Every authenticated session listens for offers, whoever dialled (the PC sends files over
        // the phone's own session too). The phone's own sends drive themselves through mailboxes.
        // Launched alongside the read loop so a stalled transfer dies with the socket rather than
        // outliving it.
        val driverJob = sessionDriver?.let { driver ->
            scope.launch { runCatching { driver(session, inbound) } }
        }

        readJob.join()
        driverJob?.cancel()
        if (releaseSlot(session)) {
            _events.value = ConnectionEvent.Disconnected
        }
        VeyroLog.i(TAG) { "[$connId] Session closed with ${peer.name} (inbound=$inbound, isConnected=${session.isConnected})" }
    }

    /**
     * Puts [session] in its peer's slot in [connections], unless a live session with the same peer
     * already holds it; then [session] runs alongside as a side session (a PC transfer
     * connection) and the companion features stay on the existing one. Slots of other peers are
     * never touched. Returns true when [session] now holds its peer's slot.
     */
    private fun claimSlot(session: PeerConnection): Boolean = synchronized(slotLock) {
        val id = session.peerDeviceId ?: return@synchronized false
        val current = _connections.value[id]
        if (current != null && current !== session && current.isConnected) {
            sideSessions += session
            false
        } else {
            // Re-inserted at the end: the map's order is "most recently connected last".
            _connections.value = (_connections.value - id) + (id to session)
            recomputeActive()
            true
        }
    }

    /**
     * Removes [session] from the slots. If it held its peer's slot and a live side session with
     * the same peer remains, that one is promoted, so a PC that redialled keeps its companion
     * features. Returns true when that peer is now disconnected; other peers are unaffected.
     */
    private fun releaseSlot(session: PeerConnection): Boolean = synchronized(slotLock) {
        sideSessions.remove(session)
        val id = session.peerDeviceId ?: return@synchronized false
        if (_connections.value[id] !== session) return@synchronized false
        val heir = sideSessions.firstOrNull { it.isConnected && it.peerDeviceId == id }
        if (heir != null) {
            sideSessions.remove(heir)
            _connections.value = _connections.value + (id to heir)
            recomputeActive()
            false
        } else {
            _connections.value = _connections.value - id
            recomputeActive()
            true
        }
    }

    /** Takes [session] out of its peer's slot without promoting anything (force reconnect). */
    private fun dropSlot(session: PeerConnection) {
        synchronized(slotLock) {
            val id = session.peerDeviceId ?: return
            if (_connections.value[id] === session) {
                _connections.value = _connections.value - id
                recomputeActive()
            }
        }
    }

    /**
     * Opens a session by announcing this build's capability bits.
     *
     * An older peer ignores the field's value; the peer's own bits arrive in its `hello`/`helloAck`
     * and are recorded on [PeerConnection.peerCapabilities]. Companion features read that, so
     * nothing new is sent to a peer that did not advertise it (ADR-025).
     */
    private suspend fun sendHello(session: PeerConnection, selfId: String) {
        session.send(
            LinkMessage.Hello(
                deviceId = selfId,
                deviceName = localDeviceName(),
                platform = Platform.ANDROID.toWire(),
                capabilities = Capabilities.ANDROID_CURRENT.bits,
            ),
        )
    }

    // ── Plumbing ───────────────────────────────────────────────────────────────────────────

    private data class DialSuccess(
        val ws: DefaultWebSocketSession,
        val remoteHost: String,
    )

    private suspend fun dialAny(endpoints: List<Endpoint>, connId: String): DialSuccess? {
        for (endpoint in endpoints) {
            VeyroLog.d(TAG) { "[$connId] TCP dialling ws://${endpoint.host}:${endpoint.port}" }
            val result = runCatching { client.connect(endpoint.host, endpoint.port) }
            val ws = result.getOrNull()
            if (ws != null) {
                VeyroLog.i(TAG) { "[$connId] TCP connection established and WebSocket upgrade complete to $endpoint" }
                return DialSuccess(ws, endpoint.host)
            }
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) throw failure
            val cause = failure?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unknown error"
            VeyroLog.w(TAG) { "[$connId] Connection attempt to $endpoint failed ($cause)" }
        }
        return null
    }

    private suspend fun readPreamble(ws: DefaultWebSocketSession, connId: String): LinkPreamble? {
        val frame = try {
            withTimeout(PREAMBLE_TIMEOUT_MS) {
                ws.incoming.receive()
            }
        } catch (e: TimeoutCancellationException) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: genuine timeout waiting for preamble (${PREAMBLE_TIMEOUT_MS}ms)" }
            ws.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "preamble-timeout"))
            return null
        } catch (e: ClosedReceiveChannelException) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: remote peer disconnected before preamble (channel closed)" }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: exception reading preamble (${t.javaClass.simpleName}: ${t.message})" }
            ws.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "preamble-error"))
            return null
        }

        if (frame !is Frame.Text) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: unexpected frame waiting for preamble (${frame.frameType})" }
            ws.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "no-preamble"))
            return null
        }
        if (frame.data.size > MAX_PREAMBLE_BYTES) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: oversized preamble frame (${frame.data.size} bytes > $MAX_PREAMBLE_BYTES)" }
            ws.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "oversized-preamble"))
            return null
        }

        val text = frame.readText()
        val preamble = try {
            LinkJson.codec.decodeFromString(LinkPreamble.serializer(), text)
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: malformed preamble JSON (${t.message})" }
            ws.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "bad-preamble"))
            return null
        }

        if (preamble.protocolVersion != LINK_PROTOCOL_VERSION) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: protocol version mismatch (expected $LINK_PROTOCOL_VERSION, got ${preamble.protocolVersion})" }
            sendAck(ws, LinkPreambleAck(LinkPreambleAck.MODE_REFUSED, "unsupported-protocol-version"), connId)
            ws.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "version-mismatch"))
            return null
        }

        VeyroLog.i(TAG) { "[$connId] Server preamble received: peerId=${preamble.id}, peerName=${preamble.name}, version=${preamble.protocolVersion}" }
        return preamble
    }

    private suspend fun readAck(
        ws: DefaultWebSocketSession,
        connId: String,
        timeoutMs: Long = ACK_TIMEOUT_MS,
    ): LinkPreambleAck? {
        val frame = try {
            withTimeout(timeoutMs) {
                ws.incoming.receive()
            }
        } catch (e: TimeoutCancellationException) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: genuine timeout waiting for preamble ack (${timeoutMs}ms)" }
            return null
        } catch (e: ClosedReceiveChannelException) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: remote peer disconnected before preamble ack (channel closed)" }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: exception reading ack (${t.javaClass.simpleName}: ${t.message})" }
            return null
        }

        if (frame !is Frame.Text) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: unexpected frame waiting for ack (${frame.frameType})" }
            return null
        }
        if (frame.data.size > MAX_PREAMBLE_BYTES) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: oversized ack frame (${frame.data.size} bytes > $MAX_PREAMBLE_BYTES)" }
            return null
        }

        val text = frame.readText()
        return try {
            LinkJson.codec.decodeFromString(LinkPreambleAck.serializer(), text)
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "[$connId] Handshake failed: malformed ack JSON (${t.message})" }
            null
        }
    }

    private suspend fun sendAck(ws: DefaultWebSocketSession, ack: LinkPreambleAck, connId: String) {
        VeyroLog.i(TAG) { "[$connId] Server acknowledgement sent: mode=${ack.mode}, reason=${ack.reason}" }
        ws.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreambleAck.serializer(), ack)),
        )
    }

    public fun shutdown() {
        stopListening()
        client.close()
    }
}

/** Legacy alias for backwards compatibility. */
public typealias LinkManager = ConnectionManager
