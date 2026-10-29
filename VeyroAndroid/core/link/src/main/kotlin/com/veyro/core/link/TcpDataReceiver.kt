package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferRoute
import com.veyro.core.model.VerificationResult
import com.veyro.core.protocol.tcp.TcpFrame
import com.veyro.core.protocol.tcp.TcpFrameCodec
import com.veyro.core.transfer.DirectPart
import com.veyro.core.transfer.ItemResult
import com.veyro.core.transfer.PartFile
import com.veyro.core.transfer.ReceivingItem
import com.veyro.core.transfer.StoragePublishResult
import com.veyro.core.transfer.TransferStoragePublisher
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Outcome of a raw TCP receive session.
 */
public sealed interface TcpReceiveOutcome {
    public data class Completed(val route: TransferRoute, val results: List<ItemResult>) : TcpReceiveOutcome
    public data class Cancelled(val reason: String) : TcpReceiveOutcome
    public data class Failed(val reason: String) : TcpReceiveOutcome
}

/**
 * Receiver-side raw TCP data engine.
 *
 * Binds an ephemeral ServerSocket on all interfaces (0.0.0.0), authenticates incoming
 * sender sockets against session credentials, streams raw binary chunks directly into
 * [DirectPart] (the final, still hidden destination, ADR-049) or [PartFile] instances, verifies
 * SHA-256 digests, and publishes to storage.
 */
public class TcpDataReceiver(
    private val dispatchers: AppDispatchers,
    private val socketTimeoutMs: Int = 30_000,
) {

    private companion object {
        const val TAG = "TcpDataReceiver"

        /** Chunk sizes a sender may announce in `ItemBegin`; VTC1 payloads stay under 16 MiB. */
        const val MIN_CHUNK_SIZE = 64 * 1024
        const val MAX_CHUNK_SIZE = 8 * 1024 * 1024
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val isClosed = AtomicBoolean(false)

    @Volatile
    public var activeRoute: TransferRoute? = null
        private set

    /**
     * Binds to an OS-allocated ephemeral port on 0.0.0.0 (all interfaces).
     * Returns the allocated port.
     */
    public fun bind(bindAddress: InetAddress = InetAddress.getByName("0.0.0.0")): Int {
        val ss = ServerSocket(0, 50, bindAddress).apply {
            soTimeout = socketTimeoutMs
        }
        serverSocket = ss
        isClosed.set(false)
        VeyroLog.i(TAG) { "[tcp-receiver:bound] Listening for raw TCP transfers on ${ss.inetAddress.hostAddress}:${ss.localPort} (timeout=${socketTimeoutMs}ms)" }
        return ss.localPort
    }

    public val localPort: Int
        get() = serverSocket?.localPort ?: 0

    public val bindAddress: InetAddress?
        get() = serverSocket?.inetAddress

    // Set from the session's control collector while the receive loop reads them on another thread.
    @Volatile private var externalCompleted: Boolean? = null
    @Volatile private var externalCancelReason: String? = null

    public fun notifyTransferEnd(completed: Boolean) {
        externalCompleted = completed
        VeyroLog.i(TAG) { "[tcp-receiver:notify-transfer-end] External TransferEnd signaled (completed=$completed)" }
    }

    public fun notifyCancel(reason: String) {
        externalCancelReason = reason
        VeyroLog.w(TAG) { "[tcp-receiver:notify-cancel] External Cancel signaled: $reason" }
        closeSocketImmediately()
    }

    /**
     * Accepts an incoming connection, verifies authentication, and streams data.
     */
    public suspend fun acceptAndReceive(
        transferId: String,
        expectedAuthToken: String,
        expectedPeerDeviceId: String?,
        offerItems: List<TransferItem>,
        destinationDir: File,
        chunkSize: Int,
        publisher: TransferStoragePublisher? = null,
        metrics: TransferThroughputMetrics? = null,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long, itemIndex: Int) -> Unit,
        onItemVerified: suspend (ItemResult) -> Unit,
    ): TcpReceiveOutcome = withContext(dispatchers.io) {
        val ss = serverSocket ?: throw IOException("ServerSocket not bound")
        val acceptStartTime = System.currentTimeMillis()
        VeyroLog.i(TAG) { "[$transferId] [tcp-receiver:await-connect] Awaiting sender TCP connection on ${ss.inetAddress.hostAddress}:${ss.localPort} (timeout=${socketTimeoutMs}ms)..." }

        val socket = try {
            ss.accept().apply {
                tcpNoDelay = true
                receiveBufferSize = 1024 * 1024
                sendBufferSize = 512 * 1024
                soTimeout = socketTimeoutMs
            }
        } catch (t: Throwable) {
            val elapsedMs = System.currentTimeMillis() - acceptStartTime
            val category = if (t is java.net.SocketTimeoutException) "ACCEPT_TIMEOUT" else "ACCEPT_FAILED"
            VeyroLog.w(TAG, t) {
                "[$transferId] [tcp-receiver:accept-failed] category=$category after ${elapsedMs}ms: ${t.javaClass.simpleName} - ${t.message}"
            }
            if (isClosed.get() || externalCancelReason != null) {
                return@withContext TcpReceiveOutcome.Cancelled(externalCancelReason ?: "Cancelled")
            }
            return@withContext TcpReceiveOutcome.Failed("Sender did not connect to TCP port within ${elapsedMs}ms ($category: ${t.message})")
        }
        val elapsedMs = System.currentTimeMillis() - acceptStartTime
        VeyroLog.i(TAG) {
            "[$transferId] [tcp-receiver:accept-success] Accepted connection from ${socket.inetAddress.hostAddress}:${socket.port} in ${elapsedMs}ms (local: ${socket.localAddress.hostAddress}:${socket.localPort})"
        }
        clientSocket = socket

        if (metrics != null) {
            metrics.sendBufferSize = socket.sendBufferSize
            metrics.receiveBufferSize = socket.receiveBufferSize
        }

        val input = BufferedInputStream(socket.getInputStream(), 512 * 1024)
        val output = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)

        // 1. Read Auth frame
        val authFrame = try {
            TcpFrameCodec.readFrame(input)
        } catch (t: Throwable) {
            VeyroLog.e(TAG, t) { "[$transferId] Failed to read Auth frame: ${t.message}" }
            close("Auth read failure")
            return@withContext TcpReceiveOutcome.Failed("Failed to read Auth frame: ${t.message}")
        }

        if (authFrame !is TcpFrame.Auth) {
            VeyroLog.e(TAG) { "[$transferId] Expected Auth frame, got: ${authFrame.javaClass.simpleName}" }
            close("Protocol error on Auth")
            return@withContext TcpReceiveOutcome.Failed("Protocol error: unexpected initial frame")
        }

        // 2. Validate auth
        val isTransferMatch = authFrame.transferId == transferId
        val isTokenMatch = authFrame.authToken == expectedAuthToken
        val isPeerMatch = expectedPeerDeviceId == null || authFrame.senderDeviceId == expectedPeerDeviceId

        if (!isTransferMatch || !isTokenMatch || !isPeerMatch) {
            VeyroLog.e(TAG) {
                "[$transferId] TCP auth failed: transferMatch=$isTransferMatch, tokenMatch=$isTokenMatch, peerMatch=$isPeerMatch"
            }
            runCatching {
                TcpFrameCodec.writeFrame(
                    output,
                    TcpFrame.AuthAck(
                        seq = 1L,
                        transferId = transferId,
                        ok = false,
                        reason = "UNAUTHORIZED",
                    ),
                )
            }
            close("Auth validation rejected")
            return@withContext TcpReceiveOutcome.Failed("TCP authentication rejected")
        }

        val route = authFrame.route
        activeRoute = route
        VeyroLog.i(TAG) { "[$transferId] Sender authenticated successfully via raw TCP ($route)" }

        // Send AuthAck
        TcpFrameCodec.writeFrame(
            output,
            TcpFrame.AuthAck(
                seq = 1L,
                transferId = transferId,
                ok = true,
                reason = null,
            ),
        )

        // 3. Items are opened when their data starts (ItemBegin, or the first chunk), not all up
        // front: a direct target is a MediaStore entry, and a 500-photo batch should not create
        // 500 of them before the first byte. ADR-049: a direct target first, so a verified item
        // needs no copy; a staging PartFile when the publisher has none.
        val parts = HashMap<Int, ReceivingItem>()
        val unopenable = HashSet<Int>()
        destinationDir.mkdirs()

        suspend fun partFor(index: Int, size: Int): ReceivingItem? {
            parts[index]?.let { return it }
            if (index in unopenable) return null
            val item = offerItems.firstOrNull { it.index == index } ?: return null
            val opened = try {
                publisher?.let { DirectPart.open(it, item.relativePath, item.mimeType, item.sizeBytes, size) }
                    ?: PartFile.open(
                        destinationDir = destinationDir,
                        relativePath = item.relativePath,
                        sizeBytes = item.sizeBytes,
                        chunkSize = size,
                    )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Index only: file names are user data and are never logged.
                VeyroLog.w(TAG) { "[$transferId] Failed to open item #$index for writing (${e.javaClass.simpleName})" }
                unopenable += index
                return null
            }
            parts[index] = opened
            return opened
        }

        suspend fun publishVerified(part: ReceivingItem, item: TransferItem): StoragePublishResult? {
            val publish: (suspend () -> StoragePublishResult)? = when {
                part is DirectPart -> ({ part.publish() })
                part is PartFile && publisher != null && part.committedFile != null -> ({
                    publisher.publish(
                        sourceFile = part.committedFile!!,
                        relativePath = item.relativePath,
                        mimeType = item.mimeType,
                        sizeBytes = item.sizeBytes,
                    )
                })
                else -> null
            }
            if (publish == null) return null
            return if (metrics != null) metrics.recordPublish { publish() } else publish()
        }

        val results = mutableListOf<ItemResult>()
        val totalBytes = offerItems.sumOf { it.sizeBytes }
        var totalWritten = 0L
        var lastSeq = 0L
        var transferEndReceived = false
        var completedReceived = false
        var cleanEofReceived = false
        var cancelledReason: String? = externalCancelReason

        try {
            while (!isClosed.get()) {
                val readStartNanos = System.nanoTime()
                val frame = try {
                    TcpFrameCodec.readFrameOrNull(input)
                } catch (t: Throwable) {
                    if (isClosed.get() || transferEndReceived || completedReceived || cancelledReason != null || externalCancelReason != null) break
                    val allItemsDone = results.size == offerItems.size && results.all { it.isVerified }
                    if (allItemsDone && t is java.io.EOFException) {
                        VeyroLog.i(TAG) { "[$transferId] [tcp-receiver:clean-eof-after-verify] Stream reached EOF after all items verified and committed: ${t.message}" }
                        break
                    }
                    VeyroLog.w(TAG, t) { "[$transferId] Error reading TCP frame: ${t.message}" }
                    break
                }
                val readDurationNanos = System.nanoTime() - readStartNanos

                if (frame == null) {
                    cleanEofReceived = true
                    VeyroLog.i(TAG) { "[$transferId] [tcp-receiver:clean-eof] Reached clean EOF on TCP stream (transferEndReceived=$transferEndReceived, completedReceived=$completedReceived, processed=${results.size}/${offerItems.size})" }
                    break
                }

                if (frame.transferId != transferId) {
                    VeyroLog.w(TAG) { "[$transferId] Ignored frame with mismatched transfer ID: ${frame.transferId}" }
                    continue
                }

                if (frame.seq <= lastSeq) {
                    VeyroLog.w(TAG) { "[$transferId] Out of order TCP frame: seq=${frame.seq} <= $lastSeq" }
                }
                lastSeq = frame.seq

                when (frame) {
                    is TcpFrame.ItemBegin -> {
                        VeyroLog.i(TAG) { "[$transferId] [tcp-receiver:item-begin-received] Item #${frame.itemIndex} (seq=${frame.seq}, size=${frame.sizeBytes}, chunkSize=${frame.chunkSize})" }
                        // The sender owns the chunk plan and states it here: Windows streams
                        // 4 MiB chunks, Android 512 KiB. A part opened with a different size would
                        // reject every full-size chunk, so it is opened (or reopened) with the sender's.
                        val size = frame.chunkSize.takeIf { it in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE } ?: chunkSize
                        val existing = parts[frame.itemIndex]
                        if (existing != null && existing.chunkSize != size) {
                            runCatching { existing.close() }
                            runCatching { existing.discard() }
                            parts.remove(frame.itemIndex)
                        }
                        partFor(frame.itemIndex, size)
                    }

                    is TcpFrame.Chunk -> {
                        val part = partFor(frame.itemIndex, chunkSize)
                        if (part != null) {
                            if (metrics != null) {
                                metrics.recordTcpReadDuration(readDurationNanos, frame.data.size.toLong())
                                metrics.recordDiskWrite(frame.data.size.toLong()) {
                                    part.writeChunk(frame.chunkIndex, frame.data)
                                }
                            } else {
                                part.writeChunk(frame.chunkIndex, frame.data)
                            }
                            totalWritten += frame.data.size
                            onProgress(totalWritten, totalBytes, frame.itemIndex)
                        }
                    }

                    is TcpFrame.ItemEnd -> {
                        val part = partFor(frame.itemIndex, chunkSize)
                        val item = offerItems.firstOrNull { it.index == frame.itemIndex }
                        VeyroLog.i(TAG) { "[$transferId] [tcp-receiver:item-end-received] Item #${frame.itemIndex} sha256=${frame.sha256Hex}" }
                        if (part != null && item != null) {
                            val verification = if (metrics != null) {
                                metrics.recordSha256 { part.verifyAndCommit(frame.sha256Hex) }
                            } else {
                                part.verifyAndCommit(frame.sha256Hex)
                            }
                            val ok = verification == VerificationResult.VERIFIED
                            VeyroLog.i(TAG) { "[$transferId] [tcp-receiver:item-verified] Item #${frame.itemIndex} verified: ok=$ok (expected=${frame.sha256Hex})" }

                            var finalVerification = verification
                            var destUri: String? = null
                            var errMsg: String? = if (ok) null else verification.name

                            if (ok) {
                                when (val pubRes = publishVerified(part, item)) {
                                    is StoragePublishResult.Success -> {
                                        destUri = pubRes.destinationUri
                                        VeyroLog.i(TAG) { "[$transferId] [tcp-receiver:item-published] Item #${frame.itemIndex} published to MediaStore: $destUri" }
                                    }
                                    is StoragePublishResult.Failed -> {
                                        finalVerification = VerificationResult.WRITE_FAILED
                                        errMsg = "PUBLICATION_FAILED: ${pubRes.reason}"
                                        VeyroLog.e(TAG) { "[$transferId] [tcp-receiver:item-publish-failed] Item #${frame.itemIndex} publication failed: ${pubRes.reason}" }
                                    }
                                    null -> destUri = (part as? PartFile)?.committedFile?.toURI()?.toString()
                                }
                            }

                            val itemRes = ItemResult(
                                itemIndex = frame.itemIndex,
                                relativePath = item.relativePath,
                                bytesWritten = if (finalVerification == VerificationResult.VERIFIED) item.sizeBytes else 0L,
                                verification = finalVerification,
                                errorMessage = errMsg,
                                destinationUri = destUri,
                            )
                            results += itemRes
                            VeyroLog.i(TAG) { "[$transferId] [tcp-receiver:item-result-notified] Emitting ItemResult for #${frame.itemIndex} (ok=${itemRes.isVerified})" }
                            onItemVerified(itemRes)
                            writeItemResult(output, transferId, frame.itemIndex, itemRes.isVerified, itemRes.errorMessage)
                        } else {
                            // An ItemEnd for an item this receiver never opened: answer it, so a
                            // sender waiting on the data socket fails the item instead of hanging.
                            writeItemResult(output, transferId, frame.itemIndex, false, "UNKNOWN_ITEM")
                        }
                    }

                    is TcpFrame.TransferEnd -> {
                        transferEndReceived = true
                        completedReceived = frame.completed
                        VeyroLog.i(TAG) { "[$transferId] [tcp-receiver:transfer-end-received] TCP TransferEnd received (completed=${frame.completed})" }
                        break
                    }

                    is TcpFrame.Cancel -> {
                        cancelledReason = frame.reason
                        VeyroLog.w(TAG) { "[$transferId] [tcp-receiver:cancel-received] TCP Cancel received: ${frame.reason}" }
                        break
                    }

                    else -> Unit
                }
            }
        } finally {
            parts.values.forEach { runCatching { it.close() } }
            close("acceptAndReceive finished")
        }

        if (cancelledReason != null || externalCancelReason != null) {
            val finalReason = cancelledReason ?: externalCancelReason ?: "cancelled"
            parts.values.filterNot { it.isCommitted }.forEach { it.discard() }
            return@withContext TcpReceiveOutcome.Cancelled(finalReason)
        }

        val allItemsVerified = results.size == offerItems.size && results.all { it.isVerified }
        val effectiveTerminated = transferEndReceived || (externalCompleted != null) || allItemsVerified

        if (!effectiveTerminated) {
            parts.values.filterNot { it.isCommitted }.forEach { it.discard() }
            return@withContext TcpReceiveOutcome.Failed("TCP stream interrupted before TransferEnd (verified ${results.count { it.isVerified }} of ${offerItems.size} items)")
        }

        // Fill in missing items if sender skipped them (e.g. missing source file where sender sent TransferEnd)
        for (offerItem in offerItems) {
            if (results.none { it.itemIndex == offerItem.index }) {
                results += ItemResult(
                    itemIndex = offerItem.index,
                    relativePath = offerItem.relativePath,
                    bytesWritten = 0L,
                    verification = VerificationResult.WRITE_FAILED,
                    errorMessage = "Item not transferred",
                )
            }
        }

        // Clean up any remaining uncommitted part artifacts
        parts.values.filterNot { it.isCommitted }.forEach { it.discard() }
        metrics?.markTransferComplete()
        metrics?.logBreakdown()
        TcpReceiveOutcome.Completed(route, results)
    }

    /** Receiver-side sequence for frames written back to the sender; AuthAck used 1. */
    private var outboundSeq = 1L

    /**
     * Answers an `ItemEnd` with a VTC1 [TcpFrame.ItemResult] on the data socket (04-PROTOCOL.md
     * 7.3). The WebSocket `itemResult` is still sent by the caller for Android senders; the Windows
     * sender reads only this one and blocks until it arrives.
     */
    private fun writeItemResult(
        output: BufferedOutputStream,
        transferId: String,
        itemIndex: Int,
        ok: Boolean,
        error: String?,
    ) {
        try {
            TcpFrameCodec.writeFrame(
                output,
                TcpFrame.ItemResult(
                    seq = ++outboundSeq,
                    transferId = transferId,
                    itemIndex = itemIndex,
                    ok = ok,
                    errorMessage = error,
                ),
            )
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "[$transferId] Could not write ItemResult for #$itemIndex on TCP (${t.javaClass.simpleName})" }
        }
    }

    /**
     * Forcefully and immediately closes client and server sockets to unblock any blocking accept/read.
     */
    public fun closeSocketImmediately() {
        if (externalCancelReason == null) {
            externalCancelReason = "Socket closed immediately"
        }
        if (isClosed.compareAndSet(false, true)) {
            VeyroLog.i(TAG) { "[tcp-receiver:socket-close] Force closing sockets immediately to unblock pending I/O" }
            runCatching { clientSocket?.close() }
            runCatching { serverSocket?.close() }
            clientSocket = null
            serverSocket = null
        }
    }

    public fun close(reason: String = "Normal closure") {
        if (isClosed.compareAndSet(false, true)) {
            VeyroLog.i(TAG) { "[tcp-receiver:socket-close] Initiating receiver socket close: reason=$reason, localPort=$localPort" }
            runCatching { clientSocket?.close() }
            runCatching { serverSocket?.close() }
            clientSocket = null
            serverSocket = null
        }
    }
}
