package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferProgress
import com.veyro.core.model.TransferRoute
import com.veyro.core.model.TransferStage
import com.veyro.core.model.TransferState
import com.veyro.core.model.VerificationResult
import com.veyro.core.network.InterfaceClassifier
import com.veyro.core.network.InterfaceKind
import com.veyro.core.network.Ipv4
import com.veyro.core.network.LocalAddressProvider
import com.veyro.core.network.WifiDirectManager
import com.veyro.core.transfer.ChunkMath
import com.veyro.core.transfer.ItemResult
import com.veyro.core.transfer.PartFile
import com.veyro.core.transfer.RollingSpeedCalculator
import com.veyro.core.transfer.StoragePublishResult
import com.veyro.core.transfer.TransferStoragePublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs a transfer coordinating WebSocket session control with a native raw TCP data plane.
 *
 * Session control messages (offers, decisions, progress reports, results, cancellation)
 * travel over the authenticated [PeerConnection] WebSocket channel.
 *
 * Pure binary payload chunks travel exclusively over native raw TCP sockets, preferring
 * high-speed Wi-Fi Direct and falling back reliably to the local network (LAN TCP).
 */
public class TransferRunner(
    private val dispatchers: AppDispatchers,
    private val wifiDirectManager: WifiDirectManager? = null,
    private val localAddressProvider: LocalAddressProvider? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private companion object {
        const val TAG = "TransferRunner"
        const val CHUNK_SIZE = 512 * 1024 // 512 KiB optimized throughput chunk size
        const val DECISION_TIMEOUT_MS = 120_000L
        const val STEP_TIMEOUT_MS = 60_000L
        const val PROGRESS_INTERVAL_MS = 200L
    }

    private val _progress = MutableSharedFlow<TransferProgress>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    public val progress: Flow<TransferProgress> = _progress.asSharedFlow()

    @Volatile
    private var cancelled = false

    private val activeSenders = ConcurrentHashMap<String, TcpDataSender>()
    private val activeReceivers = ConcurrentHashMap<String, TcpDataReceiver>()
    private val activeSessions = ConcurrentHashMap<String, PeerConnection>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    public fun cancel(transferId: String? = null) {
        cancelled = true
        VeyroLog.w(TAG) { "TransferRunner.cancel requested (targetTransferId=$transferId)" }
        if (transferId != null) {
            activeSenders[transferId]?.closeSocketImmediately()
            activeReceivers[transferId]?.notifyCancel("User cancelled")
            activeSessions[transferId]?.let { session ->
                CoroutineScope(dispatchers.io).launch {
                    runCatching { session.send(LinkMessage.Cancel(transferId, "User cancelled")) }
                }
            }
            activeJobs[transferId]?.cancel()
        } else {
            activeSenders.values.forEach { it.closeSocketImmediately() }
            activeReceivers.values.forEach { it.notifyCancel("User cancelled") }
            activeSessions.forEach { (id, session) ->
                CoroutineScope(dispatchers.io).launch {
                    runCatching { session.send(LinkMessage.Cancel(id, "User cancelled")) }
                }
            }
            activeJobs.values.forEach { it.cancel() }
        }
    }

    public sealed interface TargetHostResolution {
        public data class Valid(val host: String, val source: String) : TargetHostResolution
        public data class Invalid(val reason: String) : TargetHostResolution
    }

    internal fun isValidConcreteIpv4(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val trimmed = host.trim()
        if (trimmed == "0.0.0.0" || trimmed == "127.0.0.1" || trimmed.equals("localhost", ignoreCase = true)) return false
        if (Ipv4.parse(trimmed) == null) return false
        if (Ipv4.isLoopback(trimmed)) return false
        return true
    }

    internal suspend fun resolveReceiverLanAddress(session: PeerConnection): String? {
        val sessionLocal = session.localHost?.trim()
        val peerRemote = session.remoteHost?.trim()

        // 1. If session has a known local socket host (from accepted inbound WebSocket) that is valid, concrete, and non-loopback:
        if (isValidConcreteIpv4(sessionLocal) && sessionLocal != peerRemote) {
            VeyroLog.i(TAG) { "Receiver resolved LAN address from session.localHost: $sessionLocal" }
            return sessionLocal
        }

        // 2. Query LocalAddressProvider
        val localAddresses = runCatching { localAddressProvider?.localAddresses() }.getOrNull().orEmpty()

        // 2a. Subnet matching with peerRemote
        if (peerRemote != null && isValidConcreteIpv4(peerRemote)) {
            val nonNullPeer = peerRemote
            val subnetMatch = localAddresses.firstOrNull { local ->
                local.prefixLength in 1..31 &&
                    isValidConcreteIpv4(local.address) &&
                    local.address != nonNullPeer &&
                    Ipv4.sameSubnet(local.address, nonNullPeer, local.prefixLength)
            }
            if (subnetMatch != null) {
                VeyroLog.i(TAG) {
                    "Receiver resolved LAN address via subnet match with peer $nonNullPeer: ${subnetMatch.address} (${subnetMatch.interfaceName}/${subnetMatch.prefixLength})"
                }
                return subnetMatch.address
            }
        }

        // 2b. Best available non-loopback local address (prioritized by InterfaceClassifier in DefaultLocalAddressProvider)
        val bestLocal = localAddresses.firstOrNull { local ->
            isValidConcreteIpv4(local.address) && local.address != peerRemote && InterfaceClassifier.isAdvertisable(local.kind)
        } ?: localAddresses.firstOrNull { local ->
            isValidConcreteIpv4(local.address) && local.address != peerRemote
        }
        if (bestLocal != null) {
            VeyroLog.i(TAG) {
                "Receiver resolved LAN address from localAddressProvider: ${bestLocal.address} (${bestLocal.interfaceName}, kind=${bestLocal.kind})"
            }
            return bestLocal.address
        }

        // 3. Fallback for host-only unit tests: if communicating on loopback (127.0.0.1)
        if (peerRemote == "127.0.0.1" || sessionLocal == "127.0.0.1" || (peerRemote == null && sessionLocal == null && localAddresses.isEmpty())) {
            VeyroLog.d(TAG) { "Receiver using 127.0.0.1 for synthetic loopback session" }
            return "127.0.0.1"
        }

        return null
    }

    internal suspend fun resolveReceiverFallbackLanAddress(session: PeerConnection, primaryLanAddress: String): String? {
        val localAddresses = runCatching { localAddressProvider?.localAddresses() }.getOrNull().orEmpty()
        val peerRemote = session.remoteHost?.trim()
        val secondary = localAddresses.firstOrNull { local ->
            isValidConcreteIpv4(local.address) && local.address != primaryLanAddress && local.address != peerRemote
        }
        return secondary?.address
    }

    internal fun resolveSenderTargetHost(
        decision: LinkMessage.TransferDecision,
        session: PeerConnection,
    ): TargetHostResolution {
        val rawTcpHost = decision.tcpHost?.trim()
        val rawFallback = decision.fallbackHost?.trim()
        val rawSessionRemote = session.remoteHost?.trim()

        if (rawTcpHost == "0.0.0.0") {
            VeyroLog.w(TAG) { "Receiver advertised wildcard 0.0.0.0 as tcpHost; rejecting wildcard endpoint" }
        }

        // 1. Prefer decision.tcpHost if valid concrete IPv4
        if (isValidConcreteIpv4(rawTcpHost)) {
            return TargetHostResolution.Valid(rawTcpHost!!, "decision.tcpHost")
        }

        // 2. Try decision.fallbackHost if valid concrete IPv4
        if (isValidConcreteIpv4(rawFallback)) {
            return TargetHostResolution.Valid(rawFallback!!, "decision.fallbackHost")
        }

        // 3. Try session.remoteHost (the WebSocket server peer IP)
        if (isValidConcreteIpv4(rawSessionRemote)) {
            return TargetHostResolution.Valid(rawSessionRemote!!, "session.remoteHost")
        }

        // 4. Loopback allowance ONLY for synthetic local tests
        if (rawTcpHost == "127.0.0.1" || rawFallback == "127.0.0.1" || rawSessionRemote == "127.0.0.1") {
            return TargetHostResolution.Valid("127.0.0.1", "loopback-test")
        }

        return TargetHostResolution.Invalid(
            "No reachable IPv4 destination found (tcpHost='$rawTcpHost', fallbackHost='$rawFallback', session.remoteHost='$rawSessionRemote')"
        )
    }

    /**
     * Resolves the LAN fallback host when P2P was attempted but failed.
     *
     * Prefers [LinkMessage.TransferDecision.fallbackHost] (the LAN address the receiver
     * populated for exactly this scenario) over [LinkMessage.TransferDecision.tcpHost]
     * (which may be a P2P-only address like 192.168.49.1 that is unreachable on LAN).
     */
    internal fun resolveSenderFallbackHost(
        decision: LinkMessage.TransferDecision,
        session: PeerConnection,
    ): TargetHostResolution {
        val rawFallback = decision.fallbackHost?.trim()
        val rawSessionRemote = session.remoteHost?.trim()
        val rawTcpHost = decision.tcpHost?.trim()

        // 1. Prefer fallbackHost (the LAN address the receiver explicitly set)
        if (isValidConcreteIpv4(rawFallback)) {
            VeyroLog.i(TAG) { "P2P fallback using decision.fallbackHost: $rawFallback" }
            return TargetHostResolution.Valid(rawFallback!!, "decision.fallbackHost (P2P fallback)")
        }

        // 2. Try session.remoteHost (the WebSocket server peer IP — always a LAN address)
        if (isValidConcreteIpv4(rawSessionRemote)) {
            VeyroLog.i(TAG) { "P2P fallback using session.remoteHost: $rawSessionRemote" }
            return TargetHostResolution.Valid(rawSessionRemote!!, "session.remoteHost (P2P fallback)")
        }

        // 3. Last resort: tcpHost (might be a P2P address, but better than nothing)
        if (isValidConcreteIpv4(rawTcpHost)) {
            VeyroLog.w(TAG) { "P2P fallback using decision.tcpHost as last resort: $rawTcpHost (may be P2P address)" }
            return TargetHostResolution.Valid(rawTcpHost!!, "decision.tcpHost (P2P fallback last-resort)")
        }

        // 4. Loopback for tests
        if (rawFallback == "127.0.0.1" || rawSessionRemote == "127.0.0.1" || rawTcpHost == "127.0.0.1") {
            return TargetHostResolution.Valid("127.0.0.1", "loopback-test")
        }

        return TargetHostResolution.Invalid(
            "No reachable LAN fallback destination (fallbackHost='$rawFallback', session.remoteHost='$rawSessionRemote', tcpHost='$rawTcpHost')"
        )
    }

    // ── Send ───────────────────────────────────────────────────────────────────────────────

    public suspend fun send(
        session: PeerConnection,
        items: List<TransferItem>,
        senderName: String,
        transferId: String = UUID.randomUUID().toString(),
        onProgress: ((bytesSent: Long, totalBytes: Long, item: TransferItem, stage: TransferStage, speedBps: Long, etaSec: Long?) -> Unit)? = null,
        /** Set when this send answers a peer request (`photoFetch`, ADR-034); goes on the offer. */
        requestId: String? = null,
        resolveFile: (TransferItem) -> File,
    ): SendOutcome = withContext(dispatchers.io) {
        cancelled = false
        val files = items.filterNot { it.isDirectory }
        val totalBytes = files.sumOf { it.sizeBytes }

        VeyroLog.i(TAG) { "[$transferId] Starting transfer send to peer ${session.peerDeviceId} ($senderName -> ${files.size} files, $totalBytes bytes)" }

        val mailbox = session.mailbox(transferId)
        val tcpSender = TcpDataSender(dispatchers)

        val currentJob = coroutineContext[Job]
        if (currentJob != null) activeJobs[transferId] = currentJob
        activeSenders[transferId] = tcpSender
        activeSessions[transferId] = session

        try {
            // 1. Send TransferOffer with route capabilities over WebSocket
            val preferredRoutes = if (wifiDirectManager?.isAvailable == true) {
                listOf(TransferRoute.WIFI_DIRECT.name, TransferRoute.LAN_TCP.name)
            } else {
                listOf(TransferRoute.LAN_TCP.name)
            }

            session.send(
                LinkMessage.TransferOffer(
                    transferId = transferId,
                    items = files.map { it.toLinkItem() },
                    totalBytes = totalBytes,
                    senderName = senderName,
                    preferredRoutes = preferredRoutes,
                    requestId = requestId,
                ),
            )
            VeyroLog.i(TAG) { "[$transferId] TransferOffer sent (preferredRoutes=$preferredRoutes), awaiting receiver decision..." }
            files.firstOrNull()?.let { firstItem ->
                onProgress?.invoke(0L, totalBytes, firstItem, TransferStage.AWAITING_CONSENT, 0L, null)
            }

            // 2. Await TransferDecision over WebSocket
            val decision = mailbox.awaitDecision(DECISION_TIMEOUT_MS)

            if (decision == null) {
                VeyroLog.w(TAG) { "[$transferId] Receiver did not answer transfer offer within timeout" }
                files.firstOrNull()?.let { firstItem ->
                    onProgress?.invoke(0L, totalBytes, firstItem, TransferStage.FAILED, 0L, null)
                }
                return@withContext SendOutcome.Failed("No answer from the other device")
            }

            VeyroLog.i(TAG) {
                "[$transferId] [route-neg:step4] Sender decoded TransferDecision: accept=${decision.accept}, route=${decision.route}, tcpHost=${decision.tcpHost}, fallbackHost=${decision.fallbackHost}, tcpPort=${decision.tcpPort}"
            }

            if (!decision.accept) {
                VeyroLog.w(TAG) { "[$transferId] Receiver declined transfer offer: ${decision.reason}" }
                files.firstOrNull()?.let { firstItem ->
                    onProgress?.invoke(0L, totalBytes, firstItem, TransferStage.FAILED, 0L, null)
                }
                return@withContext SendOutcome.Declined
            }

            val targetPort = decision.tcpPort ?: run {
                VeyroLog.e(TAG) { "[$transferId] [route-neg:failed] Receiver accepted but did not provide TCP port" }
                return@withContext SendOutcome.Failed("Receiver did not provide TCP transfer endpoint")
            }
            val authToken = decision.authToken ?: run {
                VeyroLog.e(TAG) { "[$transferId] [route-neg:failed] Receiver accepted but did not provide TCP auth token" }
                return@withContext SendOutcome.Failed("Receiver did not provide TCP authentication token")
            }

            // 3. Connect raw TCP socket: prefer Wi-Fi Direct, fall back to LAN TCP
            var activeRoute = TransferRoute.LAN_TCP
            var tcpConnected = false
            var p2pAttempted = false

            if (decision.route == TransferRoute.WIFI_DIRECT.name && wifiDirectManager?.isAvailable == true) {
                p2pAttempted = true
                VeyroLog.i(TAG) { "[$transferId] Attempting Wi-Fi Direct P2P link (ssid='${decision.p2pSsid}')..." }
                val p2pLink = runCatching {
                    wifiDirectManager.prepareP2pLink(
                        isInitiator = true,
                        targetSsid = decision.p2pSsid,
                        targetPassphrase = decision.p2pPassphrase,
                    )
                }.getOrNull()
                if (p2pLink != null) {
                    // Use the P2P group owner address for TCP on the P2P interface.
                    // decision.tcpHost may be the GO's P2P address — prefer the link's resolved address.
                    val p2pHost = p2pLink.groupOwnerAddress
                    VeyroLog.i(TAG) { "[$transferId] [p2p:endpoints] Wi-Fi Direct link established, dialing TCP at $p2pHost:$targetPort..." }
                    val p2pConnected = runCatching {
                        tcpSender.connectAndAuthenticate(
                            host = p2pHost,
                            port = targetPort,
                            transferId = transferId,
                            authToken = authToken,
                            senderDeviceId = session.localDeviceId,
                            route = TransferRoute.WIFI_DIRECT,
                        )
                    }.getOrDefault(false)

                    if (p2pConnected) {
                        tcpConnected = true
                        activeRoute = TransferRoute.WIFI_DIRECT
                        VeyroLog.i(TAG) { "[$transferId] [route-neg:step8] Connected raw TCP over Wi-Fi Direct ($p2pHost:$targetPort)" }
                    } else {
                        VeyroLog.w(TAG) { "[$transferId] [p2p:fallback] Wi-Fi Direct TCP connection to $p2pHost:$targetPort failed; falling back to LAN TCP" }
                        // Clean up the P2P link since TCP over it failed
                        runCatching { wifiDirectManager.closeP2pLink() }
                    }
                } else {
                    VeyroLog.w(TAG) { "[$transferId] [p2p:fallback] Wi-Fi Direct link negotiation failed; falling back to LAN TCP" }
                }
            } else if (decision.route == TransferRoute.WIFI_DIRECT.name && wifiDirectManager?.isAvailable != true) {
                VeyroLog.i(TAG) { "[$transferId] [p2p:fallback] Sender Wi-Fi Direct unavailable (isAvailable=${wifiDirectManager?.isAvailable}); using LAN TCP" }
            }

            if (!tcpConnected) {
                // When falling back from P2P, use fallbackHost (the LAN address) instead of
                // tcpHost (which may be a P2P address like 192.168.49.1, unreachable on LAN).
                val resolution = if (p2pAttempted) {
                    resolveSenderFallbackHost(decision, session)
                } else {
                    resolveSenderTargetHost(decision, session)
                }
                if (resolution !is TargetHostResolution.Valid) {
                    val reason = (resolution as TargetHostResolution.Invalid).reason
                    VeyroLog.e(TAG) { "[$transferId] [route-neg:failed] Destination host resolution failed: $reason" }
                    files.firstOrNull()?.let { firstItem ->
                        onProgress?.invoke(0L, totalBytes, firstItem, TransferStage.FAILED, 0L, null)
                    }
                    return@withContext SendOutcome.Failed("Invalid TCP transfer destination: $reason")
                }

                val lanHost = resolution.host
                VeyroLog.i(TAG) {
                    "[$transferId] [route-neg:step5] Target resolved to $lanHost:$targetPort via ${resolution.source}"
                }
                VeyroLog.i(TAG) {
                    "[$transferId] [route-neg:step6] Sender invoking TcpDataSender.connectAndAuthenticate ($lanHost:$targetPort, route=$activeRoute)..."
                }

                try {
                    tcpSender.connectAndAuthenticate(
                        host = lanHost,
                        port = targetPort,
                        transferId = transferId,
                        authToken = authToken,
                        senderDeviceId = session.localDeviceId,
                        route = TransferRoute.LAN_TCP,
                    )
                    tcpConnected = true
                    activeRoute = TransferRoute.LAN_TCP
                    VeyroLog.i(TAG) { "[$transferId] [route-neg:step8] Connected raw TCP over LAN ($lanHost:$targetPort)" }
                } catch (t: Throwable) {
                    VeyroLog.e(TAG, t) {
                        "[$transferId] [route-neg:failed] Failed to establish raw TCP connection to $lanHost:$targetPort: ${t.javaClass.simpleName} - ${t.message}"
                    }
                    files.firstOrNull()?.let { firstItem ->
                        onProgress?.invoke(0L, totalBytes, firstItem, TransferStage.FAILED, 0L, null)
                    }
                    return@withContext SendOutcome.Failed("Could not establish TCP data connection to $lanHost:$targetPort: ${t.message}")
                }
            }

            // 4. Stream items over raw TCP
            val speedCalculator = RollingSpeedCalculator()
            val results = mutableListOf<ItemResult>()
            var sent = 0L
            var lastProgress = 0L

            val metrics = TransferThroughputMetrics(
                transferId = transferId,
                role = "SENDER",
                route = activeRoute,
                chunkSize = CHUNK_SIZE,
            )

            // Register active session for transfer-scoped cancellation
            val currentJob = coroutineContext[Job]
            if (currentJob != null) activeJobs[transferId] = currentJob
            activeSenders[transferId] = tcpSender
            activeSessions[transferId] = session

            var remoteCancelled = false
            val cancelWatcher = launch {
                while (session.isConnected && !cancelled && !remoteCancelled) {
                    val msg = mailbox.payloadChannel.receiveCatching().getOrNull()
                    if (msg is LinkMessage.Cancel && msg.transferId == transferId) {
                        VeyroLog.w(TAG) { "[$transferId] Peer cancelled transfer over WebSocket: ${msg.reason}" }
                        remoteCancelled = true
                        tcpSender.closeSocketImmediately()
                        break
                    }
                }
            }

            try {
                for (item in files) {
                    if (cancelled || remoteCancelled) {
                        VeyroLog.w(TAG) { "[$transferId] Transfer cancelled before streaming item #${item.index}" }
                        if (cancelled) {
                            tcpSender.sendCancel(transferId, "cancelled")
                            runCatching { session.send(LinkMessage.Cancel(transferId, "cancelled")) }
                        }
                        onProgress?.invoke(sent, totalBytes, item, TransferStage.CANCELLED, 0L, null)
                        return@withContext SendOutcome.Cancelled
                    }

                    val source = resolveFile(item)
                    if (!source.isFile) {
                        VeyroLog.e(TAG) { "[$transferId] Source file missing for item #${item.index}" }
                        results += ItemResult(
                            item.index, item.relativePath, 0,
                            VerificationResult.WRITE_FAILED, "source missing",
                        )
                        continue
                    }

                    val chunkCount = ChunkMath.chunkCount(item.sizeBytes, CHUNK_SIZE)
                    VeyroLog.i(TAG) {
                        "[$transferId] Streaming item #${item.index} (${item.relativePath}, ${item.sizeBytes} bytes, $chunkCount chunks) via raw TCP ($activeRoute)"
                    }

                    // Send ItemBegin over raw TCP and WebSocket
                    tcpSender.sendItemBegin(transferId, item.index, item.sizeBytes, chunkCount, CHUNK_SIZE)
                    session.send(
                        LinkMessage.ItemBegin(
                            transferId = transferId,
                            index = item.index,
                            size = item.sizeBytes,
                            chunkCount = chunkCount,
                            chunkSize = CHUNK_SIZE,
                        ),
                    )

                    // Resume support
                    val alreadyHave = decision.resume?.get(item.index)?.toSet() ?: emptySet()
                    val needed = (0 until chunkCount).filterNot { it in alreadyHave }

                    // Open file handle once per item for high performance chunk streaming
                    RandomAccessFile(source, "r").use { sourceRaf ->
                        for (chunkIndex in needed) {
                            if (cancelled || remoteCancelled) {
                                VeyroLog.w(TAG) { "[$transferId] Transfer cancelled during chunk streaming" }
                                if (cancelled) {
                                    tcpSender.sendCancel(transferId, "cancelled")
                                    runCatching { session.send(LinkMessage.Cancel(transferId, "cancelled")) }
                                }
                                onProgress?.invoke(sent, totalBytes, item, TransferStage.CANCELLED, 0L, null)
                                return@withContext SendOutcome.Cancelled
                            }

                            if (!session.isConnected || !tcpSender.isConnected) {
                                val reason = if (!session.isConnected) "WebSocket session lost" else "TCP data socket lost"
                                VeyroLog.e(TAG) { "[$transferId] $reason while streaming chunk #$chunkIndex (sent $sent of $totalBytes bytes)" }
                                onProgress?.invoke(sent, totalBytes, item, TransferStage.FAILED, 0L, null)
                                return@withContext SendOutcome.Failed("Connection lost during transfer: $reason")
                            }

                            val offset = ChunkMath.offsetOf(chunkIndex, CHUNK_SIZE)
                            val length = ChunkMath.lengthOf(chunkIndex, source.length(), CHUNK_SIZE)
                            val data = ByteArray(length)
                            metrics.recordSourceRead(length.toLong()) {
                                sourceRaf.seek(offset)
                                sourceRaf.readFully(data)
                            }

                            try {
                                val isLastChunk = chunkIndex == needed.last()
                                val shouldFlush = (chunkIndex % 4 == 0) || isLastChunk
                                tcpSender.sendChunk(
                                    transferId = transferId,
                                    itemIndex = item.index,
                                    chunkIndex = chunkIndex,
                                    data = data,
                                    flush = shouldFlush,
                                    metrics = metrics,
                                )
                            } catch (t: Throwable) {
                                if (t is kotlinx.coroutines.CancellationException) throw t
                                VeyroLog.e(TAG, t) { "[$transferId] Failed to send TCP chunk #$chunkIndex: ${t.message}" }
                                onProgress?.invoke(sent, totalBytes, item, TransferStage.FAILED, 0L, null)
                                return@withContext SendOutcome.Failed("TCP socket error: ${t.message}")
                            }

                            sent += data.size
                            val now = clock()
                            speedCalculator.record(now, sent)
                            val bps = speedCalculator.speedBytesPerSecond(now)
                            val etaSec = speedCalculator.estimateEtaSeconds((totalBytes - sent).coerceAtLeast(0L), now)

                            if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                                lastProgress = now
                                emit(transferId, item, sent, totalBytes, bps)
                                onProgress?.invoke(sent, totalBytes, item, TransferStage.TRANSFERRING, bps, etaSec)
                            }
                        }
                    }

                    // Verify file digest and send ItemEnd
                    val hash = metrics.recordSha256 { ChunkMath.hashFile(source) }
                    VeyroLog.i(TAG) { "[$transferId] Item #${item.index} stream complete, sent ItemEnd (sha256=$hash), awaiting ItemResult..." }
                    onProgress?.invoke(sent, totalBytes, item, TransferStage.VERIFYING, 0L, null)

                    try {
                        tcpSender.sendItemEnd(transferId, item.index, hash, metrics)
                        session.send(LinkMessage.ItemEnd(transferId, item.index, hash))
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        onProgress?.invoke(sent, totalBytes, item, TransferStage.FAILED, 0L, null)
                        return@withContext SendOutcome.Failed("Connection lost sending ItemEnd")
                    }

                    val itemResult = mailbox.awaitItemResult(item.index, STEP_TIMEOUT_MS)
                    if (itemResult == null) {
                        val reason = if (!session.isConnected) "Connection lost waiting for verification" else "Receiver timed out verifying item"
                        VeyroLog.e(TAG) { "[$transferId] Item #${item.index} failed: $reason" }
                        onProgress?.invoke(sent, totalBytes, item, TransferStage.FAILED, 0L, null)
                        return@withContext SendOutcome.Failed(reason)
                    }

                    val isOk = itemResult.ok
                    VeyroLog.i(TAG) { "[$transferId] Item #${item.index} verified on receiver: ok=$isOk, error=${itemResult.error}" }

                    results += ItemResult(
                        itemIndex = item.index,
                        relativePath = item.relativePath,
                        bytesWritten = item.sizeBytes,
                        verification = if (isOk) VerificationResult.VERIFIED else VerificationResult.HASH_MISMATCH,
                        errorMessage = itemResult.error,
                    )
                }

                // 5. Complete transfer
                val allOk = results.isNotEmpty() && results.all { it.verification == VerificationResult.VERIFIED }
                VeyroLog.i(TAG) { "[$transferId] [tcp-sender:completion] All items processed. Sending TransferEnd (allOk=$allOk, sentBytes=$sent, route=$activeRoute)" }

                tcpSender.sendTransferEnd(transferId, completed = allOk, metrics = metrics)
                try {
                    session.send(LinkMessage.TransferEnd(transferId, completed = allOk))
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    VeyroLog.w(TAG, t) { "[$transferId] Failed to send TransferEnd: ${t.message}" }
                }

                // Graceful half-close drain so receiver consumes TransferEnd before connection closes
                tcpSender.gracefulShutdown(2000L)
                metrics.markTransferComplete()
                metrics.logBreakdown()

                files.lastOrNull()?.let { lastItem ->
                    onProgress?.invoke(sent, totalBytes, lastItem, if (allOk) TransferStage.COMPLETED else TransferStage.FAILED, 0L, null)
                }

                SendOutcome.Done(results, route = activeRoute)
            } finally {
                cancelWatcher.cancel()
            }
        } finally {
            activeSenders.remove(transferId)
            activeSessions.remove(transferId)
            activeJobs.remove(transferId)
            tcpSender.close("send finished")
            runCatching { wifiDirectManager?.closeP2pLink() }
            session.closeMailbox(transferId)
        }
    }

    // ── Receive ────────────────────────────────────────────────────────────────────────────

    public suspend fun receive(
        session: PeerConnection,
        destinationDir: File,
        authorize: suspend (LinkMessage.TransferOffer) -> Boolean,
    ): ReceiveResult = receive(session, destinationDir, null, authorize, null)

    public suspend fun receive(
        session: PeerConnection,
        destinationDir: File,
        publisher: TransferStoragePublisher? = null,
        authorize: suspend (LinkMessage.TransferOffer) -> Boolean = { true },
        onProgress: ((bytesReceived: Long, totalBytes: Long, item: TransferItem?, stage: TransferStage, speedBps: Long, etaSec: Long?) -> Unit)? = null,
    ): ReceiveResult = withContext(dispatchers.io) {
        val offerEnvelope = session.receiveOffer()
        val offer = offerEnvelope.payload as LinkMessage.TransferOffer
        val transferId = offer.transferId
        val mailbox = session.mailbox(transferId)

        VeyroLog.i(TAG) { "[$transferId] Receiver received TransferOffer from ${offer.senderName} (${offer.items.size} items, ${offer.totalBytes} bytes)" }

        onProgress?.invoke(0L, offer.totalBytes, offer.items.firstOrNull()?.toDomain(), TransferStage.AWAITING_CONSENT, 0L, null)

        val allowed = authorize(offer)
        if (!allowed) {
            VeyroLog.w(TAG) { "[$transferId] Receiver declined offer from ${offer.senderName}" }
            session.send(
                LinkMessage.TransferDecision(
                    transferId = transferId,
                    accept = false,
                    reason = "User declined",
                ),
            )
            session.closeMailbox(transferId)
            onProgress?.invoke(0L, offer.totalBytes, null, TransferStage.FAILED, 0L, null)
            return@withContext ReceiveResult.EMPTY
        }

        val hasTcpRoute = offer.preferredRoutes.any {
            it == TransferRoute.WIFI_DIRECT.name || it == TransferRoute.LAN_TCP.name
        }

        // Check resume chunks
        val resume = HashMap<Int, List<Int>>()
        for (item in offer.items) {
            val part = runCatching {
                PartFile.open(
                    destinationDir = destinationDir,
                    relativePath = item.relPath,
                    sizeBytes = item.size,
                    chunkSize = CHUNK_SIZE,
                )
            }.getOrNull()
            if (part != null) {
                val missing = part.receivedChunks.missing()
                if (missing.size < part.chunkCount) resume[item.index] = missing
            }
        }

        val startedAt = clock()

        if (!hasTcpRoute) {
            VeyroLog.i(TAG) { "[$transferId] Peer offered WebSocket-only transfer (preferredRoutes=${offer.preferredRoutes})" }
            session.send(
                LinkMessage.TransferDecision(
                    transferId = transferId,
                    accept = true,
                    resume = resume.takeIf { it.isNotEmpty() },
                    route = TransferRoute.LAN_TCP.name,
                ),
            )
            return@withContext receiveOverWebSocket(
                session = session,
                mailbox = mailbox,
                offer = offer,
                destinationDir = destinationDir,
                publisher = publisher,
                startedAt = startedAt,
                onProgress = onProgress,
            )
        }

        destinationDir.mkdirs()
        val tcpReceiver = TcpDataReceiver(dispatchers)
        val tcpPort = tcpReceiver.bind()
        val authToken = UUID.randomUUID().toString()
        VeyroLog.i(TAG) {
            "[$transferId] [route-neg:step1] Receiver created and bound TCP listener on port $tcpPort (bound to ${tcpReceiver.bindAddress?.hostAddress ?: "0.0.0.0"})"
        }

        var selectedRoute = TransferRoute.LAN_TCP
        var advertisedTcpHost: String? = null
        var advertisedFallbackHost: String? = null
        var p2pSsid: String? = null
        var p2pPassphrase: String? = null

        val lanAddress = resolveReceiverLanAddress(session)
        val fallbackLan = if (lanAddress != null) resolveReceiverFallbackLanAddress(session, lanAddress) else null

        if (wifiDirectManager?.isAvailable == true && TransferRoute.WIFI_DIRECT.name in offer.preferredRoutes) {
            VeyroLog.i(TAG) { "[$transferId] Receiver preparing Wi-Fi Direct P2P link..." }
            val p2pLink = runCatching { wifiDirectManager.prepareP2pLink(isInitiator = false) }.getOrNull()
            if (p2pLink != null && (isValidConcreteIpv4(p2pLink.localAddress) || p2pLink.localAddress == "127.0.0.1")) {
                selectedRoute = TransferRoute.WIFI_DIRECT
                advertisedTcpHost = p2pLink.localAddress
                advertisedFallbackHost = lanAddress
                p2pSsid = p2pLink.networkName
                p2pPassphrase = p2pLink.passphrase
                VeyroLog.i(TAG) {
                    "[$transferId] [p2p:endpoints] Receiver Wi-Fi Direct ready at $advertisedTcpHost:$tcpPort (fallback: $advertisedFallbackHost, ssid=$p2pSsid)"
                }
            } else {
                VeyroLog.w(TAG) { "[$transferId] [p2p:fallback] Receiver Wi-Fi Direct preparation failed; falling back to LAN TCP" }
            }
        }

        if (selectedRoute == TransferRoute.LAN_TCP) {
            if (lanAddress == null) {
                VeyroLog.e(TAG) {
                    "[$transferId] [route-neg:failed] Receiver cannot determine a reachable LAN IPv4 address (session.localHost=${session.localHost}, session.remoteHost=${session.remoteHost}). Failing route negotiation."
                }
                tcpReceiver.close()
                session.send(
                    LinkMessage.TransferDecision(
                        transferId = transferId,
                        accept = false,
                        reason = "Receiver has no reachable LAN IPv4 address",
                    ),
                )
                session.closeMailbox(transferId)
                onProgress?.invoke(0L, offer.totalBytes, null, TransferStage.FAILED, 0L, null)
                return@withContext ReceiveResult.EMPTY
            }
            advertisedTcpHost = lanAddress
            advertisedFallbackHost = fallbackLan
            VeyroLog.i(TAG) {
                "[$transferId] [route-neg:step2] Receiver resolved LAN address: $advertisedTcpHost (fallback: $advertisedFallbackHost) on port $tcpPort"
            }
        }

        VeyroLog.i(TAG) {
            "[$transferId] [route-neg:step3] Receiver sending TransferDecision: accept=true, route=$selectedRoute, port=$tcpPort, tcpHost=$advertisedTcpHost, fallbackHost=$advertisedFallbackHost, ssid=$p2pSsid"
        }

        session.send(
            LinkMessage.TransferDecision(
                transferId = transferId,
                accept = true,
                resume = resume.takeIf { it.isNotEmpty() },
                route = selectedRoute.name,
                tcpHost = advertisedTcpHost,
                tcpPort = tcpPort,
                fallbackHost = advertisedFallbackHost,
                authToken = authToken,
                p2pSsid = p2pSsid,
                p2pPassphrase = p2pPassphrase,
            ),
        )

        val speedCalculator = RollingSpeedCalculator()
        val totalBytes = offer.totalBytes
        var lastProgress = 0L

        val domainItems = offer.items.map { it.toDomain() }

        val currentJob = coroutineContext[Job]
        if (currentJob != null) activeJobs[transferId] = currentJob
        activeReceivers[transferId] = tcpReceiver
        activeSessions[transferId] = session

        val metrics = TransferThroughputMetrics(
            transferId = transferId,
            role = "RECEIVER",
            route = selectedRoute,
            chunkSize = CHUNK_SIZE,
            destinationStorageType = if (publisher != null) "MEDIASTORE" else "APP_INTERNAL",
        )

        // Monitor control messages (Cancel and TransferEnd) on WebSocket in background
        val controlCollector = launch {
            while (session.isConnected) {
                val msg = mailbox.payloadChannel.receiveCatching().getOrNull()
                when (msg) {
                    is LinkMessage.Cancel -> {
                        VeyroLog.w(TAG) { "[$transferId] Cancel received on WebSocket: ${msg.reason}" }
                        tcpReceiver.notifyCancel(msg.reason)
                        break
                    }
                    is LinkMessage.TransferEnd -> {
                        VeyroLog.i(TAG) { "[$transferId] TransferEnd received on WebSocket (completed=${msg.completed})" }
                        tcpReceiver.notifyTransferEnd(msg.completed)
                        break
                    }
                    else -> Unit
                }
            }
        }

        try {
            val tcpOutcome = tcpReceiver.acceptAndReceive(
                transferId = transferId,
                expectedAuthToken = authToken,
                expectedPeerDeviceId = session.peerDeviceId,
                offerItems = domainItems,
                destinationDir = destinationDir,
                chunkSize = CHUNK_SIZE,
                publisher = publisher,
                metrics = metrics,
                onProgress = { bytesWritten, total, itemIdx ->
                    val now = clock()
                    speedCalculator.record(now, bytesWritten)
                    val bps = speedCalculator.speedBytesPerSecond(now)
                    val etaSec = speedCalculator.estimateEtaSeconds((total - bytesWritten).coerceAtLeast(0L), now)

                    if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                        lastProgress = now
                        val currentItem = domainItems.firstOrNull { it.index == itemIdx }
                        if (currentItem != null) {
                            emit(transferId, currentItem, bytesWritten, total, bps)
                            onProgress?.invoke(bytesWritten, total, currentItem, TransferStage.TRANSFERRING, bps, etaSec)
                        }
                        // Report genuine receiver-side progress over WebSocket
                        runCatching {
                            session.send(
                                LinkMessage.TransferProgressNotice(
                                    transferId = transferId,
                                    bytesReceived = bytesWritten,
                                    totalBytes = total,
                                    currentItemIndex = itemIdx,
                                    speedBps = bps,
                                ),
                            )
                        }
                    }
                },
                onItemVerified = { itemResult ->
                    VeyroLog.i(TAG) { "[$transferId] [tcp-receiver:item-result-send] Sending ItemResult for #${itemResult.itemIndex} over WebSocket (ok=${itemResult.isVerified})" }
                    session.send(
                        LinkMessage.ItemResult(
                            transferId = transferId,
                            index = itemResult.itemIndex,
                            ok = itemResult.isVerified,
                            error = itemResult.errorMessage,
                        ),
                    )
                },
            )

            controlCollector.cancel()

            when (tcpOutcome) {
                is TcpReceiveOutcome.Completed -> {
                    val allOk = tcpOutcome.results.isNotEmpty() && tcpOutcome.results.all { it.isVerified }
                    VeyroLog.i(TAG) {
                        "[$transferId] TCP receive completed (${tcpOutcome.results.size} items, allOk=$allOk, route=${tcpOutcome.route})"
                    }
                    onProgress?.invoke(
                        totalBytes, totalBytes, null,
                        if (allOk) TransferStage.COMPLETED else TransferStage.FAILED, 0L, null,
                    )
                    ReceiveResult(
                        transferId = transferId,
                        senderName = offer.senderName,
                        totalBytes = totalBytes,
                        startedAtEpochMillis = startedAt,
                        items = tcpOutcome.results,
                        route = tcpOutcome.route,
                    )
                }

                is TcpReceiveOutcome.Cancelled -> {
                    VeyroLog.w(TAG) { "[$transferId] TCP transfer cancelled: ${tcpOutcome.reason}" }
                    onProgress?.invoke(0L, totalBytes, null, TransferStage.CANCELLED, 0L, null)
                    ReceiveResult.EMPTY
                }

                is TcpReceiveOutcome.Failed -> {
                    if (cancelled || mailbox.isCancelled) {
                        VeyroLog.i(TAG) { "[$transferId] TCP receiver interruption was due to cancellation" }
                        onProgress?.invoke(0L, totalBytes, null, TransferStage.CANCELLED, 0L, null)
                        ReceiveResult.EMPTY
                    } else {
                        VeyroLog.e(TAG) { "[$transferId] TCP transfer failed: ${tcpOutcome.reason}" }
                        onProgress?.invoke(0L, totalBytes, null, TransferStage.FAILED, 0L, null)
                        throw IOException("Transfer failed: ${tcpOutcome.reason}")
                    }
                }
            }
        } finally {
            controlCollector.cancel()
            activeReceivers.remove(transferId)
            activeSessions.remove(transferId)
            activeJobs.remove(transferId)
            tcpReceiver.close("receive finished")
            runCatching { wifiDirectManager?.closeP2pLink() }
            session.closeMailbox(transferId)
        }
    }

    private suspend fun receiveOverWebSocket(
        session: PeerConnection,
        mailbox: TransferMailbox,
        offer: LinkMessage.TransferOffer,
        destinationDir: File,
        publisher: TransferStoragePublisher?,
        startedAt: Long,
        onProgress: ((bytesReceived: Long, totalBytes: Long, item: TransferItem?, stage: TransferStage, speedBps: Long, etaSec: Long?) -> Unit)?,
    ): ReceiveResult {
        val transferId = offer.transferId
        val speedCalculator = RollingSpeedCalculator()
        val totalBytes = offer.totalBytes
        val domainItems = offer.items.map { it.toDomain() }
        val parts = mutableMapOf<Int, PartFile>()
        val results = mutableListOf<ItemResult>()
        var lastProgress = 0L
        var totalBytesReceived = 0L

        suspend fun processMessage(msg: LinkMessage): Boolean {
            when (msg) {
                is LinkMessage.ItemBegin -> {
                    val domainItem = domainItems.firstOrNull { it.index == msg.index }
                    if (domainItem != null) {
                        val part = PartFile.open(
                            destinationDir = destinationDir,
                            relativePath = domainItem.relativePath,
                            sizeBytes = msg.size,
                            chunkSize = msg.chunkSize,
                        )
                        parts[msg.index] = part
                    }
                }

                is LinkMessage.Chunk -> {
                    val part = parts[msg.index]
                    if (part != null) {
                        val data = Base64.getDecoder().decode(msg.dataB64)
                        runCatching { part.writeChunk(msg.chunkIndex, data) }
                        totalBytesReceived += data.size

                        val now = clock()
                        speedCalculator.record(now, totalBytesReceived)
                        val bps = speedCalculator.speedBytesPerSecond(now)
                        val etaSec = speedCalculator.estimateEtaSeconds((totalBytes - totalBytesReceived).coerceAtLeast(0L), now)

                        if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                            lastProgress = now
                            val currentItem = domainItems.firstOrNull { it.index == msg.index }
                            if (currentItem != null) {
                                emit(transferId, currentItem, totalBytesReceived, totalBytes, bps)
                                onProgress?.invoke(totalBytesReceived, totalBytes, currentItem, TransferStage.TRANSFERRING, bps, etaSec)
                            }
                        }
                    }
                }

                is LinkMessage.ItemEnd -> {
                    val part = parts[msg.index]
                    val domainItem = domainItems.firstOrNull { it.index == msg.index }
                    if (part != null && domainItem != null) {
                        val verifyResult = part.verifyAndCommit(msg.sha256)
                        val ok = verifyResult == VerificationResult.VERIFIED
                        var finalVerification = verifyResult
                        var errMsg: String? = null
                        var destUri: String? = null

                        if (ok && part.committedFile != null) {
                            if (publisher != null) {
                                val pubRes = publisher.publish(
                                    sourceFile = part.committedFile!!,
                                    relativePath = domainItem.relativePath,
                                    mimeType = domainItem.mimeType,
                                    sizeBytes = domainItem.sizeBytes,
                                )
                                when (pubRes) {
                                    is StoragePublishResult.Success -> {
                                        destUri = pubRes.destinationUri
                                    }
                                    is StoragePublishResult.Failed -> {
                                        finalVerification = VerificationResult.WRITE_FAILED
                                        errMsg = "PUBLICATION_FAILED: ${pubRes.reason}"
                                    }
                                }
                            } else {
                                destUri = part.committedFile?.toURI()?.toString()
                            }
                        }

                        val itemRes = ItemResult(
                            itemIndex = msg.index,
                            relativePath = domainItem.relativePath,
                            bytesWritten = if (finalVerification == VerificationResult.VERIFIED) domainItem.sizeBytes else 0L,
                            verification = finalVerification,
                            errorMessage = errMsg,
                            destinationUri = destUri,
                        )
                        results.add(itemRes)

                        session.send(
                            LinkMessage.ItemResult(
                                transferId = transferId,
                                index = msg.index,
                                ok = finalVerification == VerificationResult.VERIFIED,
                                error = itemRes.errorMessage,
                            ),
                        )
                    }
                }

                is LinkMessage.TransferEnd -> {
                    return true
                }

                is LinkMessage.Cancel -> {
                    onProgress?.invoke(0L, totalBytes, null, TransferStage.CANCELLED, 0L, null)
                    cancelled = true
                    return true
                }

                else -> Unit
            }
            return false
        }

        try {
            var isDone = false
            while (!isDone && session.isConnected) {
                val res = mailbox.payloadChannel.receiveCatching()
                val msg = res.getOrNull()
                if (msg == null) {
                    onProgress?.invoke(0L, totalBytes, null, TransferStage.FAILED, 0L, null)
                    throw IOException("WebSocket connection closed prematurely during transfer")
                }
                isDone = processMessage(msg)
            }

            if (!isDone) {
                onProgress?.invoke(0L, totalBytes, null, TransferStage.FAILED, 0L, null)
                throw IOException("WebSocket connection closed before TransferEnd")
            }

            if (cancelled) {
                return ReceiveResult.EMPTY
            }

            val allOk = results.isNotEmpty() && results.all { it.isVerified }
            onProgress?.invoke(
                totalBytes, totalBytes, null,
                if (allOk) TransferStage.COMPLETED else TransferStage.FAILED, 0L, null,
            )

            return ReceiveResult(
                transferId = transferId,
                senderName = offer.senderName,
                totalBytes = totalBytes,
                startedAtEpochMillis = startedAt,
                items = results,
                route = TransferRoute.LAN_TCP,
            )
        } finally {
            session.closeMailbox(transferId)
        }
    }

    private suspend fun emit(
        transferId: String,
        item: TransferItem,
        done: Long,
        total: Long,
        bps: Long,
    ) {
        _progress.tryEmit(
            TransferProgress(
                transferId = transferId,
                currentItemIndex = item.index,
                currentItemName = item.fileName,
                bytesTransferred = done,
                totalBytes = if (total > 0) total else item.sizeBytes,
                bytesPerSecond = bps,
                state = TransferState.TRANSFERRING,
            ),
        )
    }

    public sealed interface SendOutcome {
        public data class Done(
            val results: List<ItemResult>,
            val route: TransferRoute = TransferRoute.LAN_TCP,
        ) : SendOutcome
        public data object Declined : SendOutcome
        public data object Cancelled : SendOutcome
        public data class Failed(val reason: String) : SendOutcome
    }
}

/** Legacy alias for backwards compatibility. */
public typealias LinkTransferRunner = TransferRunner
