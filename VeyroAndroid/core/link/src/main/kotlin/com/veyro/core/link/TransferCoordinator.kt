package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.ActiveTransfer
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.model.Transfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferProgress
import com.veyro.core.model.TransferStage
import com.veyro.core.model.TransferState
import com.veyro.core.model.folderName
import com.veyro.core.transfer.FilesystemStoragePublisher
import com.veyro.core.transfer.FolderReceive
import com.veyro.core.transfer.ItemResult
import com.veyro.core.transfer.TransferStoragePublisher
import com.veyro.core.protocol.RelativePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
 * No screen ever sees a `PeerConnection` or a `PeerMessage`; it sees progress, offers and outcomes.
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
    /** Turns on auto-accept for a device ("Always accept from this PC" on the prompt). */
    private val rememberAutoAccept: (suspend (DeviceId) -> Unit)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    public companion object {
        private const val TAG = "TransferCoordinator"

        /**
         * How long an incoming offer waits for an answer before it is declined with `timeout`.
         * Matches the PC's consent prompt, so both sides give up at the same moment.
         */
        public const val OFFER_TIMEOUT_MS: Long = 60_000L
        private const val CONNECT_WAIT_MS = 15_000L
        private const val DISCOVERY_WAIT_MS = 5_000L
        private const val RECEIVE_RETRY_DELAY_MS = 250L
        private const val OFFER_RECHECK_MS = 1_000L
        private const val HELLO_WAIT_MS = 3_000L
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

    /**
     * Clears the banner state only if it still shows [transferId], so a banner that slides out
     * after a finished transfer cannot wipe a transfer that started in the meantime.
     */
    public fun clearActiveTransfer(transferId: String) {
        _activeTransfer.update { current -> current?.takeUnless { it.transferId == transferId } }
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

        /** Completed with the answer, so the waiting offer wakes at once instead of polling. */
        val answeredSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
    }

    /** Each running [sendTo]'s own job, by transfer id, so [cancel] reaches it at any stage (ADR-055). */
    private val sendJobs = ConcurrentHashMap<String, Job>()

    /** Sends the user cancelled on this device; [sendTo] answers [SendResult.Cancelled] for them. */
    private val cancelledHere = ConcurrentHashMap.newKeySet<String>()

    /** Wire reason for each offer [authorize] refused, read once by the runner. */
    private val declineReasons = ConcurrentHashMap<String, String>()

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
            // ADR-064: one per offer, so a received folder keeps one saved name ("Photos (2)")
            // for all its items, and its empty directories are made once its files are in.
            var folders: FolderReceive? = null
            val results = runCatching {
                val destination = destinationProvider().also { it.mkdirs() }
                val received = FolderReceive(storagePublisher ?: inPlace(destination)).also { folders = it }
                runner.receive(
                    session = connection,
                    destinationDir = destination,
                    authorize = { offer ->
                        handledId = offer.transferId
                        authorize(connection, offer).also { accepted ->
                            if (accepted) onFolderAccepted(offer, received)
                        }
                    },
                    declineReason = { id -> declineReasons.remove(id) ?: DeclineReason.DECLINED.wire },
                    publisher = storagePublisher?.let { received },
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
                // ADR-064: a folder's empty directories, once at least one of its files is in.
                val dirsNotCreated = if (anyOk) folders?.createEmptyDirectories() ?: 0 else 0
                val savedFolder = folders?.folderName

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
                    statusText = when {
                        !allOk -> "Transfer completed with errors"
                        savedFolder != null -> "Received ${folderSummary(savedFolder, receiveResult.size)}" +
                            if (dirsNotCreated > 0) ", $dirsNotCreated empty folder(s) not created" else ""
                        else -> "Received ${receiveResult.size} file(s) (Verified)"
                    },
                    folderName = savedFolder ?: _activeTransfer.value?.folderName,
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

    /**
     * An accepted offer, before any item opens: picks the saved name of a folder's top folder and
     * shows it in the banner (ADR-064).
     */
    private suspend fun onFolderAccepted(offer: PeerMessage.TransferOffer, folders: FolderReceive) {
        folders.prepare(offer.items.map { it.toDomain() })
        val saved = folders.folderName ?: return
        _activeTransfer.update { current -> if (current?.transferId == offer.transferId) current.copy(folderName = saved) else current }
    }

    /**
     * Without a publisher items are committed where they land, under the names they came with,
     * so a folder is not renamed; only its empty directories are made, inside [destination].
     */
    private fun inPlace(destination: File): TransferStoragePublisher =
        object : TransferStoragePublisher by FilesystemStoragePublisher(destination) {
            override suspend fun folderExists(name: String): Boolean = false
        }

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
        // (with several PCs connected, the one for this peer, whichever PC is selected on Home).
        val current = connectionManager.connectionFor(peerDeviceId.value)
        if (current != null) {
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
            connectionManager.connections
                .map { it[peerDeviceId.value] }
                .filterNotNull()
                .first { it.isConnected }
        } ?: run {
            VeyroLog.w(TAG) { "[$transferId] Timed out waiting for active session with $peerName" }
            return SessionResolution.Failed("$peerName did not answer")
        }

        return SessionResolution.Ready(session)
    }

    /**
     * Sends [files] to one device. [cancel] on this device stops it at any stage, staging and
     * connecting included, and the answer is [SendResult.Cancelled] rather than an exception, so a
     * send to several devices knows to skip the rest (ADR-055).
     */
    public suspend fun sendTo(
        peerDeviceId: DeviceId,
        peerName: String,
        files: List<OutgoingFile>,
    ): SendResult {
        val transferId = UUID.randomUUID().toString().take(8)
        return try {
            coroutineScope {
                sendJobs[transferId] = coroutineContext.job
                try {
                    runSend(transferId, peerDeviceId, peerName, files)
                } finally {
                    sendJobs.remove(transferId)
                }
            }
        } catch (e: CancellationException) {
            // Only this send's job was cancelled; a cancelled caller still gets the exception.
            if (transferId !in cancelledHere || !currentCoroutineContext().isActive) throw e
            VeyroLog.i(TAG) { "[$transferId] Send cancelled on this device" }
            recordCancelledSend(transferId, peerDeviceId, peerName, files)
            SendResult.Cancelled
        } finally {
            cancelledHere.remove(transferId)
        }
    }

    /** History for a send cancelled here, unless its outcome is already recorded. */
    private suspend fun recordCancelledSend(
        transferId: String,
        peerDeviceId: DeviceId,
        peerName: String,
        files: List<OutgoingFile>,
    ) {
        if (!completedTransfers.add(transferId)) return
        val now = System.currentTimeMillis()
        val record = Transfer(
            id = transferId,
            peerDeviceId = peerDeviceId,
            peerDisplayName = peerName,
            direction = TransferDirection.SEND,
            items = plannedItems(files).map { it.first },
            totalBytes = files.sumOf { if (it.isDirectory) 0L else it.sizeBytes },
            state = TransferState.CANCELLED,
            startedAtEpochMillis = now,
            finishedAtEpochMillis = now,
        )
        runCatching { onTransferCompleted?.invoke(record) }
    }

    private suspend fun runSend(
        transferId: String,
        peerDeviceId: DeviceId,
        peerName: String,
        files: List<OutgoingFile>,
    ): SendResult = withContext(dispatchers.io) {
        VeyroLog.i(TAG) { "[$transferId] User initiated transfer to $peerName ($peerDeviceId) with ${files.size} file(s)" }
        if (files.isEmpty()) return@withContext SendResult.Failed("Nothing selected")

        val totalSourceBytes = files.sumOf { if (it.isDirectory) 0L else it.sizeBytes }
        val fileCount = files.count { !it.isDirectory }
        if (fileCount == 0) return@withContext SendResult.Failed("There are no files to send")
        val folder = plannedItems(files).map { it.first }.folderName()
        VeyroLog.i(TAG) { "[$transferId] Source files: $fileCount, total size: $totalSourceBytes bytes" }

        val startedAt = System.currentTimeMillis()
        _activeTransfer.value = ActiveTransfer(
            transferId = transferId,
            direction = TransferDirection.SEND,
            peerDeviceId = peerDeviceId,
            peerDisplayName = peerName,
            currentFileName = files.firstOrNull { !it.isDirectory }?.effectiveName ?: "",
            currentFileIndex = 0,
            totalFiles = fileCount,
            bytesTransferred = 0L,
            totalBytes = totalSourceBytes,
            bytesPerSecond = 0L,
            etaSeconds = null,
            stage = TransferStage.PREPARING,
            statusText = if (folder != null) "Preparing ${folderSummary(folder, fileCount)}..." else "Staging $fileCount file(s)...",
            startedAtEpochMillis = startedAt,
            elapsedMillis = 0L,
            folderName = folder,
        )

        val staged = try {
            VeyroLog.i(TAG) { "[$transferId] Staging ${files.size} file(s) into cache" }
            stage(transferId, files)
        } catch (t: Throwable) {
            // The half-staged copy can be gigabytes; it goes now, not at the next sweep.
            runCatching { File(stagingDir, transferId).deleteRecursively() }
            if (t is CancellationException) {
                _activeTransfer.value = _activeTransfer.value?.copy(
                    stage = TransferStage.CANCELLED,
                    statusText = "Transfer cancelled",
                )
                throw t
            }
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

            // ADR-064: folders only to a device that says it takes them. Its hello can trail a
            // fresh connection by a moment.
            if (staged.hasFolders && !receivesFolders(session)) {
                val reason = "$peerName cannot receive folders. Update Veyro on $peerName."
                VeyroLog.w(TAG) { "[$transferId] Peer does not advertise FOLDER_TRANSFER; folder not offered" }
                _activeTransfer.value = _activeTransfer.value?.copy(
                    stage = TransferStage.FAILED,
                    statusText = reason,
                    errorMessage = reason,
                )
                return@withContext SendResult.Failed(reason)
            }

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
                            TransferStage.TRANSFERRING -> "Sending ${item.fileName} (${item.index + 1}/${staged.fileCount})"
                            TransferStage.VERIFYING -> "Verifying ${item.fileName}..."
                            TransferStage.COMPLETED -> "Transfer completed"
                            TransferStage.CANCELLED -> "Transfer cancelled"
                            TransferStage.FAILED -> "Transfer failed"
                            else -> stage.name
                        },
                        elapsedMillis = (now - startedAt).coerceAtLeast(0L),
                    )
                },
                openSource = staged::sourceFor,
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
                        statusText = when {
                            !allOk -> "Completed with errors"
                            folder != null -> "Sent ${folderSummary(folder, staged.fileCount)}, all verified"
                            else -> "Sent ${staged.fileCount} file(s), all verified"
                        },
                        bytesTransferred = totalSourceBytes,
                        isVerified = allOk,
                        route = outcome.route,
                    )
                    SendResult.Success(outcome.results)
                }
                is TransferRunner.SendOutcome.Declined -> {
                    VeyroLog.w(TAG) { "[$transferId] Transfer declined by receiver (${outcome.reason})" }
                    // The receiver's own reason, not a generic "declined": busy and error ask for
                    // a different reaction than a real no.
                    val message = outcome.reason.message(peerName)
                    val transferRecord = Transfer(
                        id = transferId,
                        peerDeviceId = peerDeviceId,
                        peerDisplayName = peerName,
                        direction = TransferDirection.SEND,
                        items = staged.items,
                        totalBytes = totalSourceBytes,
                        state = when (outcome.reason) {
                            DeclineReason.DECLINED, DeclineReason.TIMEOUT -> TransferState.DECLINED
                            DeclineReason.BUSY, DeclineReason.ERROR -> TransferState.FAILED
                        },
                        startedAtEpochMillis = System.currentTimeMillis(),
                        finishedAtEpochMillis = System.currentTimeMillis(),
                    )
                    completedTransfers.add(transferId)
                    runCatching { onTransferCompleted?.invoke(transferRecord) }
                    _activeTransfer.value = _activeTransfer.value?.copy(
                        stage = TransferStage.FAILED,
                        statusText = message,
                        errorMessage = message,
                    )
                    SendResult.Declined(outcome.reason)
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
                    if (transferId in cancelledHere) SendResult.Cancelled else SendResult.CancelledByPeer
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
            staged.close()
            runCatching { File(stagingDir, transferId).deleteRecursively() }
        }
    }

    /** True once [session]'s peer advertised FOLDER_TRANSFER, waiting briefly for its hello. */
    private suspend fun receivesFolders(session: PeerConnection): Boolean {
        val capabilities = session.peerCapabilities.value.takeUnless { it == Capabilities.NONE }
            ?: withTimeoutOrNull(HELLO_WAIT_MS) { session.peerCapabilities.first { it != Capabilities.NONE } }
            ?: Capabilities.NONE
        return Capability.FOLDER_TRANSFER in capabilities
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
            val outcome = staged.use {
                runner.send(
                    session = session,
                    items = staged.items,
                    senderName = localName(),
                    transferId = transferId,
                    requestId = requestId,
                    openSource = staged::sourceFor,
                )
            }
            VeyroLog.i(TAG) { "[$transferId] Requested send finished: ${outcome.javaClass.simpleName}" }
            when (outcome) {
                is TransferRunner.SendOutcome.Done -> SendResult.Success(outcome.results)
                is TransferRunner.SendOutcome.Declined -> SendResult.Declined(outcome.reason)
                // No banner, no Cancel button here: the PC asked for the file and cancelled it.
                TransferRunner.SendOutcome.Cancelled -> SendResult.CancelledByPeer
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
        // ADR-055: the send's own job as well. The runner only has a send once it is offered, so
        // without this a Cancel while staging or connecting was lost and the send went ahead.
        sendJobs.forEach { (id, job) ->
            if (targetId == null || id == targetId) {
                cancelledHere.add(id)
                job.cancel()
            }
        }
    }

    // ── Receiving ──────────────────────────────────────────────────────────────────────────

    /**
     * The user's answer to [pendingOffer], from the prompt or the notification; whichever answers
     * first wins and later answers are ignored. [alwaysAccept] (only meaningful with [accept])
     * turns on auto-accept for the sending device before the transfer starts.
     */
    public suspend fun respondToOffer(transferId: String, accept: Boolean, alwaysAccept: Boolean = false) {
        val offer = _pendingOffer.value?.takeIf { it.transferId == transferId }
        if (accept && alwaysAccept) {
            val peer = offer?.peerDeviceId
            val remember = rememberAutoAccept
            if (peer != null && remember != null) {
                runCatching { remember(peer) }
                    .onFailure { VeyroLog.w(TAG) { "[$transferId] Could not save auto-accept (${it.javaClass.simpleName})" } }
            }
        }
        mutex.withLock {
            pending[transferId]?.takeUnless { it.answered }?.let {
                it.accepted = accept
                it.answered = true
                it.answeredSignal.complete(Unit)
            }
        }
        if (!accept && _activeTransfer.value?.transferId == transferId) {
            _activeTransfer.value = _activeTransfer.value?.copy(
                stage = TransferStage.CANCELLED,
                statusText = "Transfer declined",
            )
        }
    }

    private suspend fun authorize(session: PeerConnection, offer: PeerMessage.TransferOffer): Boolean {
        val transferId = offer.transferId
        val peerId = session.peerDeviceId?.let { DeviceId.parseOrNull(it) ?: DeviceId(it) }
        VeyroLog.i(TAG) { "[$transferId] Authorizing transfer from ${offer.senderName} (peerId=$peerId, ${offer.items.size} items, ${offer.totalBytes} bytes)" }

        // Defense in depth: Check if transfer is already completed or actively in progress
        if (completedTransfers.contains(transferId)) {
            VeyroLog.w(TAG) { "[$transferId] Rejecting TransferOffer: transfer was already completed" }
            declineReasons[transferId] = DeclineReason.BUSY.wire
            return false
        }
        if (!activeTransfers.add(transferId)) {
            VeyroLog.w(TAG) { "[$transferId] Rejecting TransferOffer: transfer is already actively being processed" }
            declineReasons[transferId] = DeclineReason.BUSY.wire
            return false
        }

        val files = offer.items.filterNot { it.isDir }
        // The sender's name for it; untrusted, shown only. The saved name may differ (ADR-064).
        val folder = offer.items.map { it.toDomain() }.folderName()
        val uiOffer = IncomingOffer(
            transferId = transferId,
            peerName = offer.senderName,
            fileCount = files.size,
            totalBytes = offer.totalBytes,
            topLevelNames = files.map { it.relPath.substringBefore('/') }.distinct().take(5),
            peerDeviceId = peerId,
            files = files.take(IncomingOffer.MAX_LISTED_FILES).map {
                IncomingOffer.ListedFile(name = it.relPath.substringAfterLast('/'), sizeBytes = it.size)
            },
            expiresAtEpochMillis = clock() + OFFER_TIMEOUT_MS,
            folderName = folder,
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
            folderName = folder,
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
            val deadline = uiOffer.expiresAtEpochMillis
            while (clock() < deadline) {
                if (decision.answered) {
                    VeyroLog.i(TAG) { "[$transferId] User answered offer: accepted=${decision.accepted}" }
                    if (!decision.accepted) {
                        activeTransfers.remove(transferId)
                        completedTransfers.add(transferId)
                        declineReasons[transferId] = DeclineReason.DECLINED.wire
                        _activeTransfer.value = _activeTransfer.value?.copy(
                            stage = TransferStage.FAILED,
                            statusText = "Declined transfer",
                            errorMessage = "Declined by user",
                        )
                    }
                    return decision.accepted
                }
                // The sender gave up (its own timeout or a cancel) or the session dropped: close
                // the prompt now rather than let the user accept something that is gone.
                if (!session.isConnected || session.mailbox(transferId).isCancelled) {
                    VeyroLog.i(TAG) { "[$transferId] Offer withdrawn before an answer" }
                    activeTransfers.remove(transferId)
                    completedTransfers.add(transferId)
                    _activeTransfer.value = _activeTransfer.value?.copy(
                        stage = TransferStage.CANCELLED,
                        statusText = "${offer.senderName} withdrew the transfer",
                    )
                    return false
                }
                // Wakes at once on the answer; otherwise once a second to notice a withdrawn
                // offer or the deadline (was every 100 ms for the whole minute the prompt shows).
                kotlinx.coroutines.withTimeoutOrNull(OFFER_RECHECK_MS) { decision.answeredSignal.await() }
            }
            VeyroLog.w(TAG) { "[$transferId] Transfer offer timed out awaiting user decision" }
            declineReasons[transferId] = DeclineReason.TIMEOUT.wire
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

    /**
     * What a send streams: staged copies by item index, and a folder's files read straight from
     * their streams (ADR-064). [close] closes every source it handed out, whatever path the send
     * took out of the runner.
     */
    private class Staged(
        val items: List<TransferItem>,
        private val files: Map<Int, File>,
        private val streamed: Map<Int, OutgoingFile>,
    ) : java.io.Closeable {
        private val opened = Collections.synchronizedList(ArrayList<SendSource>())

        val fileCount: Int get() = items.count { !it.isDirectory }
        val hasFolders: Boolean get() = items.any { it.isDirectory || '/' in it.relativePath }
        val folderName: String? get() = items.folderName()

        fun sourceFor(item: TransferItem): SendSource? {
            files[item.index]?.let { return SendSource.of(it) }
            val outgoing = streamed[item.index] ?: return null
            // Opened now, so a file that vanished since the folder was read fails alone.
            val first = try {
                outgoing.openStream()
            } catch (e: Exception) {
                VeyroLog.w(TAG) { "Item #${item.index} could not be opened (${e.javaClass.simpleName})" }
                return null
            }
            var handedOut = false
            return SendSource.ofStream(item.sizeBytes) {
                if (handedOut) outgoing.openStream() else first.also { handedOut = true }
            }.also { opened += it }
        }

        override fun close() {
            synchronized(opened) { opened.forEach { runCatching { it.close() } } }
        }
    }

    /**
     * The items in offer order: files first, then a folder's empty directories (ADR-064), so an
     * item's index is also its place among the files ("file 3 of 120"). Sizes as claimed.
     */
    private fun plannedItems(files: List<OutgoingFile>): List<Pair<TransferItem, OutgoingFile>> {
        val ordered = files.filterNot { it.isDirectory } + files.filter { it.isDirectory }
        return ordered.mapIndexed { index, outgoing ->
            TransferItem(
                index = index,
                relativePath = outgoing.relativePath ?: RelativePath.sanitizeOutgoingSegment(outgoing.effectiveName),
                sizeBytes = if (outgoing.isDirectory) 0L else outgoing.sizeBytes,
                lastModifiedEpochMillis = outgoing.lastModifiedEpochMillis,
                isDirectory = outgoing.isDirectory,
            ) to outgoing
        }
    }

    private suspend fun stage(transferId: String, files: List<OutgoingFile>): Staged {
        val context = currentCoroutineContext()
        val dir = File(stagingDir, transferId)
        val planned = plannedItems(files)
        val items = ArrayList<TransferItem>(planned.size)
        val byIndex = HashMap<Int, File>()
        val streamed = HashMap<Int, OutgoingFile>()
        val buffer by lazy(LazyThreadSafetyMode.NONE) { ByteArray(256 * 1024) }
        for ((item, outgoing) in planned) {
            if (outgoing.isDirectory) {
                items += item
                continue
            }
            // ADR-064: no copy. Streamed while sending, so a folder of gigabytes needs no room.
            if (outgoing.streamed) {
                streamed[item.index] = outgoing
                items += item
                continue
            }
            dir.mkdirs()
            val local = File(dir, "staged-${item.index}")
            outgoing.openStream().use { input ->
                local.outputStream().use { output ->
                    // ADR-055: Cancel is checked between buffers; a 4 GB video takes a while to copy.
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            byIndex[item.index] = local
            // What was actually copied, not what the picker claimed.
            items += item.copy(sizeBytes = local.length())
        }
        return Staged(items, byIndex, streamed)
    }
}

/** Legacy alias for backwards compatibility. */
public typealias LinkTransferCoordinator = TransferCoordinator

/** One file chosen to send. Opener, not path, because of `content://` URIs. */
public data class OutgoingFile(
    val effectiveName: String,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
    /**
     * The path on the wire for an item of a folder ("Photos/2024/a.jpg", every segment already
     * sanitised, ADR-064). Null sends the file under [effectiveName] alone.
     */
    val relativePath: String? = null,
    /** An empty directory of a folder: offered so it exists on the other side, never streamed. */
    val isDirectory: Boolean = false,
    /**
     * Read straight from [openStream] while it is sent, with no staged copy (a folder's files,
     * ADR-064). [sizeBytes] must then be exact: a stream that ends elsewhere fails the send.
     */
    val streamed: Boolean = false,
    val openStream: () -> InputStream,
)

/** An offer awaiting the user's answer. No full path list - a glance, not a wall (REQ-U04). */
public data class IncomingOffer(
    val transferId: String,
    val peerName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val topLevelNames: List<String>,
    /** The sending device, for its fingerprint and "Always accept"; null if the session has none. */
    val peerDeviceId: DeviceId? = null,
    /** The first [MAX_LISTED_FILES] files, name and size, for the prompt's rows. */
    val files: List<ListedFile> = emptyList(),
    /** When the offer is declined with `timeout` if nobody answers ([TransferCoordinator.OFFER_TIMEOUT_MS]). */
    val expiresAtEpochMillis: Long = 0L,
    /** The folder's name when the offer is one folder (ADR-064); [fileCount] counts its files. */
    val folderName: String? = null,
) {
    /** One row of the prompt. User data: shown, never logged. */
    public data class ListedFile(val name: String, val sizeBytes: Long)

    public companion object {
        public const val MAX_LISTED_FILES: Int = 3
    }

    public fun summary(): String = when {
        folderName != null -> folderSummary(folderName, fileCount)
        fileCount <= 0 -> "an empty transfer"
        fileCount == 1 -> topLevelNames.firstOrNull() ?: "1 file"
        else -> "$fileCount files"
    }
}

public sealed interface SendResult {
    public data class Success(val results: List<ItemResult>) : SendResult
    public data class Declined(val reason: DeclineReason = DeclineReason.DECLINED) : SendResult

    /** The user cancelled it on this device; a send to several devices stops here (ADR-055). */
    public data object Cancelled : SendResult

    /** The receiving device cancelled it; the other devices of the same send still get theirs. */
    public data object CancelledByPeer : SendResult
    public data class Failed(val reason: String) : SendResult
}
