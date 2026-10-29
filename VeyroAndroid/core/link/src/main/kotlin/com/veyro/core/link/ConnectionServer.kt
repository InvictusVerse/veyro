package com.veyro.core.link

import com.veyro.core.common.VeyroLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Embedded WebSocket server on an OS-allocated port, reused across runs when free (ADR-052).
 *
 * ## Provenance and why WebSocket at all
 *
 * A port of `services/ecosystem/transport/WsServer.kt` from the author's Invox code. Veyro
 * previously used a raw TCP listener with hand-rolled length-prefixed framing. That compiled and
 * passed loopback tests but did not connect between two real devices, and the WebSocket stack here
 * is known to work on exactly these phones.
 *
 * The practical reasons it is better suited: the HTTP upgrade gives a clean connection boundary, the
 * protocol-level ping/pong detects a half-open connection that raw TCP will happily keep "open" for
 * minutes, and framing is handled by a library rather than by us.
 *
 * ## The lifecycle rule that is easy to get wrong
 *
 * Ktor closes the socket the moment the `webSocket {}` handler returns. So [onConnection] must block
 * for the entire lifetime of the conversation. A handler that sets something up and returns drops
 * the peer immediately - and the symptom is a connection that appears to succeed and then vanishes,
 * which is miserable to diagnose. This is called out in the Invox source too, having evidently been
 * learned there first.
 */
public class ConnectionServer(
    private val onConnection: suspend (session: DefaultWebSocketSession, remoteHost: String?, localHost: String?) -> Unit,
    private val path: String = DEFAULT_PATH,
) {

    public companion object {
        private const val TAG = "ConnectionServer"
        public const val DEFAULT_PATH: String = "/veyro"

        /** Below this a preferred port is ignored: the OS never hands those out as ephemeral. */
        private const val MIN_PREFERRED_PORT = 1024
        private const val MAX_PORT = 65535
    }

    private var engine: EmbeddedServer<*, *>? = null

    @Volatile
    public var port: Int = 0
        private set

    /**
     * Binds and returns the port. Idempotent. [preferredPort] (the port of the previous run, ADR-052)
     * is tried first, so a phone that restarts comes back on the address its paired phones remember;
     * when it is taken or invalid, the OS picks one as before. The port is advertised either way.
     */
    public fun start(preferredPort: Int = 0): Int {
        engine?.let { return port }
        if (preferredPort in MIN_PREFERRED_PORT..MAX_PORT && isFree(preferredPort)) {
            val bound = runCatching { bind(preferredPort) }
                .onFailure { VeyroLog.i(TAG) { "Port $preferredPort not available (${it.javaClass.simpleName}); letting the OS pick" } }
                .getOrDefault(0)
            if (bound != 0) return bound
        }
        return bind(0)
    }

    private fun isFree(candidate: Int): Boolean =
        runCatching { java.net.ServerSocket(candidate).use { true } }.getOrDefault(false)

    private fun bind(requested: Int): Int {
        val server = embeddedServer(CIO, port = requested, host = "0.0.0.0") {
            install(ServerWebSockets) {
                // Protocol-level keepalive, a backstop to the application heartbeat in
                // PeerConnection, which already detects a dead connection (ADR-048). Kept slow:
                // each ping wakes the Wi-Fi radio, and the PC pings this socket every 20 s anyway.
                pingPeriodMillis = WS_PING_PERIOD_MS
                timeoutMillis = 40_000
                // Chunks are base64 inside a JSON envelope, so the frame ceiling has to clear the
                // largest chunk plus its ~33% encoding overhead plus the envelope.
                maxFrameSize = 24L * 1024 * 1024
                masking = false
            }
            routing {
                webSocket(path) {
                    val remoteHost = runCatching { call.request.local.remoteHost }.getOrNull()
                    val localHost = runCatching { call.request.local.localHost }.getOrNull()
                    VeyroLog.i(TAG) { "Peer connected from $remoteHost (local socket host: $localHost)" }
                    try {
                        // Blocks for the lifetime of the conversation. See the class note.
                        onConnection(this, remoteHost, localHost)
                    } catch (t: Throwable) {
                        VeyroLog.w(TAG, t) { "Connection handler threw" }
                    }
                }
            }
        }
        try {
            engine = server.start(wait = false)
            // Port 0 means the OS picked one; read it back so it can be advertised. This is why
            // Veyro needs no port configuration and has nothing to collide on.
            port = runBlocking {
                engine?.engine?.resolvedConnectors()?.firstOrNull()?.port ?: 0
            }
        } catch (t: Throwable) {
            runCatching { server.stop(0, 0) }
            engine = null
            port = 0
            throw t
        }
        VeyroLog.i(TAG) { "Listening on port $port at $path" }
        return port
    }

    public fun stop() {
        runCatching { engine?.stop(500, 1500) }
            .onFailure { VeyroLog.w(TAG, it) { "Stop failed" } }
        engine = null
        port = 0
    }

    public val isRunning: Boolean get() = engine != null && port != 0
}

/** Legacy alias for backwards compatibility. */
public typealias LinkServer = ConnectionServer

/**
 * Outbound WebSocket client.
 *
 * ## The TCP_NODELAY detail, which is not cosmetic
 *
 * OkHttp does not enable `TCP_NODELAY`, so small control frames sit in Nagle's buffer waiting for
 * more data to coalesce. On a request/response exchange - which is exactly what the preamble, the
 * pairing handshake and the offer/decision steps are - that adds up to 40 ms per round trip for no
 * reason, and the symptom ("it feels sluggish") does not point anywhere near the cause.
 *
 * Wrapping the socket factory is the only way to set it through OkHttp. Taken from the Invox code,
 * where the same fix is annotated as a latency bug that was tracked down the hard way.
 */
public open class ConnectionClient {

    private companion object {
        const val TAG = "ConnectionClient"
    }

    private val noDelayFactory = object : SocketFactory() {
        private val delegate = getDefault()
        private fun Socket.noDelay() = apply { tcpNoDelay = true }

        override fun createSocket(): Socket = delegate.createSocket().noDelay()

        override fun createSocket(host: String?, port: Int): Socket =
            delegate.createSocket(host, port).noDelay()

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int,
        ): Socket = delegate.createSocket(host, port, localHost, localPort).noDelay()

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            delegate.createSocket(host, port).noDelay()

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket = delegate.createSocket(address, port, localAddress, localPort).noDelay()
    }

    private val http = HttpClient(OkHttp) {
        install(WebSockets) {
            // Backstop only; PeerConnection's heartbeat is the liveness check (ADR-048). Each ping
            // wakes the Wi-Fi radio, and the PC already pings this socket every 20 s.
            pingIntervalMillis = WS_PING_PERIOD_MS
            // Note: maxFrameSize is intentionally omitted here. In Ktor's OkHttp engine adapter,
            // specifying any maxFrameSize != Long.MAX_VALUE throws WebSocketException("Max frame size switch is not supported in OkHttp engine.").
            // OkHttp manages framing buffers internally, while inbound frame limits are enforced
            // by ConnectionServer (CIO) and application bounds in PeerConnection.
        }
        engine {
            config {
                socketFactory(noDelayFactory)
                connectTimeout(10_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                writeTimeout(60_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    public open suspend fun connect(
        host: String,
        port: Int,
        path: String = ConnectionServer.DEFAULT_PATH,
    ): DefaultWebSocketSession {
        VeyroLog.d(TAG) { "Dialling ws://$host:$port$path" }
        return try {
            http.webSocketSession { url("ws", host, port, path) }
        } catch (t: Throwable) {
            VeyroLog.d(TAG) { "Dial to ws://$host:$port$path failed: ${t.javaClass.simpleName} (${t.message})" }
            throw t
        }
    }

    public open fun close() {
        runCatching { http.close() }
    }
}

/** Legacy alias for backwards compatibility. */
public typealias LinkClient = ConnectionClient

/** WebSocket ping period on both the server and the client side of a phone socket (ADR-048). */
private const val WS_PING_PERIOD_MS: Long = 60_000L
