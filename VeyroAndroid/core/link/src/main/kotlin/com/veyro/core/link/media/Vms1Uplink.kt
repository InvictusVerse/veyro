package com.veyro.core.link.media

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.link.PeerConnection
import com.veyro.core.link.StreamRules
import com.veyro.core.protocol.media.MediaSendQueue
import com.veyro.core.protocol.media.MediaSink
import com.veyro.core.protocol.media.Vms1
import com.veyro.core.protocol.media.Vms1Channel
import com.veyro.core.protocol.media.Vms1Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's end of one VMS1 media socket (ADR-066, 04-PROTOCOL.md 7.5): dialled to the PC for
 * one `sid`, authenticated with keys from the control session's exporter, then fed by a capture
 * through [MediaSink]. A video writer and an audio writer drain the [MediaSendQueue] (7.5.6), so
 * a slow network drops video instead of stalling capture, and audio goes out between video
 * fragments. A reader watches for the PC's `CLOSE` or the socket ending. Sockets stay in
 * `:core:link`; frames and samples are user data and are never logged.
 */
public class Vms1Uplink private constructor(
    private val socket: Socket,
    private val channel: Vms1Channel,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
) : MediaSink {

    public companion object {
        private const val TAG = "Vms1Uplink"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val SEND_BUFFER_BYTES = 256 * 1024
        private const val TAKE_TIMEOUT_MS = 1_000L
        private const val CLOSE_GRACE_MS = 500L

        /**
         * Dials [host]:[port] and runs the phone's handshake for [sid] on [connection]'s keys.
         * Throws [IOException] when the PC cannot be reached or does not accept the session.
         */
        @Throws(IOException::class)
        public suspend fun dial(
            connection: PeerConnection,
            host: String,
            port: Int,
            sid: ByteArray,
            scope: CoroutineScope,
            dispatchers: AppDispatchers,
        ): Vms1Uplink = withContext(dispatchers.io) {
            val pcId = connection.peerDeviceId ?: throw IOException("the session has no peer id")
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                // A small kernel buffer: the queue, not the socket, decides what is dropped.
                socket.sendBufferSize = SEND_BUFFER_BYTES
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val channel = Vms1Channel.connectAsPhone(
                    BufferedInputStream(socket.getInputStream()),
                    BufferedOutputStream(socket.getOutputStream(), 128 * 1024),
                    Vms1.SOURCE_SCREEN,
                    sid,
                    connection.localDeviceId,
                    pcId,
                    connection::exportKey,
                )
                socket.soTimeout = 0
                VeyroLog.i(TAG) { "Media socket authenticated" }
                Vms1Uplink(socket, channel, scope, dispatchers).also { it.startPumps() }
            } catch (e: Throwable) {
                runCatching { socket.close() }
                if (e is IOException) throw e
                throw IOException("media handshake failed: ${e.javaClass.simpleName}", e)
            }
        }
    }

    private val queue = MediaSendQueue()
    private val ended = CompletableDeferred<String>()
    private val closing = AtomicBoolean(false)

    /** Why the socket ended: the PC's `CLOSE` reason (a 6.9 reason), or `network`. */
    public val closed: Deferred<String> get() = ended

    /** Video access units dropped because the network was behind. */
    public val droppedUnits: Long get() = queue.droppedUnits

    override fun videoConfig(config: Vms1.VideoConfig) {
        queue.offerConfig(config)
    }

    override fun accessUnit(ptsUs: Long, data: ByteArray, length: Int, key: Boolean): Boolean =
        queue.offerUnit(ptsUs, data, length, key)

    override fun audioConfig(config: Vms1.AudioConfig) {
        queue.offerAudioConfig(config)
    }

    override fun audio(ptsUs: Long, samples: ByteArray, length: Int) {
        queue.offerAudio(ptsUs, samples, length)
    }

    private fun startPumps() {
        val writer = channel.writer
        scope.launch(dispatchers.io) {
            loop("video") {
                when (val item = queue.takeVideo(TAKE_TIMEOUT_MS)) {
                    null -> Unit
                    is MediaSendQueue.VideoItem.Config -> {
                        writer.writeVideoConfig(item.config)
                        writer.flush()
                    }
                    is MediaSendQueue.VideoItem.Frame ->
                        writer.writeAccessUnit(item.ptsUs, item.data, 0, item.length, item.key, item.discontinuity)
                }
            }
        }
        scope.launch(dispatchers.io) {
            loop("audio") {
                when (val item = queue.takeAudio(TAKE_TIMEOUT_MS)) {
                    null -> Unit
                    is MediaSendQueue.AudioItem.Config -> {
                        writer.writeAudioConfig(item.config)
                        writer.flush()
                    }
                    is MediaSendQueue.AudioItem.Samples -> writer.writeAudio(item.ptsUs, item.data, 0, item.length)
                }
            }
        }
        scope.launch(dispatchers.io) {
            // The PC sends nothing after AUTH_ACK but CLOSE; anything else is a protocol error.
            val reason = try {
                when (val frame = channel.reader.read()) {
                    is Vms1Frame.Close -> frame.close.reason.takeIf { it in StreamRules.REASONS } ?: "error"
                    else -> "network"
                }
            } catch (e: Exception) {
                "network"
            }
            end(reason)
        }
    }

    private inline fun loop(name: String, step: () -> Unit) {
        try {
            while (!closing.get()) step()
        } catch (e: Exception) {
            if (!closing.get()) {
                VeyroLog.w(TAG) { "Media $name writer stopped: ${e.javaClass.simpleName}" }
                end("network")
            }
        }
    }

    private fun end(reason: String) {
        if (ended.complete(reason)) close(null)
    }

    /**
     * Ends the socket: tells the PC [reason] in a `CLOSE` when given (best effort, a stalled
     * socket gets [CLOSE_GRACE_MS]), then closes it and wipes the keys. Idempotent.
     */
    public fun close(reason: String?) {
        if (!closing.compareAndSet(false, true)) return
        ended.complete(reason ?: "network")
        queue.close()
        scope.launch(dispatchers.io) {
            if (reason != null) {
                launch(dispatchers.io) {
                    runCatching {
                        channel.writer.writeClose(if (reason == "user") 0 else 1, reason)
                        channel.writer.flush()
                    }
                }
                delay(CLOSE_GRACE_MS)
            }
            runCatching { socket.close() }
            channel.close()
        }
    }
}
