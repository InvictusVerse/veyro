package com.veyro.core.protocol.media

import com.veyro.core.protocol.AeadStreamException
import com.veyro.core.protocol.AeadStreamFramer
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/** One record read from a VMS1 socket. Byte arrays are copies the caller owns. */
public sealed interface Vms1Frame {
    public class Auth(public val auth: Vms1.Auth) : Vms1Frame
    public class AuthAck(public val status: Int) : Vms1Frame
    public class Close(public val close: Vms1.Close) : Vms1Frame
    public class VideoConfig(public val config: Vms1.VideoConfig) : Vms1Frame
    public class Video(public val flags: Int, public val ptsUs: Long?, public val auLength: Int?, public val data: ByteArray) : Vms1Frame
    public class AudioConfig(public val config: Vms1.AudioConfig) : Vms1Frame
    public class Audio(public val ptsUs: Long, public val samples: ByteArray) : Vms1Frame
}

/** Both directions of one VMS1 connection. [close] wipes the keys; it does not close the socket. */
public class Vms1Channel(public val writer: Vms1Writer, public val reader: Vms1Reader) : Closeable {

    override fun close() {
        writer.wipe()
        reader.wipe()
    }

    public companion object {
        private val random = SecureRandom()

        /**
         * The phone's side of the handshake (04-PROTOCOL.md 7.5.2): preface out, the PC's preface
         * in, keys from the session exporter, `AUTH` out, `AUTH_ACK` in. The caller sets a 10 s
         * socket timeout first. Any failure throws and leaves nothing usable.
         *
         * [exportKey] is the live control session's exporter (`PeerConnection.exportKey`).
         */
        @Throws(IOException::class)
        public fun connectAsPhone(
            input: InputStream,
            output: OutputStream,
            source: Int,
            sid: ByteArray,
            phoneId: String,
            pcId: String,
            exportKey: (String, ByteArray, Int) -> ByteArray,
        ): Vms1Channel {
            val saltPhone = ByteArray(Vms1.SALT_SIZE).also { random.nextBytes(it) }
            output.write(Vms1.encodePreface(source, sid, saltPhone))
            output.flush()
            val pcPreface = ByteArray(Vms1.PREFACE_SIZE)
            readFully(input, pcPreface, 0, pcPreface.size)
            val preface = Vms1.parsePreface(pcPreface)
            if (preface.source != source || !preface.sid.contentEquals(sid)) throw AeadStreamException("VMS1 preface for another session")
            val channel = fromOkm(
                input, output,
                exportKey(Vms1.exportLabel(source), Vms1.exportContext(source, sid, saltPhone, preface.salt), Vms1.OKM_SIZE),
                phone = true,
                source = source,
            )
            try {
                channel.writer.writeAuth(phoneId, pcId, source, sid)
                val ack = channel.reader.read()
                if (ack !is Vms1Frame.AuthAck || ack.status != 0) throw AeadStreamException("VMS1 AUTH not acknowledged")
                return channel
            } catch (e: Throwable) {
                channel.close()
                throw e
            }
        }

        /**
         * Splits [okm] into the two direction keys and wipes it. [phone] chooses which one this
         * side seals with. Public for the PC-side test listener and the vectors.
         */
        public fun fromOkm(input: InputStream, output: OutputStream, okm: ByteArray, phone: Boolean, source: Int = Vms1.SOURCE_SCREEN): Vms1Channel {
            try {
                if (okm.size != Vms1.OKM_SIZE) throw AeadStreamException("VMS1 key derivation returned ${okm.size} bytes")
                val up = okm.copyOfRange(0, 32)
                val down = okm.copyOfRange(32, 64)
                try {
                    return if (phone) {
                        Vms1Channel(Vms1Writer(output, up, Vms1.STREAM_UP), Vms1Reader(input, down, Vms1.STREAM_DOWN, fromPhone = false, source = source))
                    } else {
                        Vms1Channel(Vms1Writer(output, down, Vms1.STREAM_DOWN), Vms1Reader(input, up, Vms1.STREAM_UP, fromPhone = true, source = source))
                    }
                } finally {
                    up.fill(0)
                    down.fill(0)
                }
            } finally {
                okm.fill(0)
            }
        }

        internal fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
            var total = 0
            while (total < length) {
                val read = input.read(buffer, offset + total, length - total)
                if (read < 0) throw EOFException("VMS1 stream ended inside a record")
                total += read
            }
        }
    }
}

/**
 * The only writer of one VMS1 direction. Each record is sealed and written under one lock, so
 * a video thread and an audio thread may both write and no nonce is ever used twice. An access
 * unit's fragments are written one record at a time, so audio can slip in between them; there
 * must be only one video thread, so no other access unit does.
 */
public class Vms1Writer(private val output: OutputStream, key: ByteArray, streamId: Int) {

    private val framer = AeadStreamFramer(key, streamId, AeadStreamFramer.Role.SEAL)
    private val record = ByteArray(Vms1.HEADER_SIZE + Vms1.MAX_PLAINTEXT + Vms1.TAG_SIZE)
    private val startPrefix = ByteArray(Vms1.START_PREFIX)
    private var audioFrameBytes = 0
    private var videoConfigured = false

    /** Records sealed so far. */
    public val sent: Long @Synchronized get() = framer.nextCounter

    @Throws(IOException::class)
    public fun writeAuth(phoneId: String, pcId: String, source: Int, sid: ByteArray) {
        val plain = Vms1.encodeAuth(phoneId, pcId, source, sid)
        writeRecord(Vms1.KIND_AUTH, 0, plain, 0, plain.size, flush = true)
    }

    @Throws(IOException::class)
    public fun writeAuthAck(status: Int = 0) {
        writeRecord(Vms1.KIND_AUTH_ACK, 0, byteArrayOf(status.toByte()), 0, 1, flush = true)
    }

    @Throws(IOException::class)
    public fun writeClose(code: Int, reason: String) {
        val plain = Vms1.encodeClose(code, reason)
        writeRecord(Vms1.KIND_CLOSE, 0, plain, 0, plain.size, flush = true)
    }

    @Throws(IOException::class)
    public fun writeVideoConfig(config: Vms1.VideoConfig) {
        val plain = Vms1.encodeVideoConfig(config)
        synchronized(this) {
            writeRecord(Vms1.KIND_VIDEO_CONFIG, 0, plain, 0, plain.size, flush = false)
            videoConfigured = true
        }
    }

    @Throws(IOException::class)
    public fun writeAudioConfig(config: Vms1.AudioConfig) {
        val plain = Vms1.encodeAudioConfig(config)
        synchronized(this) {
            writeRecord(Vms1.KIND_AUDIO_CONFIG, 0, plain, 0, plain.size, flush = false)
            audioFrameBytes = config.frameBytes
        }
    }

    /**
     * One access unit, in fragments. [ptsUs] is on the phone's monotonic clock, the same clock
     * as [writeAudio], so the PC can line the two up. Flushed after the last fragment.
     */
    @Throws(IOException::class)
    public fun writeAccessUnit(ptsUs: Long, data: ByteArray, offset: Int, length: Int, key: Boolean, discontinuity: Boolean) {
        check(videoConfigured) { "VIDEO_CONFIG must come before the first access unit" }
        require(ptsUs >= 0 && offset >= 0 && length >= 0 && offset + length <= data.size) { "access unit bounds" }
        for (fragment in AccessUnitFragmenter.plan(length, key, discontinuity)) {
            val last = fragment.flags and Vms1.FLAG_END != 0
            if (fragment.flags and Vms1.FLAG_START != 0) {
                synchronized(this) {
                    Vms1.writeLong(startPrefix, 0, ptsUs)
                    Vms1.writeInt(startPrefix, 8, length)
                    writeTwoPart(Vms1.KIND_VIDEO, fragment.flags, startPrefix, data, offset + fragment.offset, fragment.length, last)
                }
            } else {
                writeRecord(Vms1.KIND_VIDEO, fragment.flags, data, offset + fragment.offset, fragment.length, flush = last)
            }
        }
    }

    /** Whole interleaved s16le sample frames, 1..16 384 bytes, after [writeAudioConfig]. */
    @Throws(IOException::class)
    public fun writeAudio(ptsUs: Long, samples: ByteArray, offset: Int, length: Int) {
        require(ptsUs >= 0 && offset >= 0 && length in 1..Vms1.MAX_AUDIO_BYTES && offset + length <= samples.size) { "audio bounds" }
        synchronized(this) {
            check(audioFrameBytes > 0) { "AUDIO_CONFIG must come before the first audio packet" }
            require(length % audioFrameBytes == 0) { "audio must be whole sample frames" }
            Vms1.writeLong(startPrefix, 0, ptsUs)
            writeTwoPart(Vms1.KIND_AUDIO, 0, startPrefix, samples, offset, length, flush = true, headLen = 8)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    public fun flush() {
        output.flush()
    }

    @Synchronized
    private fun writeRecord(kind: Int, flags: Int, src: ByteArray, off: Int, len: Int, flush: Boolean) {
        val sealed = framer.seal(headerFor(kind, flags, len), Vms1.HEADER_SIZE, src, off, len, record, Vms1.HEADER_SIZE)
        output.write(record, 0, Vms1.HEADER_SIZE + sealed)
        if (flush) output.flush()
    }

    /** `head[0, headLen) || body[off, off+len)` as one record, without copying [body]. */
    private fun writeTwoPart(kind: Int, flags: Int, head: ByteArray, body: ByteArray, off: Int, len: Int, flush: Boolean, headLen: Int = head.size) {
        val sealed = framer.seal(headerFor(kind, flags, headLen + len), Vms1.HEADER_SIZE, head, headLen, body, off, len, record, Vms1.HEADER_SIZE)
        output.write(record, 0, Vms1.HEADER_SIZE + sealed)
        if (flush) output.flush()
    }

    private fun headerFor(kind: Int, flags: Int, plainLen: Int): ByteArray {
        if (plainLen > Vms1.MAX_PLAINTEXT) throw AeadStreamException("VMS1 record too large")
        Vms1.writeHeader(record, kind, flags, plainLen + Vms1.TAG_SIZE)
        return record
    }

    /** Not synchronized: a writer blocked on the socket must not hold up the close. */
    internal fun wipe() {
        framer.wipe()
    }
}

/** The only reader of one VMS1 direction. [fromPhone]: the records it expects come from the phone. */
public class Vms1Reader(
    private val input: InputStream,
    key: ByteArray,
    streamId: Int,
    private val fromPhone: Boolean,
    /** The socket's source: on a speaker socket the sound comes from the PC (ADR-073). */
    private val source: Int = Vms1.SOURCE_SCREEN,
) {

    private val framer = AeadStreamFramer(key, streamId, AeadStreamFramer.Role.OPEN)
    private val header = ByteArray(Vms1.HEADER_SIZE)
    private val sealed = ByteArray(Vms1.MAX_PLAINTEXT + Vms1.TAG_SIZE)
    private val plain = ByteArray(Vms1.MAX_PLAINTEXT)
    private var failed = false
    private var first = true

    /** The next record, or null on a clean end of stream at a record boundary. */
    @Synchronized
    @Throws(IOException::class)
    public fun read(): Vms1Frame? {
        if (failed) throw AeadStreamException("VMS1 stream failed earlier")
        try {
            val firstByte = input.read(header, 0, 1)
            if (firstByte < 0) return null
            Vms1Channel.readFully(input, header, 1, Vms1.HEADER_SIZE - 1)
            val kind = header[0].toInt() and 0xFF
            val flags = header[1].toInt() and 0xFF
            val len = Vms1.readInt(header, 2)
            Vms1.checkHeader(kind, flags, len, fromPhone, source)
            // The first record of each direction is the handshake one.
            val expectedFirst = if (fromPhone) Vms1.KIND_AUTH else Vms1.KIND_AUTH_ACK
            if (first != (kind == expectedFirst)) throw AeadStreamException("VMS1 handshake record out of order")
            Vms1Channel.readFully(input, sealed, 0, len)
            val n = framer.open(header, Vms1.HEADER_SIZE, sealed, 0, len, plain, 0)
            first = false
            return decode(kind, flags, n)
        } catch (e: IOException) {
            failed = true
            throw e
        } catch (e: RuntimeException) {
            failed = true
            throw AeadStreamException("VMS1 record is malformed", e)
        }
    }

    private fun decode(kind: Int, flags: Int, n: Int): Vms1Frame = when (kind) {
        Vms1.KIND_AUTH -> Vms1Frame.Auth(Vms1.decodeAuth(plain, n))
        Vms1.KIND_AUTH_ACK -> {
            if (n != 1) throw AeadStreamException("VMS1 AUTH_ACK has a bad length")
            Vms1Frame.AuthAck(plain[0].toInt() and 0xFF)
        }
        Vms1.KIND_CLOSE -> Vms1Frame.Close(Vms1.decodeClose(plain, n))
        Vms1.KIND_VIDEO_CONFIG -> Vms1Frame.VideoConfig(Vms1.decodeVideoConfig(plain, n))
        Vms1.KIND_AUDIO_CONFIG -> Vms1Frame.AudioConfig(Vms1.decodeAudioConfig(plain, n))
        Vms1.KIND_VIDEO -> if (flags and Vms1.FLAG_START != 0) {
            if (n < Vms1.START_PREFIX + 1) throw AeadStreamException("VMS1 START fragment is truncated")
            val pts = Vms1.readLong(plain, 0)
            val auLength = Vms1.readInt(plain, 8)
            if (pts < 0 || auLength !in 1..Vms1.MAX_ACCESS_UNIT) throw AeadStreamException("VMS1 START fragment out of range")
            Vms1Frame.Video(flags, pts, auLength, plain.copyOfRange(Vms1.START_PREFIX, n))
        } else {
            if (n > Vms1.MAX_FRAGMENT) throw AeadStreamException("VMS1 fragment too large")
            Vms1Frame.Video(flags, null, null, plain.copyOfRange(0, n))
        }
        Vms1.KIND_AUDIO -> {
            if (n < 9 || n - 8 > Vms1.MAX_AUDIO_BYTES) throw AeadStreamException("VMS1 AUDIO has a bad length")
            val pts = Vms1.readLong(plain, 0)
            if (pts < 0) throw AeadStreamException("VMS1 AUDIO pts out of range")
            Vms1Frame.Audio(pts, plain.copyOfRange(8, n))
        }
        else -> throw AeadStreamException("VMS1 record kind not allowed")
    }

    /** Not synchronized: a reader blocked on the socket must not hold up the close. */
    internal fun wipe() {
        framer.wipe()
        plain.fill(0)
    }
}
