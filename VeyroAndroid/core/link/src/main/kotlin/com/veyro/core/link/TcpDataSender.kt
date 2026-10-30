package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.TransferRoute
import com.veyro.core.protocol.tcp.TcpFrame
import com.veyro.core.protocol.tcp.Vtc3
import com.veyro.core.protocol.tcp.Vtc3Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Sender-side raw TCP data engine.
 *
 * Implements a single ordered writer over the TCP socket stream, bounded chunk streaming and
 * connection lifecycle management. Every record is sealed (VTC3, ADR-058): the [Vtc3Channel]
 * owns the counters, so this class never numbers a frame itself.
 *
 * Lock order: [writeMutex] is always taken outside the `Vtc3Writer` lock and never while
 * holding it.
 */
public class TcpDataSender(
    private val dispatchers: AppDispatchers,
    private val connectTimeoutMs: Int = 8_000,
    private val socketTimeoutMs: Int = 30_000,
) {

    private companion object {
        const val TAG = "TcpDataSender"
    }

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private val writeMutex = Mutex()

    /** Runs the one chunk write in flight ([sendChunk]); its jobs never outlive the socket. */
    private val pipelineScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val pendingChunk = AtomicReference<Deferred<Unit>?>(null)

    @Volatile
    private var channel: Vtc3Channel? = null

    @Volatile
    public var activeRoute: TransferRoute? = null
        private set

    @Volatile
    private var isClosed = false

    public val isConnected: Boolean
        get() = !isClosed && socket?.isConnected == true && socket?.isClosed == false

    /** Set by [startInboundReader]; while it runs it is the only reader of the socket. */
    @Volatile
    private var inboundReader: Job? = null

    /**
     * Reads what the receiver writes back on the data socket after [TcpFrame.AuthAck] and hands
     * each frame to [onFrame], until EOF or the socket closes.
     *
     * The Windows receiver answers every `ItemEnd` with a VTC3 [TcpFrame.ItemResult] on this socket
     * and sends nothing on the WebSocket, so a sender that only waited on the WebSocket timed out
     * on every item (the photo-fetch "spins forever" report). Reading here is what lets
     * [TransferRunner] accept the result from either channel.
     */
    public fun startInboundReader(
        scope: CoroutineScope,
        /**
         * Called once when a record from the receiver fails (tag, order, format, truncation): the
         * data socket is already closed then, and the transfer must fail (review issue 5).
         */
        onFault: (Throwable) -> Unit = {},
        onFrame: suspend (TcpFrame) -> Unit,
    ): Job {
        val reader = channel?.reader ?: throw IOException("Socket not connected")
        return scope.launch(dispatchers.io) {
            while (isActive && !isClosed) {
                val frame = try {
                    reader.readOrNull()
                } catch (e: SocketTimeoutException) {
                    // Idle while chunks stream the other way (a timeout before a record starts).
                    continue
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    // Fail closed (04-PROTOCOL.md 7.3): a record that does not open ends the data
                    // socket at once, so no further chunk is sealed for a stream that is broken.
                    if (!isClosed) {
                        VeyroLog.w(TAG) { "[tcp-sender:feedback-fault] Receiver record rejected (${t.javaClass.simpleName}); closing the data socket" }
                        closeSocketImmediately()
                        onFault(t)
                    }
                    break
                }
                if (frame == null) break
                onFrame(frame)
            }
        }.also { inboundReader = it }
    }

    /**
     * Connects to the receiver's raw TCP endpoint, performs the [TcpFrame.Auth] handshake,
     * and validates the peer's acceptance.
     */
    public suspend fun connectAndAuthenticate(
        host: String,
        port: Int,
        transferId: String,
        authToken: String,
        senderDeviceId: String,
        route: TransferRoute,
        /** `(saltA, saltB) -> 64 bytes` from the session exporter (`Vtc3.keyDeriver`). */
        deriveKeys: (ByteArray, ByteArray) -> ByteArray,
    ): Boolean = withContext(dispatchers.io) {
        isClosed = false
        pendingChunk.set(null)
        val connectStartTime = System.currentTimeMillis()
        VeyroLog.i(TAG) { "[$transferId] [tcp-sender:connect-start] Dialing raw TCP ($route) at $host:$port (timeout: ${connectTimeoutMs}ms)..." }

        val s = Socket().apply {
            tcpNoDelay = true
            sendBufferSize = 1024 * 1024
            receiveBufferSize = 512 * 1024
            soTimeout = socketTimeoutMs
        }
        socket = s
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val connectElapsedMs = System.currentTimeMillis() - connectStartTime
            VeyroLog.i(TAG) {
                "[$transferId] [tcp-sender:connect-success] Socket connected to $host:$port in ${connectElapsedMs}ms. Local socket: ${s.localSocketAddress}"
            }

            val out = BufferedOutputStream(s.getOutputStream(), 512 * 1024)
            val inp = BufferedInputStream(s.getInputStream(), 256 * 1024)
            output = out
            input = inp

            // Fresh salts both ways, so this attempt's keys are new (ADR-058, 04-PROTOCOL.md 7.3).
            val opened = Vtc3.openAsSender(inp, out, transferId, deriveKeys)
            channel = opened
            VeyroLog.i(TAG) { "[$transferId] [tcp-sender:auth-send] Sending VTC3 Auth frame (route=$route)..." }
            writeMutex.withLock {
                opened.writer.write(
                    TcpFrame.Auth(
                        seq = 0L,
                        transferId = transferId,
                        authToken = authToken,
                        senderDeviceId = senderDeviceId,
                        route = route,
                    ),
                )
            }

            val authStartTime = System.currentTimeMillis()
            val ackFrame = opened.reader.readOrNull() ?: throw java.io.EOFException("Receiver closed before AuthAck")
            val authElapsedMs = System.currentTimeMillis() - authStartTime
            if (ackFrame !is TcpFrame.AuthAck) {
                throw IOException("Expected AuthAck but received: ${ackFrame.javaClass.simpleName}")
            }
            if (!ackFrame.ok) {
                throw SecurityException("Receiver rejected TCP authentication: ${ackFrame.reason}")
            }

            activeRoute = route
            VeyroLog.i(TAG) {
                "[$transferId] [tcp-sender:auth-success] VTC3 AuthAck confirmed in ${authElapsedMs}ms (route=$route)"
            }
            true
        } catch (t: Throwable) {
            val totalElapsedMs = System.currentTimeMillis() - connectStartTime
            val category = when (t) {
                is java.net.SocketTimeoutException -> "CONNECTION_TIMEOUT"
                is java.net.ConnectException -> "CONNECTION_REFUSED_OR_UNREACHABLE"
                is java.net.NoRouteToHostException -> "NO_ROUTE_TO_HOST"
                is java.net.UnknownHostException -> "UNKNOWN_HOST"
                is SecurityException -> "AUTH_REJECTED"
                is kotlinx.coroutines.CancellationException -> "CANCELLED"
                else -> "IO_ERROR"
            }
            VeyroLog.e(TAG, t) {
                "[$transferId] [tcp-sender:connect-failed] category=$category in ${totalElapsedMs}ms for $host:$port ($route): ${t.javaClass.simpleName} - ${t.message}"
            }
            close()
            throw t
        }
    }

    public suspend fun sendItemBegin(
        transferId: String,
        itemIndex: Int,
        sizeBytes: Long,
        chunkCount: Int,
        chunkSize: Int,
    ): Unit = withContext(dispatchers.io) {
        awaitPendingChunk()
        val writer = channel?.writer ?: throw IOException("Socket not connected")
        VeyroLog.i(TAG) { "[$transferId] [tcp-sender:item-begin-send] Item #$itemIndex (size=$sizeBytes, chunks=$chunkCount)" }
        writeMutex.withLock {
            writer.write(
                TcpFrame.ItemBegin(
                    seq = 0L,
                    transferId = transferId,
                    itemIndex = itemIndex,
                    sizeBytes = sizeBytes,
                    chunkCount = chunkCount,
                    chunkSize = chunkSize,
                ),
            )
        }
    }

    public suspend fun sendChunk(
        transferId: String,
        itemIndex: Int,
        chunkIndex: Int,
        data: ByteArray,
        flush: Boolean = false,
        metrics: TransferThroughputMetrics? = null,
    ): Unit = withContext(dispatchers.io) {
        val writer = channel?.writer ?: throw IOException("Socket not connected")
        val frame = TcpFrame.Chunk(
            seq = 0L,
            transferId = transferId,
            itemIndex = itemIndex,
            chunkIndex = chunkIndex,
            data = data,
        )
        // Write-behind (NFR-3): chunk N is sealed and written while the caller reads and hashes
        // chunk N+1. At most one chunk is in flight and the next call waits for it, so records
        // keep their order; a failure surfaces on the next send. The caller gives up [data]: it
        // must not reuse or change the array, or the sealed bytes would not match its hash.
        awaitPendingChunk()
        pendingChunk.set(
            pipelineScope.async {
                writeMutex.withLock {
                    if (metrics != null) {
                        metrics.recordSocketWrite(data.size.toLong()) {
                            writer.write(frame, flush = flush)
                        }
                        if (flush) metrics.recordFlush()
                    } else {
                        writer.write(frame, flush = flush)
                    }
                }
            },
        )
    }

    /** Waits for the chunk write in flight, if any, and rethrows its failure as an [IOException]. */
    private suspend fun awaitPendingChunk() {
        val pending = pendingChunk.get() ?: return
        try {
            pending.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("TCP chunk write was cancelled", e)
        } finally {
            pendingChunk.compareAndSet(pending, null)
        }
    }

    public suspend fun sendItemEnd(
        transferId: String,
        itemIndex: Int,
        sha256Hex: String,
        metrics: TransferThroughputMetrics? = null,
    ): Unit = withContext(dispatchers.io) {
        awaitPendingChunk()
        val writer = channel?.writer ?: throw IOException("Socket not connected")
        VeyroLog.i(TAG) { "[$transferId] [tcp-sender:item-end-send] Item #$itemIndex" }
        writeMutex.withLock {
            writer.write(
                TcpFrame.ItemEnd(
                    seq = 0L,
                    transferId = transferId,
                    itemIndex = itemIndex,
                    sha256Hex = sha256Hex,
                ),
                flush = true,
            )
            metrics?.recordFlush()
        }
        VeyroLog.i(TAG) { "[$transferId] [tcp-sender:item-end-sent] Item #$itemIndex flushed to TCP stream" }
    }

    public suspend fun sendTransferEnd(
        transferId: String,
        completed: Boolean,
        metrics: TransferThroughputMetrics? = null,
    ): Unit = withContext(dispatchers.io) {
        awaitPendingChunk()
        val writer = channel?.writer ?: run {
            VeyroLog.w(TAG) { "[$transferId] Data channel not open; cannot send TransferEnd" }
            return@withContext
        }
        VeyroLog.i(TAG) { "[$transferId] [tcp-sender:transfer-end-send] Sending VTC3 TransferEnd (completed=$completed)..." }
        writeMutex.withLock {
            try {
                writer.write(
                    TcpFrame.TransferEnd(
                        seq = 0L,
                        transferId = transferId,
                        completed = completed,
                    ),
                    flush = true,
                )
                metrics?.recordFlush()
                VeyroLog.i(TAG) { "[$transferId] [tcp-sender:transfer-end-sent] TransferEnd flushed to socket (completed=$completed)" }
            } catch (t: Throwable) {
                VeyroLog.e(TAG, t) { "[$transferId] [tcp-sender:transfer-end-failed] Failed to write TransferEnd: ${t.message}" }
                throw t
            }
        }
    }

    /**
     * Gracefully performs TCP half-close on sender side:
     * 1. Flushes output stream.
     * 2. Calls socket.shutdownOutput() to send FIN packet to receiver.
     * 3. Awaits peer socket closure / EOF with a bounded timeout (up to [timeoutMs]).
     */
    public suspend fun gracefulShutdown(timeoutMs: Long = 2000L): Unit = withContext(dispatchers.io) {
        val s = socket ?: return@withContext
        try {
            runCatching { awaitPendingChunk() }
            writeMutex.withLock {
                runCatching { output?.flush() }
            }
            if (!s.isClosed && s.isConnected && !s.isOutputShutdown) {
                VeyroLog.i(TAG) { "[tcp-sender:shutdown-output] Shutting down socket output (half-close FIN sent)" }
                runCatching { s.shutdownOutput() }
            }

            // With an inbound reader running it owns the input stream: wait for it to see EOF
            // instead of reading concurrently.
            inboundReader?.let { reader ->
                if (withTimeoutOrNull(timeoutMs) { reader.join() } != null) {
                    VeyroLog.i(TAG) { "[tcp-sender:peer-eof-confirmed] Peer closed socket gracefully (clean EOF)" }
                }
                return@withContext
            }

            // Drain peer EOF / close with timeout
            val inp = input
            if (inp != null && !s.isClosed) {
                val buf = ByteArray(256)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline && !s.isClosed) {
                    if (s.isInputShutdown) break
                    val r = runCatching { inp.read(buf) }.getOrDefault(-1)
                    if (r < 0) {
                        VeyroLog.i(TAG) { "[tcp-sender:peer-eof-confirmed] Peer closed socket gracefully (clean EOF)" }
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            VeyroLog.d(TAG) { "[tcp-sender:graceful-shutdown-notice] Note during graceful shutdown: ${t.message}" }
        }
    }

    public suspend fun sendCancel(
        transferId: String,
        reason: String,
    ): Unit = withContext(dispatchers.io) {
        try {
            awaitPendingChunk()
        } catch (e: IOException) {
            // The chunk in flight failed; the Cancel below is best effort either way.
        }
        val writer = channel?.writer ?: return@withContext
        VeyroLog.i(TAG) { "[$transferId] [tcp-sender:cancel-send] Sending TCP Cancel frame (reason=$reason)" }
        writeMutex.withLock {
            runCatching {
                writer.write(
                    TcpFrame.Cancel(
                        seq = 0L,
                        transferId = transferId,
                        reason = reason,
                    ),
                    flush = true,
                )
            }
        }
    }

    /**
     * Forcefully and immediately closes the active TCP socket to unblock any blocking socket I/O.
     */
    public fun closeSocketImmediately() {
        isClosed = true
        VeyroLog.i(TAG) { "[tcp-sender:socket-close] Force closing TCP socket immediately to unblock pending I/O" }
        runCatching { socket?.close() }
    }

    public fun close(reason: String = "Normal closure") {
        // closeSocketImmediately only closes the socket; the channel (both framers) and the
        // streams are still released here (review issue 7). Everything below is idempotent.
        if (isClosed && socket == null && channel == null) return
        isClosed = true
        VeyroLog.i(TAG) { "[tcp-sender:socket-close] Initiating socket close ($reason)" }
        // A chunk write still in flight may be blocked on a full socket while holding the stream:
        // close the socket first so it fails at once, instead of flushing behind it.
        if (pendingChunk.getAndSet(null)?.isActive == true) runCatching { socket?.close() }
        runCatching { output?.flush() }
        runCatching { output?.close() }
        runCatching { input?.close() }
        runCatching { socket?.close() }
        runCatching { channel?.close() }
        channel = null
        output = null
        input = null
        socket = null
        activeRoute = null
    }
}
