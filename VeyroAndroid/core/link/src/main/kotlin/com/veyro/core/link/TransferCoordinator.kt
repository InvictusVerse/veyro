package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.ActiveTransfer
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.model.Transfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferProgress
import com.veyro.core.model.TransferStage
import com.veyro.core.model.TransferState
import com.veyro.core.transfer.ItemResult
import com.veyro.core.transfer.TransferStoragePublisher
import com.veyro.core.protocol.RelativePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The single surface the Send and Receive screens talk to.
 *
 * It ties the two halves together: [ConnectionManager] owns the connection and the session, and
 * [TransferRunner] moves the bytes. The receiver side is automatic - [ConnectionManager]'s session
 * driver runs a receive loop on every inbound connection - while the sender side is driven by
 * [sendTo], which dials, waits for the connection, and streams.
 *
 * No screen ever sees a `PeerConnection` or a `LinkMessage`; it sees progress, offers and outcomes.
 * That is what keeps the UI testable against a fake and the protocol out of the UI.
 */
public class TransferCoordinator(
    private val connectionManager: ConnectionManager,
    private val runner: TransferRunner,
    private val dispatchers: AppDispatchers,
    private val stagingDir: File,
    private val destinationProvider: suspend () -> File,
    private val localName: suspend () -> String,
    private val endpointsFor: suspend (DeviceId) -> List<Endpoint>,
    private val autoAcceptFor: suspend (DeviceId) -> Boolean,
    private val discoverEndpoints: (suspend (DeviceId, Long) -> List<Endpoint>)? = null,
    private val storagePublisher: TransferStoragePublisher? = null,
    private val onTransferCompleted: (suspend (Transfer) -> Unit)? = null,
) {

    public companion object {
        private const val TAG = "TransferCoordinator"
        private const val OFFER_TIMEOUT_MS = 60_000L
        private const val CONNECT_WAIT_MS = 15_000L
        private const val DISCOVERY_WAIT_MS = 5_000L
        private const val RECEIVE_RETRY_DELAY_MS = 250L
    }

    public val progress: Flow<TransferProgress> = runner.progress

    private val _incomingOffers = MutableSharedFlow<IncomingOffer>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    public val incomingOffers: Flow<IncomingOffer> = _incomingOffers.asSharedFlow()

    private val _pendingOffer = MutableStateFlow<IncomingOffer?>(null)
    public val pendingOffer: StateFlow<IncomingOffer?> = _pendingOffer.asStateFlow()

    private val _activeTransfer = MutableStateFlow<ActiveTransfer?>(null)
    public val activeTransfer: StateFlow<ActiveTransfer?> = _activeTransfer.asStateFlow()

    public fun clearActiveTransfer() {
        _activeTransfer.value = null
    }

    private val mutex = Mutex()
    private val pending = HashMap<String, PendingDecision>()
    private val activeTransfers = ConcurrentHashMap.newKeySet<String>()
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

    private class PendingDecision {
        @Volatile var answered = false
        @Volatile var accepted = false
    }

    /** Sessions that already have a receive loop; one loop per session, never two. */
    private val drivenSessions: MutableSet<PeerConnection> =
        Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))

    init {
        // The receiver half. Every authenticated session gets a receive loop that waits for an
        // offer and routes the authorization question to the UI - whoever dialled, because the PC
        // sends files over the phone's own persistent session too (ADR-035). This coexists with
        // the phone's own sends on the same session: receiveOffer() only ever yields incoming
        // TransferOffers, while a send waits on its own transfer's mailbox.
        connectionManager.sessionDriver = driver@{ connection, inbound ->
            if (!drivenSessions.add(connection)) {
                VeyroLog.w(TAG) { "Receiver session driver already running for ${connection.peerDeviceId}; not starting a second" }
                return@driver
            }
            try {
                receiveLoop(connection, inbound)
            } finally {
                drivenSessions.remove(connection)
            }
        }
    }

    private suspend fun receiveLoop(connection: PeerConnection, inbound: Boolean) {
        VeyroLog.i(TAG) { "Receiver session driver started for ${connection.peerDeviceId} (inbound=$inbound)" }
        // The transfer this loop last authorized; only that one is this loop's to mark failed.
        var handledId: String? = null
        while (connection.isConnected) {
            val results = runCatching {
                runner.receive(
                    session = connection,
                    destinationDir = destinationProvider().also { it.mkdirs() },
                    authorize = { offer ->
                        handledId = offer.transferId
                        authorize(connection, offer)
                    },
                    publisher = storagePublisher,
                    onProgress = { bytesRecv, totalBytes, item, stage, speedBps, etaSec ->
                        val now = System.currentTimeMillis()
                        val started = _activeTransfer.value?.startedAtEpochMillis ?: now
                        val count = _activeTransfer.value?.totalFiles ?: 1
                        val currentIdx = item?.index ?: (_activeTransfer.value?.currentFileIndex ?: 0)
                        val name = item?.fileName ?: (_activeTransfer.value?.currentFileName ?: "")
                        _activeTransfer.value = _activeTransfer.value?.copy(
                            currentFileName = name,
                            currentFileIndex = currentIdx,
                            bytesTransferred = bytesRecv,
                            totalBytes = totalBytes,
                            bytesPerSecond = speedBps,
                            etaSeconds = etaSec,
                            stage = stage,
                            statusText = when (stage) {
                                TransferStage.AWAITING_CONSENT -> "Awaiting confirmation..."
                                TransferStage.TRANSFERRING -> "Receiving $name (${currentIdx + 1}/$count)"
                                TransferStage.VERIFYING -> "Verifying SHA-256 integrity..."
                                TransferStage.PUBLISHING -> "Saving to MediaStore..."
                                TransferStage.COMPLETED -> "Transfer completed"
                                TransferStage.CANCELLED -> "Transfer cancelled"
                                TransferStage.FAILED -> "Transfer failed"
                                else -> stage.name
                            },
                            elapsedMillis = (now - started).coerceAtLeast(0L),
                        )
                    },
                )
            }
            val failure = results.exceptionOrNull()
            if (failure != null) {
                if (failure is CancellationException) break
                activeTransfers.clear()
                val current = _activeTransfer.value
                if (current != null && current.transferId == handledId && current.stage != TransferStage.COMPLETED) {
                    VeyroLog.w(TAG, failure) { "Receive cycle ended with error: ${failure.message}" }
                    _activeTransfer.value = current.copy(
                        stage = TransferStage.FAILED,
                        statusText = "Receive failed: ${failure.message}",
                        errorMessage = failure.message,
                    )
                }
                // One failed transfer does not end the session (04-PROTOCOL.md 6.4): keep
                // serving offers for as long as the connection is up.
                if (connection.isConnected) {
                    // Paced, so a failure that repeats before any offer cannot spin the loop.
                    kotlinx.coroutines.delay(RECEIVE_RETRY_DELAY_MS)
                    continue
                }
                break
            }
            val receiveResult = results.getOrNull()
            if (receiveResult.isNullOrEmpty() && !connection.isConnected) {
                val current = _activeTransfer.value
                if (current != null && current.transferId == handledId && !current.isTerminal) {
                    _activeTransfer.value = _activeTransfer.value?.copy(
                        stage = TransferStage.FAILED,
                        statusText = "Transfer interrupted: Connection lost",
                        errorMessage = "Connection lost before transfer completed",
                    )
                }
                break
            }
            activeTransfers.clear()
            VeyroLog.i(TAG) { "Receive cycle completed (${receiveResult?.size ?: 0} items processed); waiting for next offer" }

            if (!receiveResult.isNullOrEmpty()) {
                val peerId = connection.peerDeviceId?.let { DeviceId.parseOrNull(it) ?: DeviceId(it) }
                    ?: DeviceId("0000000000000000")
                val allOk = receiveResult.all { it.isVerified }
                val anyOk = receiveResult.any { it.isVerified }
                val finalState = when {
                    allOk -> TransferState.COMPLETED
                    anyOk -> TransferState.COMPLETED_WITH_ERRORS
                    else -> TransferState.FAILED
                }
                val primaryUri = receiveResult.firstOrNull { it.destinationUri != null }?.destinationUri

                _activeTransfer.value = _activeTransfer.value?.copy(
                    stage = TransferStage.RECORDING_HISTORY,
                    statusText = "Saving transfer history...",
                )

                val transferRecord = Transfer(
                    id = receiveResult.transferId,
                    peerDeviceId = peerId,
                    peerDisplayName = receiveResult.senderName,
                    direction = TransferDirection.RECEIVE,
                    items = receiveResult.map { itemRes ->
                        TransferItem(
                            index = itemRes.itemIndex,
                            relativePath = itemRes.relativePath,
                            sizeBytes = itemRes.bytesWritten,
                            lastModifiedEpochMillis = System.currentTimeMillis(),
                            isDirectory = false,
                            destinationUri = itemRes.destinationUri,
                            verification = itemRes.verification,
                        )
                    },
                    totalBytes = receiveResult.sumOf { it.bytesWritten },
                    state = finalState,
                    startedAtEpochMillis = receiveResult.startedAtEpochMillis,
                    finishedAtEpochMillis = System.currentTimeMillis(),
                    destinationUri = primaryUri,
                )

                val wasNewlyCompleted = completedTransfers.add(receiveResult.transferId)

                if (wasNewlyCompleted && onTransferCompleted != null) {
                    try {
                        onTransferCompleted.invoke(transferRecord)
                        VeyroLog.i(TAG) {
                            "[${receiveResult.transferId}] Persisted history record: peer=${peerId.value} (${receiveResult.senderName}), state=$finalState, items=${receiveResult.size}, bytes=${transferRecord.totalBytes}, uri=$primaryUri"
                        }
                    } catch (t: Throwable) {
                        VeyroLog.e(TAG, t) {
                            "[${receiveResult.transferId}] Failed to persist history record: ${t.message}"
                        }
                    }
                }

                _activeTransfer.value = _activeTransfer.value?.copy(
                    stage = if (allOk) TransferStage.COMPLETED else TransferStage.FAILED,
                    statusText = if (allOk) "Received ${receiveResult.size} file(s) (Verified)" else "Transfer completed with errors",
                    destinationUri = primaryUri,
                    isVerified = allOk,
                    bytesTransferred = receiveResult.sumOf { it.bytesWritten },
                    route = receiveResult.route,
                )
            }
        }
        activeTransfers.clear()
        val terminalCheck = _activeTransfer.value
        // Only the receive this loop ran: a send on another session is not this loop's to fail.
        if (terminalCheck != null && terminalCheck.transferId == handledId && !terminalCheck.isTerminal) {
            _activeTransfer.value = terminalCheck.copy(
                stage = TransferStage.FAILED,
                statusText = "Transfer ended: Connection closed",
                errorMessage = "Connection closed",
            )
        }
        VeyroLog.i(TAG) { "Receiver session driver terminated for ${connection.peerDeviceId}" }
    }

    public suspend fun destinationDirectory(): File = destinationProvider().also { it.mkdirs() }

    // ── Sending ────────────────────────────────────────────────────────────────────────────

    /**
     * Sends [files] to a paired device.
     *
     * Dials, waits for the connection to come up, then streams. Staging copies each selection into the
     * cache first, because a picked `content://` file has no path the runner can open directly.
     */
    private sealed interface SessionResolution {
        data class Ready(val session: PeerConnection) : SessionResolution
        data class Failed(val reason: String) : SessionResolution
    }

    /**
     * Resolves an authenticated session for [peerDeviceId]:
     * 1. Reuses an active, connected, authenticated session if one exists.
     * 2. Otherwise uses current endpoints from [endpointsFor].
     * 3. If no endpoints exist, triggers discovery and waits bounded time via [discoverEndpoints].
     * 4. Dials and connects across endpoints.
     * 5. Retries stale endpoints/connection failure safely with fresh discovery.
     */
    private suspend fun resolveOrConnectSession(
        transferId: String,
        peerDeviceId: DeviceId,
        peerName: String,
    ): SessionResolution {
        // 1. Reuse a valid, connected, authenticated session for the exact target device ID
        val current = connectionManager.activeConnection.value
        if (current != null && current.isConnected && current.peerDeviceId == peerDeviceId.value) {
            VeyroLog.i(TAG) { "[$transferId] Reusing active authenticated session with $peerName" }
            return SessionResolution.Ready(current)
        }

        // 2. Otherwise use a currently valid discovered endpoint
        var endpoints = endpointsFor(peerDeviceId)

        // 3. If no endpoint exists, trigger discovery and wait for a bounded period
        if (endpoints.isEmpty() && discoverEndpoints != null) {
            VeyroLog.i(TAG) { "[$transferId] No immediate endpoints for $peerName; discovering (wait ${DISCOVERY_WAIT_MS}ms)..." }
            endpoints = discoverEndpoints.invoke(peerDeviceId, DISCOVERY_WAIT_MS)
        }

        if (endpoints.isEmpty()) {
            VeyroLog.w(TAG) { "[$transferId] Target $peerName ($peerDeviceId) has no known endpoints after discovery" }
            return SessionResolution.Failed("$peerName is offline or unreachable")
        }

        // 4. If discovery yields an endpoint, attempt a connection
        VeyroLog.i(TAG) { "[$transferId] Dialing $peerName across ${endpoints.size} endpoint(s)" }
        var connected = connectionManager.connect(peerDeviceId.value, peerName, endpoints)

        // 5. Retry stale endpoints or connection failures safely, with bounded attempts
        if (!connected && discoverEndpoints != null) {
            VeyroLog.w(TAG) { "[$transferId] Connection failed on initial endpoints; triggering fresh discovery retry" }
            endpoints = discoverEndpoints.invoke(peerDeviceId, DISCOVERY_WAIT_MS)
            if (endpoints.isNotEmpty()) {
                VeyroLog.i(TAG) { "[$transferId] Retrying connection to $peerName across ${endpoints.size} fresh endpoint(s)" }
                connected = connectionManager.connect(peerDeviceId.value, peerName, endpoints, forceReconnect = true)
            }
        }

        if (!connected) {
            VeyroLog.w(TAG) { "[$transferId] Connection manager could not connect to $peerName" }
            return SessionResolution.Failed("Could not connect to $peerName")
        }

        val session = withTimeoutOrNull(CONNECT_WAIT_MS) {
            connectionManager.activeConnection.filterNotNull().first {
                it.isConnected && (it.peerDeviceId == null || it.peerDeviceId == peerDeviceId.value)
            }
        } ?: run {
            VeyroLog.w(TAG) { "[$transferId] Timed out waiting for active session with $peerName" }
            return SessionResolution.Failed("$peerName did not answer")
        }

        return SessionResolution.Ready(session)
    }

    public suspend fun sendTo(
        peerDeviceId: DeviceId,
        peerName: String,
        files: List<OutgoingFile>,
    ): SendResult = withContext(dispatchers.io) {
        val transferId = UUID.randomUUID().toString().take(8)
        VeyroLog.i(TAG) { "[$transferId] User initiated transfer to $peerName ($peerDeviceId) with ${files.size} file(s)" }
        if (files.isEmpty()) return@withContext SendResult.Failed("Nothing selected")

        val totalSourceBytes = files.sumOf { it.sizeBytes }
        VeyroLog.i(TAG) { "[$transferId] Source files: ${files.size}, total size: $totalSourceBytes bytes" }

        val startedAt = System.currentTimeMillis()
        _activeTransfer.value = ActiveTransfer(
            transferId = transferId,
            direction = TransferDirection.SEND,
            peerDeviceId = peerDeviceId,
            peerDisplayName = peerName,
            currentFileName = files.firstOrNull()?.effectiveName ?: "",
            currentFileIndex = 0,
            totalFiles = files.size,
            bytesTransferred = 0L,
            totalBytes = totalSourceBytes,
            bytesPerSecond = 0L,
            etaSeconds = null,
            stage = TransferStage.PREPARING,
            statusText = "Staging ${files.size} file(s)...",
            startedAtEpochMillis = startedAt,
            elapsedMillis = 0L,
        )

        val staged = try {
            VeyroLog.i(TAG) { "[$transferId] Staging ${files.size} file(s) into cache" }
            stage(transferId, files)
        } catch (t: Throwable) {
            VeyroLog.e(TAG, t) { "[$transferId] Staging failed: ${t.message}" }
            _activeTransfer.value = _activeTransfer.value?.copy(
                stage = TransferStage.FAILED,
                statusText = "Staging failed",
                errorMessage = "Could not read the selected files",
            )
            return@withContext SendResult.Failed("Could not read the selected files")
        }
        VeyroLog.i(TAG) { "[$transferId] Staging complete: ${staged.items.size} items ready" }

        try {
            _activeTransfer.value = _activeTransfer.value?.copy(
                stage = TransferStage.CONNECTING,
                statusText = "Connecting to $peerName...",
            )
            val sessionResolution = resolveOrConnectSession(transferId, peerDeviceId, peerName)
            if (sessionResolution is SessionResolution.Failed) {
                _activeTransfer.value = _activeTransfer.value?.copy(
                    stage = TransferStage.FAILED,
                    statusText = sessionResolution.reason,
                    errorMessage = sessionResolution.reason,
                )
                return@withContext SendResult.Failed(sessionResolution.reason)
            }
            val session = (sessionResolution as SessionResolution.Ready).session

            VeyroLog.i(TAG) { "[$transferId] Active session ready; dispatching transfer through runner" }
            when (val outcome = runner.send(
                session = session,
                items = staged.items,
                senderName = localName(),
                transferId = transferId,
                onProgress = { bytesSent, totalBytes, item, stage, speedBps, etaSec ->
                    val now = System.currentTimeMillis()
                    _activeTransfer.value = _activeTransfer.value?.copy(
                        currentFileName = item.fileName,
                        currentFileIndex = item.index,
                        bytesTransferred = bytesSent,
                        totalBytes = totalBytes,
                        bytesPerSecond = speedBps,
                        etaSeconds = etaSec,
                        stage = stage,
                        statusText = when (stage) {
                            TransferStage.AWAITING_CONSENT -> "Waiting for $peerName to accept..."
                            TransferStage.TRANSFERRING -> "Sending ${item.fileName} (${item.index + 1}/${staged.items.size})"
                            TransferStage.VERIFYING -> "Verifying ${item.fileName}..."
                            TransferStage.COMPLETED -> "Transfer completed"
                            TransferStage.CANCELLED -> "Transfer cancelled"
                            TransferStage.FAILED -> "Transfer failed"
                            else -> stage.name
                        },
                        elapsedMillis = (now - startedAt).coerceAtLeast(0L),
                    )
                },
                resolveFile = { item -> staged.files.getValue(item.index) },
            )) {
                is TransferRunner.SendOutcome.Done -> {
                    VeyroLog.i(TAG) { "[$transferId] Transfer completed: ${outcome.results.size} items" }
                    val allOk = outcome.results.all { it.isVerified }
                    val anyOk = outcome.results.any { it.isVerified }
                    val finalState = when {
                        allOk -> TransferState.COMPLETED
                        anyOk -> TransferState.COMPLETED_WITH_ERRORS
                        else -> TransferState.FAILED
                    }
                    val transferRecord = Transfer(
                        id = transferId,
                        peerDeviceId = peerDeviceId,
                        peerDisplayName = peerName,
                        direction = TransferDirection.SEND,
                        items = staged.items.map { item ->
                            val res = outcome.results.firstOrNull { it.itemIndex == item.index }
                            item.copy(verification = res?.verification)
                        },
                        totalBytes = totalSourceBytes,
                        state = finalState,
                        startedAtEpochMillis = System.currentTimeMillis() - 1000L,
                        finishedAtEpochMillis = System.currentTimeMillis(),
                    )
                    completedTransfers.add(transferId)
                    if (onTransferCompleted != null) {
                        try {
                            onTransferCompleted.invoke(transferRecord)
                            VeyroLog.i(TAG) { "[$transferId] Persisted send history record: state=$finalState" }
                        } catch (t: Throwable) {
                            VeyroLog.e(TAG, t) { "[$transferId] Failed to persist send history record: ${t.message}" }
                        }
                    }
                    _activeTransfer.value = _activeTransfer.value?.copy(
                        stage = if (allOk) TransferStage.COMPLETED else TransferStage.FAILED,
                        statusText = if (allOk) "Sent ${staged.items.size} file(s), all verified" else "Completed with errors",
                        bytesTransferred = totalSourceBytes,
                        isVerified = allOk,
                        route = outcome.route,
                    )
                    SendResult.Success(outcome.results)
                }
                TransferRunner.SendOutcome.Declined -> {
                    VeyroLog.w(TAG) { "[$transferId] Transfer declined by receiver" }
                    val transferRecord = Transfer(
                        id = transferId,
                        peerDeviceId = peerDeviceId,
                        peerDisplayName = peerName,
                        direction = TransferDirection.SEND,
                        items = staged.items,
                        totalBytes = totalSourceBytes,
                        state = TransferState.DECLINED,
                        startedAtEpochMillis = System.currentTimeMillis(),
                        finishedAtEpochMillis = System.currentTimeMillis(),
                    )
                    completedTransfers.add(transferId)
                    runCatching { onTransferCompleted?.invoke(transferRecord) }
                    _activeTransfer.value = _activeTransfer.value?.copy(
                        stage = TransferStage.FAILED,
                        statusText = "Declined by $peerName",
                        errorMessage = "Transfer declined by receiver",
                    )
                    SendResult.Declined
                }
                TransferRunner.SendOutcome.Cancelled -> {
                    VeyroLog.w(TAG) { "[$transferId] Transfer cancelled" }
                    val transferRecord = Transfer(
                        id = transferId,
                        peerDeviceId = peerDeviceId,
                        peerDisplayName = peerName,
                        direction = TransferDirection.SEND,
                        items = staged.items,
                        totalBytes = totalSourceBytes,
                        state = TransferState.CANCELLED,
                        startedAtEpochMillis = System.currentTimeMillis(),
                        finishedAtEpochMillis = System.currentTimeMillis(),
                    )
                    completedTransfers.add(transferId)
                    runCatching { onTransferCompleted?.invoke(transferRecord) }
                    _activeTransfer.value = _activeTransfer.value?.copy(
                        stage = TransferStage.CANCELLED,
                        statusText = "Transfer cancelled",
                    )
                    SendResult.Cancelled
                }
                is TransferRunner.SendOutcome.Failed -> {
                    VeyroLog.e(TAG) { "[$transferId] Transfer failed: ${outcome.reason}" }
                    val transferRecord = Transfer(
                        id = transferId,
                        peerDeviceId = peerDeviceId,
                        peerDisplayName = peerName,
                        direction = TransferDirection.SEND,
                        items = staged.items,
                        totalBytes = totalSourceBytes,
                        state = TransferState.FAILED,
                        startedAtEpochMillis = System.currentTimeMillis(),
                        finishedAtEpochMillis = System.currentTimeMillis(),
                    )
                    completedTransfers.add(transferId)
                    runCatching { onTransferCompleted?.invoke(transferRecord) }
                    _activeTransfer.value = _activeTransfer.value?.copy(
                        stage = TransferStage.FAILED,
                        statusText = "Transfer failed: ${outcome.reason}",
                        errorMessage = outcome.reason,
                    )
                    SendResult.Failed(outcome.reason)
                }
            }
        } catch (t: Throwable) {
            val current = _activeTransfer.value
            if (current?.stage == TransferStage.COMPLETED) {
                if (t is CancellationException) throw t
                return@withContext SendResult.Success(emptyList())
            }
            if (t is CancellationException) {
                _activeTransfer.value = _activeTransfer.value?.copy(
                    stage = TransferStage.CANCELLED,
                    statusText = "Transfer cancelled",
                )
                throw t
            }
            VeyroLog.e(TAG, t) { "[$transferId] Send failed with exception: ${t.message}" }
            val transferRecord = Transfer(
                id = transferId,
                peerDeviceId = peerDeviceId,
                peerDisplayName = peerName,
                direction = TransferDirection.SEND,
                items = staged.items,
                totalBytes = totalSourceBytes,
                state = TransferState.FAILED,
                startedAtEpochMillis = System.currentTimeMillis(),
                finishedAtEpochMillis = System.currentTimeMillis(),
            )
            completedTransfers.add(transferId)
            runCatching { onTransferCompleted?.invoke(transferRecord) }
            _activeTransfer.value = _activeTransfer.value?.copy(
                stage = TransferStage.FAILED,
                statusText = "Transfer failed: ${t.message ?: "Connection lost"}",
                errorMessage = t.message ?: "Connection lost",
            )
            SendResult.Failed(t.message ?: "Connection lost")
        } finally {
            runCatching { File(stagingDir, transferId).deleteRecursively() }
        }
    }

    /**
     * Sends [file] over [session] as the answer to a request the peer made (`photoFetch`,
     * 04-PROTOCOL.md 6.4): the offer carries [requestId], which the peer accepts without a prompt.
     *
     * Uses the same staging path as [sendTo], so a `content://` source works, but stays quiet: no
     * banner and no history entry, since the user acted on the PC, not here. File names are never
     * logged.
     */
    public suspend fun sendRequested(
        session: PeerConnection,
        file: OutgoingFile,
        requestId: String,
    ): SendResult = withContext(dispatchers.io) {
        val transferId = UUID.randomUUID().toString().take(8)
        try {
            val staged = try {
                stage(transferId, listOf(file))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                VeyroLog.w(TAG) { "[$transferId] Requested send: staging failed (${t.javaClass.simpleName})" }
                return@withContext SendResult.Failed("Could not read the requested file")
            }
            val outcome = runner.send(
                session = session,
                items = staged.items,
                senderName = localName(),
                transferId = transferId,
                requestId = requestId,
                resolveFile = { item -> staged.files.getValue(item.index) },
            )
            VeyroLog.i(TAG) { "[$transferId] Requested send finished: ${outcome.javaClass.simpleName}" }
            when (outcome) {
                is TransferRunner.SendOutcome.Done -> SendResult.Success(outcome.results)
                TransferRunner.SendOutcome.Declined -> SendResult.Declined
                TransferRunner.SendOutcome.Cancelled -> SendResult.Cancelled
                is TransferRunner.SendOutcome.Failed -> SendResult.Failed(outcome.reason)
            }
        } finally {
            runCatching { File(stagingDir, transferId).deleteRecursively() }
        }
    }

    public fun cancel(transferId: String? = null) {
        val active = _activeTransfer.value
        val targetId = transferId ?: active?.transferId
        if (active != null && (targetId == null || active.transferId == targetId)) {
            if (active.stage == TransferStage.COMPLETED || active.isTerminal) {
                VeyroLog.i(TAG) { "Cancel ignored: transfer ${active.transferId} is already in terminal stage ${active.stage}" }
                return
            }
            _activeTransfer.value = active.copy(
                stage = TransferStage.CANCELLED,
                statusText = "Transfer cancelled",
            )
        }
        runner.cancel(targetId)
    }

    // ── Receiving ──────────────────────────────────────────────────────────────────────────

    public suspend fun respondToOffer(transferId: String, accept: Boolean) {
        mutex.withLock {
            pending[transferId]?.let { it.accepted = accept; it.answered = true }
        }
        if (!accept && _activeTransfer.value?.transferId == transferId) {
            _activeTransfer.value = _activeTransfer.value?.copy(
                stage = TransferStage.CANCELLED,
                statusText = "Transfer declined",
            )
        }
    }

    private suspend fun authorize(session: PeerConnection, offer: LinkMessage.TransferOffer): Boolean {
        val transferId = offer.transferId
        val peerId = session.peerDeviceId?.let { DeviceId.parseOrNull(it) ?: DeviceId(it) }
        VeyroLog.i(TAG) { "[$transferId] Authorizing transfer from ${offer.senderName} (peerId=$peerId, ${offer.items.size} items, ${offer.totalBytes} bytes)" }

        // Defense in depth: Check if transfer is already completed or actively in progress
        if (completedTransfers.contains(transferId)) {
            VeyroLog.w(TAG) { "[$transferId] Rejecting TransferOffer: transfer was already completed" }
            return false
        }
        if (!activeTransfers.add(transferId)) {
            VeyroLog.w(TAG) { "[$transferId] Rejecting TransferOffer: transfer is already actively being processed" }
            return false
        }

        val uiOffer = IncomingOffer(
            transferId = transferId,
            peerName = offer.senderName,
            fileCount = offer.items.count { !it.isDir },
            totalBytes = offer.totalBytes,
            topLevelNames = offer.items.filterNot { it.isDir }
                .map { it.relPath.substringBefore('/') }.distinct().take(5),
        )

        val autoAccept = peerId != null && autoAcceptFor(peerId)
        _activeTransfer.value = ActiveTransfer(
            transferId = transferId,
            direction = TransferDirection.RECEIVE,
            peerDeviceId = peerId,
            peerDisplayName = offer.senderName,
            currentFileName = uiOffer.topLevelNames.firstOrNull() ?: "",
            currentFileIndex = 0,
            totalFiles = uiOffer.fileCount,
            bytesTransferred = 0L,
            totalBytes = offer.totalBytes,
            bytesPerSecond = 0L,
            etaSeconds = null,
            stage = if (autoAccept) TransferStage.TRANSFERRING else TransferStage.AWAITING_CONSENT,
            statusText = if (autoAccept) "Receiving from ${offer.senderName}..." else "Incoming transfer from ${offer.senderName}",
            startedAtEpochMillis = System.currentTimeMillis(),
            elapsedMillis = 0L,
        )

        if (autoAccept) {
            VeyroLog.i(TAG) { "[$transferId] Transfer auto-accepted by policy for $peerId" }
            return true
        }

        val decision = PendingDecision()
        mutex.withLock { pending[transferId] = decision }
        _pendingOffer.value = uiOffer
        try {
            _incomingOffers.emit(uiOffer)
            val deadline = System.currentTimeMillis() + OFFER_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (decision.answered) {
                    VeyroLog.i(TAG) { "[$transferId] User answered offer: accepted=${decision.accepted}" }
                    if (!decision.accepted) {
                        activeTransfers.remove(transferId)
                        completedTransfers.add(transferId)
                        _activeTransfer.value = _activeTransfer.value?.copy(
                            stage = TransferStage.FAILED,
                            statusText = "Declined transfer",
                            errorMessage = "Declined by user",
                        )
                    }
                    return decision.accepted
                }
                kotlinx.coroutines.delay(100)
            }
            VeyroLog.w(TAG) { "[$transferId] Transfer offer timed out awaiting user decision" }
            activeTransfers.remove(transferId)
            completedTransfers.add(transferId)
            _activeTransfer.value = _activeTransfer.value?.copy(
                stage = TransferStage.FAILED,
                statusText = "Transfer offer timed out",
                errorMessage = "Timed out awaiting decision",
            )
            return false
        } finally {
            mutex.withLock {
                pending.remove(transferId)
                if (_pendingOffer.value?.transferId == transferId) {
                    _pendingOffer.value = null
                }
            }
        }
    }

    // ── Staging ────────────────────────────────────────────────────────────────────────────

    private class Staged(val items: List<TransferItem>, val files: Map<Int, File>)

    private fun stage(transferId: String, files: List<OutgoingFile>): Staged {
        val dir = File(stagingDir, transferId).apply { mkdirs() }
        val items = ArrayList<TransferItem>(files.size)
        val byIndex = HashMap<Int, File>(files.size)
        files.forEachIndexed { index, outgoing ->
            val local = File(dir, "staged-$index")
            outgoing.openStream().use { input ->
                local.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            }
            byIndex[index] = local
            items += TransferItem(
                index = index,
                relativePath = RelativePath.sanitizeOutgoingSegment(outgoing.effectiveName),
                // What was actually copied, not what the picker claimed.
                sizeBytes = local.length(),
                lastModifiedEpochMillis = outgoing.lastModifiedEpochMillis,
                isDirectory = false,
            )
        }
        return Staged(items, byIndex)
    }
}

/** Legacy alias for backwards compatibility. */
public typealias LinkTransferCoordinator = TransferCoordinator

/** One file chosen to send. Opener, not path, because of `content://` URIs. */
public data class OutgoingFile(
    val effectiveName: String,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
    val openStream: () -> InputStream,
)

/** An offer awaiting the user's answer. No full path list - a glance, not a wall (REQ-U04). */
public data class IncomingOffer(
    val transferId: String,
    val peerName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val topLevelNames: List<String>,
) {
    public fun summary(): String = when {
        fileCount <= 0 -> "an empty transfer"
        fileCount == 1 -> topLevelNames.firstOrNull() ?: "1 file"
        else -> "$fileCount files"
    }
}

public sealed interface SendResult {
    public data class Success(val results: List<ItemResult>) : SendResult
    public data object Declined : SendResult
    public data object Cancelled : SendResult
    public data class Failed(val reason: String) : SendResult
}
