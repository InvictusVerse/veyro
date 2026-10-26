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
 * Embedded WebSocket server on an OS-allocated ephemeral port.
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
    }

    private var engine: EmbeddedServer<*, *>? = null

    @Volatile
    public var port: Int = 0
        private set

    /** Binds to an ephemeral port and returns it. Idempotent. */
    public fun start(): Int {
        engine?.let { return port }

        val server = embeddedServer(CIO, port = 0, host = "0.0.0.0") {
            install(ServerWebSockets) {
                // Protocol-level keepalive, separate from the application heartbeat in PeerConnection.
                // This one detects a dead TCP connection; that one detects an unresponsive peer.
                pingPeriodMillis = 20_000
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
        engine = server.start(wait = false)

        // Port 0 means the OS picked one; read it back so it can be advertised. This is why Veyro
        // needs no port configuration and has nothing to collide on.
        port = runBlocking {
            engine?.engine?.resolvedConnectors()?.firstOrNull()?.port ?: 0
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
            pingIntervalMillis = 20_000
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
