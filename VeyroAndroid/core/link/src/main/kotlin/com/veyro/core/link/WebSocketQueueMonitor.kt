package com.veyro.core.link

import com.veyro.core.common.VeyroLog
import io.ktor.websocket.WebSocketSession
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Low-overhead runtime monitor for extracting the underlying outgoing WebSocket queue size.
 *
 * OkHttp's [okhttp3.WebSocket] maintains an internal queue with a hard limit of 16 MiB
 * ([okhttp3.internal.ws.RealWebSocket.MAX_QUEUE_SIZE]). If callers enqueue faster than
 * network throughput and exceed 16 MiB, OkHttp forcibly terminates the connection with code 1001
 * (CLOSE_CLIENT_GOING_AWAY).
 *
 * Ktor wraps OkHttp's WebSocket inside [io.ktor.client.plugins.websocket.DefaultClientWebSocketSession]
 * and [io.ktor.client.engine.okhttp.OkHttpWebsocketSession]. This monitor inspects the session
 * hierarchy once, caches the reflection path, and allows [PeerConnection] and [TransferRunner]
 * to query [okhttp3.WebSocket.queueSize] with microsecond latency to apply proactive in-flight backpressure.
 */
public object WebSocketQueueMonitor {

    private const val TAG = "WebSocketQueueMonitor"

    // Caches the sequence of fields leading to okhttp3.WebSocket for each concrete session class
    private val fieldPathCache = ConcurrentHashMap<Class<*>, List<Field>>()

    /**
     * Returns the number of bytes currently queued in the underlying transport, or null if the
     * session is not backed by OkHttp or the queue cannot be determined.
     */
    public fun getQueueSize(session: WebSocketSession?): Long? {
        if (session == null) return null
        return runCatching {
            val ws = findOkHttpWebSocket(session) ?: return null
            ws.queueSize()
        }.onFailure {
            VeyroLog.d(TAG) { "Failed to query queueSize: ${it.message}" }
        }.getOrNull()
    }

    private fun findOkHttpWebSocket(session: Any): okhttp3.WebSocket? {
        if (session is okhttp3.WebSocket) return session
        val sessionClass = session.javaClass

        // Fast path: use cached reflection path
        val cachedPath = fieldPathCache[sessionClass]
        if (cachedPath != null) {
            var current: Any? = session
            for (field in cachedPath) {
                if (current == null) break
                current = field.get(current)
            }
            if (current is okhttp3.WebSocket) return current
        }

        // Slow path: recursive discovery
        val path = mutableListOf<Field>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val found = discoverPath(session, path, visited, depth = 0)
        if (found != null) {
            fieldPathCache[sessionClass] = path.toList()
            return found
        }
        return null
    }

    private fun discoverPath(
        obj: Any?,
        path: MutableList<Field>,
        visited: MutableSet<Any>,
        depth: Int,
    ): okhttp3.WebSocket? {
        if (obj == null || depth > 4 || !visited.add(obj)) return null
        if (obj is okhttp3.WebSocket) return obj

        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                field.isAccessible = true
                val child = runCatching { field.get(obj) }.getOrNull() ?: continue

                if (child is okhttp3.WebSocket) {
                    path.add(field)
                    return child
                }

                val typeName = child.javaClass.name
                if (child is WebSocketSession ||
                    typeName.contains("WebSocket", ignoreCase = true) ||
                    typeName.contains("OkHttp", ignoreCase = true) ||
                    typeName.contains("Session", ignoreCase = true)
                ) {
                    path.add(field)
                    val found = discoverPath(child, path, visited, depth + 1)
                    if (found != null) return found
                    path.removeAt(path.lastIndex)
                }
            }
            clazz = clazz.superclass
        }
        return null
    }
}
