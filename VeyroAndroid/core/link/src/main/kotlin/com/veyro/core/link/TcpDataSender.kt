package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.TransferRoute
import com.veyro.core.protocol.tcp.TcpFrame
import com.veyro.core.protocol.tcp.TcpFrameCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Sender-side raw TCP data engine.
 *
 * Implements a single ordered writer over the TCP socket stream, bounded chunk streaming,
 * monotonic frame sequencing, and connection lifecycle management.
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
    private val seqGenerator = AtomicLong(1L)

    @Volatile
    public var activeRoute: TransferRoute? = null
        private set

    @Volatile
    private var isClosed = false

    public val isConnected: Boolean
        get() = !isClosed && socket?.isConnected == true && socket?.isClosed == false

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
    ): Boolean = withContext(dispatchers.io) {
        isClosed = false
        seqGenerator.set(1L)
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

            val authSeq = seqGenerator.getAndIncrement()
            VeyroLog.i(TAG) { "[$transferId] [tcp-sender:auth-send] Sending VTC1 Auth frame (seq=$authSeq, route=$route)..." }
            writeMutex.withLock {
                TcpFrameCodec.writeFrame(
                    out,
                    TcpFrame.Auth(
                        seq = authSeq,
                        transferId = transferId,
                        authToken = authToken,
                        senderDeviceId = senderDeviceId,
                        route = route,
                    ),
                )
            }

            val authStartTime = System.currentTimeMillis()
            val ackFrame = TcpFrameCodec.readFrame(inp)
            val authElapsedMs = System.currentTimeMillis() - authStartTime
            if (ackFrame !is TcpFrame.AuthAck) {
                throw IOException("Expected AuthAck but received: ${ackFrame.javaClass.simpleName}")
            }
            if (!ackFrame.ok) {
                throw SecurityException("Receiver rejected TCP authentication: ${ackFrame.reason}")
            }

            activeRoute = route
            VeyroLog.i(TAG) {
                "[$transferId] [tcp-sender:auth-success] VTC1 AuthAck confirmed in ${authElapsedMs}ms (route=$route)"
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
        val out = output ?: throw IOException("Socket not connected")
        val seq = seqGenerator.getAndIncrement()
        VeyroLog.i(TAG) { "[$transferId] [tcp-sender:item-begin-send] Item #$itemIndex (seq=$seq, size=$sizeBytes, chunks=$chunkCount)" }
        writeMutex.withLock {
            TcpFrameCodec.writeFrame(
                out,
                TcpFrame.ItemBegin(
                    seq = seq,
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
        val out = output ?: throw IOException("Socket not connected")
        val seq = seqGenerator.getAndIncrement()
        writeMutex.withLock {
            if (metrics != null) {
                metrics.recordSocketWrite(data.size.toLong()) {
                    TcpFrameCodec.writeFrame(
                        out,
                        TcpFrame.Chunk(
                            seq = seq,
                            transferId = transferId,
                            itemIndex = itemIndex,
                            chunkIndex = chunkIndex,
                            data = data,
                        ),
                        autoFlush = flush,
                    )
                }
                if (flush) metrics.recordFlush()
            } else {
                TcpFrameCodec.writeFrame(
                    out,
                    TcpFrame.Chunk(
                        seq = seq,
                        transferId = transferId,
                        itemIndex = itemIndex,
                        chunkIndex = chunkIndex,
                        data = data,
                    ),
                    autoFlush = flush,
                )
            }
        }
    }

    public suspend fun sendItemEnd(
        transferId: String,
        itemIndex: Int,
        sha256Hex: String,
        metrics: TransferThroughputMetrics? = null,
    ): Unit = withContext(dispatchers.io) {
        val out = output ?: throw IOException("Socket not connected")
        val seq = seqGenerator.getAndIncrement()
        VeyroLog.i(TAG) { "[$transferId] [tcp-sender:item-end-send] Item #$itemIndex (seq=$seq, sha256=$sha256Hex)" }
        writeMutex.withLock {
            TcpFrameCodec.writeFrame(
                out,
                TcpFrame.ItemEnd(
                    seq = seq,
                    transferId = transferId,
                    itemIndex = itemIndex,
                    sha256Hex = sha256Hex,
                ),
                autoFlush = true,
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
        val out = output ?: run {
            VeyroLog.w(TAG) { "[$transferId] Output stream null; cannot send TransferEnd" }
            return@withContext
        }
        val seq = seqGenerator.getAndIncrement()
        VeyroLog.i(TAG) { "[$transferId] [tcp-sender:transfer-end-send] Sending VTC1 TransferEnd (seq=$seq, completed=$completed)..." }
        writeMutex.withLock {
            try {
                TcpFrameCodec.writeFrame(
                    out,
                    TcpFrame.TransferEnd(
                        seq = seq,
                        transferId = transferId,
                        completed = completed,
                    ),
                    autoFlush = true,
                )
                metrics?.recordFlush()
                VeyroLog.i(TAG) { "[$transferId] [tcp-sender:transfer-end-sent] TransferEnd flushed to socket (seq=$seq, completed=$completed)" }
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
            writeMutex.withLock {
                runCatching { output?.flush() }
            }
            if (!s.isClosed && s.isConnected && !s.isOutputShutdown) {
                VeyroLog.i(TAG) { "[tcp-sender:shutdown-output] Shutting down socket output (half-close FIN sent)" }
                runCatching { s.shutdownOutput() }
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
        val out = output ?: return@withContext
        val seq = seqGenerator.getAndIncrement()
        VeyroLog.i(TAG) { "[$transferId] [tcp-sender:cancel-send] Sending TCP Cancel frame (seq=$seq, reason=$reason)" }
        writeMutex.withLock {
            runCatching {
                TcpFrameCodec.writeFrame(
                    out,
                    TcpFrame.Cancel(
                        seq = seq,
                        transferId = transferId,
                        reason = reason,
                    ),
                )
                out.flush()
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
        if (isClosed) return
        isClosed = true
        VeyroLog.i(TAG) { "[tcp-sender:socket-close] Initiating socket close ($reason)" }
        runCatching { output?.flush() }
        runCatching { output?.close() }
        runCatching { input?.close() }
        runCatching { socket?.close() }
        output = null
        input = null
        socket = null
        activeRoute = null
    }
}
