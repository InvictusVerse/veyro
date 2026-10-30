package com.veyro.core.link.media

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.link.PeerConnection
import com.veyro.core.link.SpeakerCaptureRules
import com.veyro.core.protocol.media.Vms1
import com.veyro.core.protocol.media.Vms1Channel
import com.veyro.core.protocol.media.Vms1Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Where the PC's sound goes on the phone. Called on the socket's reader thread. */
public interface PcAudioSink {
    public fun onAudioConfig(config: Vms1.AudioConfig)

    /** Whole s16le sample frames; [samples] belongs to the sink. */
    public fun onAudio(ptsUs: Long, samples: ByteArray)
}

/**
 * The phone's end of a VMS1 speaker socket (ADR-073): dialled to the PC like a mirroring socket
 * (source 3), authenticated on the control session's exporter; afterwards only the PC sends,
 * `AUDIO_CONFIG` and `AUDIO`, and the phone hands them to a [PcAudioSink]. Sound is user data and
 * is never logged.
 */
public class Vms1SpeakerLink private constructor(
    private val socket: Socket,
    private val channel: Vms1Channel,
) {

    public companion object {
        private const val TAG = "Vms1Speaker"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_TIMEOUT_MS = 10_000

        /** Dials [host]:[port] and runs the phone's handshake for [sid]. Throws [IOException] on failure. */
        @Throws(IOException::class)
        public suspend fun dial(
            connection: PeerConnection,
            host: String,
            port: Int,
            sid: ByteArray,
            sink: PcAudioSink,
            scope: CoroutineScope,
            dispatchers: AppDispatchers,
        ): Vms1SpeakerLink = withContext(dispatchers.io) {
            val pcId = connection.peerDeviceId ?: throw IOException("the session has no peer id")
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val channel = Vms1Channel.connectAsPhone(
                    BufferedInputStream(socket.getInputStream()),
                    BufferedOutputStream(socket.getOutputStream()),
                    Vms1.SOURCE_SPEAKER,
                    sid,
                    connection.localDeviceId,
                    pcId,
                    connection::exportKey,
                )
                socket.soTimeout = 0
                VeyroLog.i(TAG) { "Speaker socket authenticated" }
                Vms1SpeakerLink(socket, channel).also { it.read(sink, scope, dispatchers) }
            } catch (e: Throwable) {
                runCatching { socket.close() }
                if (e is IOException) throw e
                throw IOException("speaker handshake failed: ${e.javaClass.simpleName}", e)
            }
        }
    }

    private val ended = CompletableDeferred<String>()
    private val closing = AtomicBoolean(false)

    /** Why the socket ended: the PC's CLOSE reason, or `network`. */
    public val closed: Deferred<String> get() = ended

    private fun read(sink: PcAudioSink, scope: CoroutineScope, dispatchers: AppDispatchers) {
        scope.launch(dispatchers.io) {
            val reason = try {
                var why = "network"
                while (true) {
                    when (val frame = channel.reader.read()) {
                        null -> break
                        is Vms1Frame.AudioConfig -> sink.onAudioConfig(frame.config)
                        is Vms1Frame.Audio -> sink.onAudio(frame.ptsUs, frame.samples)
                        is Vms1Frame.Close -> {
                            why = frame.close.reason.takeIf { it in SpeakerCaptureRules.SPEAKER_REASONS } ?: "error"
                            break
                        }
                        else -> break
                    }
                }
                why
            } catch (e: Exception) {
                if (!closing.get()) VeyroLog.w(TAG) { "Speaker socket ended: ${e.javaClass.simpleName}" }
                "network"
            }
            ended.complete(reason)
            close(null)
        }
    }

    /** Tells the PC [reason] when given (best effort), closes the socket and wipes the keys. Idempotent. */
    public fun close(reason: String?) {
        if (!closing.compareAndSet(false, true)) return
        ended.complete(reason ?: "network")
        if (reason != null) runCatching {
            channel.writer.writeClose(if (reason == "user") 0 else 1, reason)
            channel.writer.flush()
        }
        runCatching { socket.close() }
        channel.close()
    }
}
