package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * ## Who mints the secret
 *
 * The **target** mints the 32-byte session secret and sends it sealed under the PIN-derived key.
 * Not the initiator: the target is the device whose user read the PIN aloud, so it is the side that
 * has already demonstrated physical presence.
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
) {

    /**
     * Invoked once per established connection, on both the inbound and outbound side.
     *
     * This is how transfer plugs in without `ConnectionManager` depending on the transfer runner: the
     * coordinator sets this to "if inbound, receive". The sender does not use it - it drives the
     * send itself through [activeConnection] - so the driver is a no-op there. Set once, after
     * construction, before any connection.
     */
    public var sessionDriver: (suspend (connection: PeerConnection, inbound: Boolean) -> Unit)? = null

    private companion object {
        const val TAG = "ConnectionManager"
        const val PREAMBLE_TIMEOUT_MS = 10_000L
        const val CONSENT_TIMEOUT_MS = 30_000L
        const val ACK_TIMEOUT_MS = 35_000L
        const val PAIR_EXCHANGE_TIMEOUT_MS = 60_000L
        const val MAX_PREAMBLE_BYTES = 64 * 1024
    }

    /** What the pairing UI needs to render. */
    public data class PendingInbound(
        val peerDeviceId: String,
        val peerDeviceName: String,
        /** `"..."` until the user consents and a PIN is minted. */
        val pin: String,
        val expiresAt: Long,
    )

    public data class PendingOutbound(
        val peerDeviceId: String,
        val peerDeviceName: String,
    )

    public sealed interface ConnectionEvent {
        public data class Connected(val peerDeviceId: String, val peerName: String) : ConnectionEvent
        public data class Paired(val peer: TrustedPeer) : ConnectionEvent
        public data class Failed(val reason: String) : ConnectionEvent
        public data object Disconnected : ConnectionEvent
    }

    /** Legacy alias for backwards compatibility. */
    public typealias LinkEvent = ConnectionEvent

    private val pairingWindows = PairingRegistry()
    private val mutex = Mutex()

    private var server: ConnectionServer? = null

    private val _pendingInbound = MutableStateFlow<PendingInbound?>(null)
    public val pendingInbound: StateFlow<PendingInbound?> = _pendingInbound.asStateFlow()

    private val _pendingOutbound = MutableStateFlow<PendingOutbound?>(null)
    public val pendingOutbound: StateFlow<PendingOutbound?> = _pendingOutbound.asStateFlow()

    private val _events = MutableStateFlow<ConnectionEvent?>(null)
    public val events: StateFlow<ConnectionEvent?> = _events.asStateFlow()

    private val _activeConnection = MutableStateFlow<PeerConnection?>(null)
    public val activeConnection: StateFlow<PeerConnection?> = _activeConnection.asStateFlow()

    /** Legacy alias. */
    public val activeSession: StateFlow<PeerConnection?> get() = activeConnection

    /** Resolved when the local user answers an inbound pairing prompt. */
    private var inboundConsent: CompletableDeferred<Boolean>? = null

    /** Resolved when the local user types the PIN for an outbound pairing. */
    private var outboundPin: CompletableDeferred<String?>? = null

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

        // Guard against simultaneous connection attempts causing session confusion
        val active = _activeConnection.value
        if (active != null) {
            if (!active.isConnected) {
                VeyroLog.i(TAG) { "[$connId] Clearing inactive connection from active slot" }
                _activeConnection.value = null
            } else if (active.peerDeviceId == preamble.id) {
                VeyroLog.i(TAG) { "[$connId] Inbound connection from already-connected peer (${preamble.id}); closing older redundant session" }
                scope.launch { active.close("superseded-by-inbound") }
                _activeConnection.value = null
            } else {
                VeyroLog.w(TAG) { "[$connId] Inbound connection rejected: session already active with another peer (${active.peerDeviceId})" }
                sendAck(ws, LinkPreambleAck(LinkPreambleAck.MODE_BUSY, "already-connected"), connId)
                ws.close(CloseReason(CloseReason.Codes.NORMAL, "busy"))
                return
            }
        }

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
                _pendingOutbound.value = null
            }
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

        val known = trustedPeer(preamble.id)?.takeUnless { it.revoked }
        if (known != null) {
            sendAck(ws, LinkPreambleAck(LinkPreambleAck.MODE_SESSION), connId)
            runConnection(ws, known, encrypt = true, inbound = true, connId = connId, remoteHost = remoteHost, localHost = localHost)
            return
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
        _activeConnection.value = session

        // Launch the session driver so the receiver can handle file transfers over this session
        val driverJob = sessionDriver?.let { driver ->
            scope.launch { runCatching { driver(session, true) } }
        }

        // Hold the connection open for the lifetime of the conversation.
        readJob.join()
        driverJob?.cancel()
        _activeConnection.value = null
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
    ): Boolean = mutex.withLock {
        val connId = "out-" + UUID.randomUUID().toString().take(8)
        if (endpoints.isEmpty()) {
            VeyroLog.w(TAG) { "[$connId] Connect failed: no address for $peerName" }
            _events.value = ConnectionEvent.Failed("No address for $peerName")
            return false
        }
        val active = _activeConnection.value
        if (active != null) {
            if (!active.isConnected) {
                VeyroLog.i(TAG) { "[$connId] Clearing inactive connection from active slot before connecting" }
                _activeConnection.value = null
            } else if (active.peerDeviceId == peerDeviceId) {
                if (forceReconnect) {
                    VeyroLog.i(TAG) { "[$connId] Force reconnect requested for $peerName ($peerDeviceId); closing existing session" }
                    scope.launch { runCatching { active.close("force-reconnect") } }
                    _activeConnection.value = null
                } else {
                    VeyroLog.i(TAG) { "[$connId] Connect reused existing active session with $peerName ($peerDeviceId)" }
                    return true
                }
            } else if (forceReconnect) {
                VeyroLog.i(TAG) { "[$connId] Force reconnect requested; closing session with different peer (${active.peerDeviceId})" }
                scope.launch { runCatching { active.close("force-reconnect-switch") } }
                _activeConnection.value = null
            } else {
                VeyroLog.w(TAG) { "[$connId] Connect aborted: session already active with another peer (${active.peerDeviceId})" }
                _events.value = ConnectionEvent.Failed("Already connected to a peer")
                return false
            }
        }
        startListening()

        VeyroLog.i(TAG) { "[$connId] Starting outbound connection to $peerName ($peerDeviceId) across ${endpoints.size} endpoint(s)" }
        val dialSuccess = dialAny(endpoints, connId) ?: run {
            VeyroLog.w(TAG) { "[$connId] Connect failed: could not reach $peerName on any endpoint" }
            _events.value = ConnectionEvent.Failed("Could not reach $peerName")
            return false
        }
        val ws = dialSuccess.ws
        val remoteHost = dialSuccess.remoteHost

        VeyroLog.i(TAG) { "[$connId] TCP connection established and WebSocket handshake completed with $peerName (remoteHost=$remoteHost)" }

        val selfId = localDeviceId()
        val selfName = localDeviceName()
        VeyroLog.i(TAG) { "[$connId] Client preamble sent (id=$selfId, name=$selfName)" }
        ws.send(
            Frame.Text(
                LinkJson.codec.encodeToString(
                    LinkPreamble.serializer(),
                    LinkPreamble(selfId, selfName),
                ),
            ),
        )

        val ack = readAck(ws, connId, ACK_TIMEOUT_MS) ?: run {
            ws.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "no-ack"))
            VeyroLog.w(TAG) { "[$connId] Handshake failed: no preamble ack received from $peerName within ${ACK_TIMEOUT_MS}ms" }
            _events.value = ConnectionEvent.Failed("$peerName did not answer")
            return false
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
                    false
                } else {
                    scope.launch { runConnection(ws, known, encrypt = true, inbound = false, connId = connId, remoteHost = remoteHost) }
                    true
                }
            }

            LinkPreambleAck.MODE_PAIRING -> {
                VeyroLog.i(TAG) { "[$connId] Proceeding with outbound pairing exchange with $peerName" }
                runOutboundPairing(ws, peerDeviceId, peerName, connId, remoteHost = remoteHost)
            }

            else -> {
                VeyroLog.w(TAG) { "[$connId] Peer $peerName rejected connection with mode '${ack.mode}' (reason: ${ack.reason})" }
                ws.close(CloseReason(CloseReason.Codes.NORMAL, ack.mode))
                _events.value = ConnectionEvent.Failed(
                    ack.reason ?: "$peerName is not accepting connections right now",
                )
                false
            }
        }
    }

    private suspend fun runOutboundPairing(
        ws: DefaultWebSocketSession,
        peerDeviceId: String,
        peerName: String,
        connId: String,
        remoteHost: String? = null,
    ): Boolean {
        _pendingOutbound.value = PendingOutbound(peerDeviceId, peerName)
        val pinDeferred = CompletableDeferred<String?>()
        outboundPin = pinDeferred

        val pin = withTimeoutOrNull(PairingRegistry.PIN_TTL_MS) { pinDeferred.await() }
        outboundPin = null
        _pendingOutbound.value = null

        if (pin.isNullOrBlank()) {
            VeyroLog.w(TAG) { "[$connId] Outbound pairing cancelled: empty PIN or user cancelled" }
            ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no-pin"))
            _events.value = ConnectionEvent.Failed("Pairing cancelled")
            return false
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
                    return false
                }
                val id = DeviceId.parseOrNull(payload.deviceId) ?: run {
                    VeyroLog.w(TAG) { "[$connId] Handshake failed: invalid device ID in PairOk" }
                    session.close("bad-id")
                    _events.value = ConnectionEvent.Failed("Invalid peer device ID")
                    return false
                }
                val peerPk = payload.publicKeyB64?.let {
                    runCatching { Base64.getDecoder().decode(it) }.getOrNull()
                }
                if (peerPk == null || peerPk.size != 32 || !DeviceId.matches(id, peerPk)) {
                    VeyroLog.w(TAG) { "[$connId] Handshake failed: refusing to pair with a peer whose deviceId does not match its public key" }
                    session.close("identity-mismatch")
                    _events.value = ConnectionEvent.Failed("Peer identity does not match public key")
                    return false
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
                    return false
                }
                session.rekey(FrameCrypto.sessionKeyFromBytes(secret))
                VeyroLog.i(TAG) { "[$connId] Cryptographic authentication completed (pairing-mode, peer=${payload.deviceName})" }
                VeyroLog.i(TAG) { "[$connId] Session established with ${payload.deviceName}" }
                _events.value = ConnectionEvent.Paired(peer)
                _activeConnection.value = session
                val driverJob = sessionDriver?.let { driver ->
                    scope.launch { runCatching { driver(session, false) } }
                }
                scope.launch {
                    readJob.join()
                    driverJob?.cancel()
                    _activeConnection.value = null
                    _events.value = ConnectionEvent.Disconnected
                    VeyroLog.i(TAG) { "[$connId] Session closed with ${payload.deviceName} (outbound, isConnected=${session.isConnected})" }
                }
                true
            }

            is LinkMessage.PairFail -> {
                VeyroLog.w(TAG) { "[$connId] Pairing rejected by peer: ${payload.reason}" }
                session.close("pair-fail")
                _events.value = ConnectionEvent.Failed(payload.reason)
                false
            }

            else -> {
                VeyroLog.w(TAG) { "[$connId] Handshake failed: PairOk timed out or PIN mismatch" }
                session.close("pair-timeout")
                // The overwhelmingly likely cause, and the one the user can act on.
                _events.value = ConnectionEvent.Failed("The PIN did not match. Check it and try again.")
                false
            }
        }
    }

    // ── User answers ───────────────────────────────────────────────────────────────────────

    /** Answers the inbound consent prompt. */
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
        _pendingOutbound.value = null
    }

    /** Disconnects any active session and cancels any in-progress pairing. */
    public fun disconnect() {
        cancelInboundPairing()
        cancelOutboundPairing()
        val session = _activeConnection.value
        if (session != null) {
            scope.launch {
                try {
                    session.close("user-disconnect")
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
        _activeConnection.value = session
        _events.value = ConnectionEvent.Connected(peer.deviceId.value, peer.name)
        VeyroLog.i(TAG) { "[$connId] Session established with ${peer.name} (remoteHost=$remoteHost, localHost=$localHost)" }

        sendHello(session, selfId)

        // The receiver auto-listens for an offer; the sender's driver is a no-op (it sends via the
        // activeSession the UI drives). Launched alongside the read loop so a stalled transfer
        // dies with the socket rather than outliving it.
        val driverJob = sessionDriver?.let { driver ->
            scope.launch { runCatching { driver(session, inbound) } }
        }

        readJob.join()
        driverJob?.cancel()
        _activeConnection.value = null
        _events.value = ConnectionEvent.Disconnected
        VeyroLog.i(TAG) { "[$connId] Session closed with ${peer.name} (inbound=$inbound, isConnected=${session.isConnected})" }
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
