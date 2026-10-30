package com.veyro.core.protocol.tcp

import com.veyro.core.protocol.AeadStreamException
import com.veyro.core.protocol.AeadStreamFramer
import com.veyro.core.protocol.ProtocolConstants
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * VTC3: the sealed raw-TCP data plane (ADR-058, 04-PROTOCOL.md 7.3).
 *
 * Every data-socket attempt opens with one cleartext stream-open record in each direction, each
 * carrying a fresh 16-byte salt. The per-attempt keys come from the session's key exporter with
 * both salts in the context, so the framer counters restart at 0 on every attempt without ever
 * reusing a (key, nonce) pair:
 *
 * ```
 * okm    = exportKey("veyro-vtc3", utf8(tid) || 0x00 || saltA || saltB, 64)
 * keyS2R = okm[0..32]   stream id 1, sender seals, receiver opens
 * keyR2S = okm[32..64]  stream id 2, receiver seals, sender opens
 * ```
 */
public object Vtc3 {

    public const val EXPORT_LABEL: String = ProtocolConstants.EXPORT_LABEL_VTC3
    public const val SALT_SIZE: Int = 16
    public const val STREAM_SENDER_TO_RECEIVER: Int = 1
    public const val STREAM_RECEIVER_TO_SENDER: Int = 2
    public const val OKM_SIZE: Int = 64

    private val random = SecureRandom()

    /** `(saltA, saltB) -> 64 bytes` from the session exporter for [transferId]. */
    public fun keyDeriver(
        exportKey: (String, ByteArray, Int) -> ByteArray,
        transferId: String,
    ): (ByteArray, ByteArray) -> ByteArray = { saltA, saltB ->
        exportKey(EXPORT_LABEL, exportContext(transferId, saltA, saltB), OKM_SIZE)
    }

    /** `utf8(tid) || 0x00 || saltA || saltB`. */
    public fun exportContext(transferId: String, saltA: ByteArray, saltB: ByteArray): ByteArray {
        require(saltA.size == SALT_SIZE && saltB.size == SALT_SIZE) { "VTC3 salts must be $SALT_SIZE bytes" }
        val tid = transferId.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArray(tid.size + 1 + 2 * SALT_SIZE)
        System.arraycopy(tid, 0, out, 0, tid.size)
        System.arraycopy(saltA, 0, out, tid.size + 1, SALT_SIZE)
        System.arraycopy(saltB, 0, out, tid.size + 1 + SALT_SIZE, SALT_SIZE)
        return out
    }

    /** The cleartext stream-open record: `"VTC3" | 0x09 | u16 tidLen | tid | u64 0 | u32 16 | salt`. */
    public fun streamOpenRecord(transferId: String, salt: ByteArray): ByteArray {
        require(salt.size == SALT_SIZE) { "VTC3 salt must be $SALT_SIZE bytes" }
        val tid = tidBytes(transferId)
        val out = ByteArray(4 + 1 + 2 + tid.size + 8 + 4 + SALT_SIZE)
        System.arraycopy(TcpFrameCodec.MAGIC, 0, out, 0, 4)
        out[4] = TcpFrameCodec.TYPE_STREAM_OPEN
        TcpFrameCodec.writeShortBE(out, 5, tid.size)
        System.arraycopy(tid, 0, out, 7, tid.size)
        TcpFrameCodec.writeLongBE(out, 7 + tid.size, 0L)
        TcpFrameCodec.writeIntBE(out, 15 + tid.size, SALT_SIZE)
        System.arraycopy(salt, 0, out, 19 + tid.size, SALT_SIZE)
        return out
    }

    /** The data-socket dialler: writes its salt first, then reads the receiver's. */
    @Throws(IOException::class)
    public fun openAsSender(
        input: InputStream,
        output: OutputStream,
        transferId: String,
        deriveKeys: (ByteArray, ByteArray) -> ByteArray,
    ): Vtc3Channel {
        val saltA = newSalt()
        output.write(streamOpenRecord(transferId, saltA))
        output.flush()
        val saltB = readStreamOpen(input, transferId)
        val okm = deriveKeys(saltA, saltB)
        return try {
            channel(input, output, transferId, okm, sender = true)
        } finally {
            okm.fill(0)
        }
    }

    /** The data-socket listener: reads the sender's salt first, then writes its own. */
    @Throws(IOException::class)
    public fun openAsReceiver(
        input: InputStream,
        output: OutputStream,
        transferId: String,
        deriveKeys: (ByteArray, ByteArray) -> ByteArray,
    ): Vtc3Channel {
        val saltA = readStreamOpen(input, transferId)
        val saltB = newSalt()
        output.write(streamOpenRecord(transferId, saltB))
        output.flush()
        val okm = deriveKeys(saltA, saltB)
        return try {
            channel(input, output, transferId, okm, sender = false)
        } finally {
            okm.fill(0)
        }
    }

    /** Reads and checks one stream-open record; returns the peer's salt. */
    @Throws(IOException::class)
    public fun readStreamOpen(input: InputStream, transferId: String): ByteArray {
        val expectedTid = tidBytes(transferId)
        val prefix = ByteArray(7)
        readFully(input, prefix, 0, 7)
        if (!magicMatches(prefix)) throw AeadStreamException("VTC3 stream-open has a bad magic")
        if (prefix[4] != TcpFrameCodec.TYPE_STREAM_OPEN) throw AeadStreamException("VTC3 stream-open expected")
        val tidLen = TcpFrameCodec.readShortBE(prefix, 5)
        if (tidLen != expectedTid.size) throw AeadStreamException("VTC3 stream-open for another transfer")
        val tid = ByteArray(tidLen)
        readFully(input, tid, 0, tidLen)
        if (!tid.contentEquals(expectedTid)) throw AeadStreamException("VTC3 stream-open for another transfer")
        val suffix = ByteArray(12)
        readFully(input, suffix, 0, 12)
        if (TcpFrameCodec.readLongBE(suffix, 0) != 0L) throw AeadStreamException("VTC3 stream-open seq must be 0")
        if (TcpFrameCodec.readIntBE(suffix, 8) != SALT_SIZE) throw AeadStreamException("VTC3 stream-open length must be 16")
        val salt = ByteArray(SALT_SIZE)
        readFully(input, salt, 0, SALT_SIZE)
        return salt
    }

    internal fun tidBytes(transferId: String): ByteArray {
        val tid = transferId.toByteArray(StandardCharsets.UTF_8)
        require(tid.size in 1..TcpFrameCodec.MAX_TRANSFER_ID_LENGTH) { "transfer id must be 1..256 bytes" }
        return tid
    }

    internal fun magicMatches(prefix: ByteArray): Boolean =
        prefix[0] == TcpFrameCodec.MAGIC[0] && prefix[1] == TcpFrameCodec.MAGIC[1] &&
            prefix[2] == TcpFrameCodec.MAGIC[2] && prefix[3] == TcpFrameCodec.MAGIC[3]

    @Throws(IOException::class)
    internal fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var total = 0
        while (total < length) {
            val read = input.read(buffer, offset + total, length - total)
            if (read < 0) throw EOFException("VTC3 stream ended inside a record")
            total += read
        }
    }

    private fun newSalt(): ByteArray = ByteArray(SALT_SIZE).also { random.nextBytes(it) }

    private fun channel(input: InputStream, output: OutputStream, transferId: String, okm: ByteArray, sender: Boolean): Vtc3Channel {
        if (okm.size != OKM_SIZE) throw AeadStreamException("VTC3 key derivation returned ${okm.size} bytes")
        val s2r = okm.copyOfRange(0, 32)
        val r2s = okm.copyOfRange(32, 64)
        try {
            val writer = if (sender) {
                Vtc3Writer(output, s2r, STREAM_SENDER_TO_RECEIVER, transferId)
            } else {
                Vtc3Writer(output, r2s, STREAM_RECEIVER_TO_SENDER, transferId)
            }
            val reader = if (sender) {
                Vtc3Reader(input, r2s, STREAM_RECEIVER_TO_SENDER, transferId)
            } else {
                Vtc3Reader(input, s2r, STREAM_SENDER_TO_RECEIVER, transferId)
            }
            return Vtc3Channel(writer, reader)
        } finally {
            s2r.fill(0)
            r2s.fill(0)
        }
    }
}

/** Both directions of one data-socket attempt. [close] wipes both framers; it does not close the socket. */
public class Vtc3Channel(public val writer: Vtc3Writer, public val reader: Vtc3Reader) : Closeable {
    override fun close() {
        writer.wipe()
        reader.wipe()
    }
}

/**
 * The only writer of one data-socket direction: allocates the counter, seals and writes under
 * one lock, so no caller can send two records with the same nonce (invariant 1).
 */
public class Vtc3Writer(
    private val output: OutputStream,
    key: ByteArray,
    streamId: Int,
    transferId: String,
) {
    private val framer = AeadStreamFramer(key, streamId, AeadStreamFramer.Role.SEAL)
    private val tid = Vtc3.tidBytes(transferId)
    private val headerLen = 4 + 1 + 2 + tid.size + 8 + 4
    private var plain = ByteArray(0)
    private var record = ByteArray(0)
    private val chunkMeta = ByteArray(12)

    /** Seals and writes [frame]. Its `seq` is ignored; the framer counter is written instead. */
    @Synchronized
    @Throws(IOException::class)
    public fun write(frame: TcpFrame, flush: Boolean = frame !is TcpFrame.Chunk) {
        val frameTid = frame.transferId.toByteArray(StandardCharsets.UTF_8)
        require(frameTid.contentEquals(tid)) { "frame belongs to another transfer" }
        val size = TcpFrameCodec.payloadSize(frame)
        if (size > TcpFrameCodec.MAX_PAYLOAD_SIZE) throw AeadStreamException("VTC3 frame too large")
        val total = headerLen + size + 16
        // Grown to the largest record seen and reused: no per-chunk allocation (FR-S5).
        if (record.size < total) record = ByteArray(total)

        System.arraycopy(TcpFrameCodec.MAGIC, 0, record, 0, 4)
        record[4] = TcpFrameCodec.frameTypeOf(frame)
        TcpFrameCodec.writeShortBE(record, 5, tid.size)
        System.arraycopy(tid, 0, record, 7, tid.size)
        TcpFrameCodec.writeLongBE(record, 7 + tid.size, framer.nextCounter)
        TcpFrameCodec.writeIntBE(record, 15 + tid.size, size + 16)
        val sealed = if (frame is TcpFrame.Chunk) {
            // Chunk bytes are encrypted straight from the caller's array into the record buffer.
            TcpFrameCodec.writeIntBE(chunkMeta, 0, frame.itemIndex)
            TcpFrameCodec.writeIntBE(chunkMeta, 4, frame.chunkIndex)
            TcpFrameCodec.writeIntBE(chunkMeta, 8, frame.data.size)
            framer.seal(record, headerLen, chunkMeta, 12, frame.data, 0, frame.data.size, record, headerLen)
        } else {
            if (plain.size < size) plain = ByteArray(size)
            val len = TcpFrameCodec.encodePayloadInto(frame, plain, 0)
            framer.seal(record, headerLen, plain, 0, len, record, headerLen)
        }
        output.write(record, 0, headerLen + sealed)
        if (flush) output.flush()
    }

    @Synchronized
    @Throws(IOException::class)
    public fun flush() {
        output.flush()
    }

    /** Records written so far. */
    public val nextSeq: Long @Synchronized get() = framer.nextCounter

    /** Not synchronized: a reader or writer blocked on the socket must not hold up the close. */
    internal fun wipe() {
        framer.wipe()
        plain.fill(0)
    }
}

/** The only reader of one data-socket direction. */
public class Vtc3Reader(
    private val input: InputStream,
    key: ByteArray,
    streamId: Int,
    transferId: String,
) {
    private val framer = AeadStreamFramer(key, streamId, AeadStreamFramer.Role.OPEN)
    private val tid = Vtc3.tidBytes(transferId)
    private val transferId: String = transferId
    private val header = ByteArray(4 + 1 + 2 + tid.size + 8 + 4)
    private var sealed = ByteArray(0)
    private var plain = ByteArray(0)
    private var failed = false

    /**
     * The next frame, or null on a clean EOF at a record boundary. A socket timeout before the
     * first byte of a record passes through unchanged (an idle wakeup); after it, the stream
     * position is lost and the read fails with [AeadStreamException]. Every other violation
     * throws [AeadStreamException] (or [EOFException] for a truncated record), and the reader
     * stays failed.
     */
    @Synchronized
    @Throws(IOException::class)
    public fun readOrNull(): TcpFrame? {
        if (failed) throw AeadStreamException("VTC3 stream failed earlier")
        val first = input.read(header, 0, 1)
        if (first < 0) return null
        try {
            return readRest()
        } catch (e: java.net.SocketTimeoutException) {
            failed = true
            throw AeadStreamException("VTC3 stream timed out inside a record", e)
        } catch (e: IOException) {
            failed = true
            throw e
        }
    }

    private fun readRest(): TcpFrame {
        Vtc3.readFully(input, header, 1, 6)
        if (!Vtc3.magicMatches(header)) throw AeadStreamException("VTC3 record has a bad magic")
        val type = header[4]
        if (type < TcpFrameCodec.TYPE_AUTH || type > TcpFrameCodec.TYPE_CANCEL) {
            throw AeadStreamException("VTC3 record has an unexpected type")
        }
        val tidLen = TcpFrameCodec.readShortBE(header, 5)
        if (tidLen < 1 || tidLen > TcpFrameCodec.MAX_TRANSFER_ID_LENGTH || tidLen != tid.size) {
            throw AeadStreamException("VTC3 record for another transfer")
        }
        Vtc3.readFully(input, header, 7, tidLen + 12)
        for (i in tid.indices) {
            if (header[7 + i] != tid[i]) throw AeadStreamException("VTC3 record for another transfer")
        }
        val seq = TcpFrameCodec.readLongBE(header, 7 + tidLen)
        if (seq < 0 || seq != framer.nextCounter) throw AeadStreamException("VTC3 record out of order")
        val ctLen = TcpFrameCodec.readIntBE(header, 15 + tidLen)
        if (ctLen < 16 || ctLen > TcpFrameCodec.MAX_PAYLOAD_SIZE + 16) {
            throw AeadStreamException("VTC3 record has a bad length")
        }
        if (sealed.size < ctLen) sealed = ByteArray(ctLen)
        if (plain.size < ctLen) plain = ByteArray(ctLen)
        Vtc3.readFully(input, sealed, 0, ctLen)
        val len = framer.open(header, header.size, sealed, 0, ctLen, plain, 0)
        return try {
            TcpFrameCodec.decodePayload(type, seq, transferId, plain, len)
        } catch (e: AeadStreamException) {
            throw e
        } catch (e: IOException) {
            throw AeadStreamException("VTC3 record payload is malformed", e)
        } catch (e: RuntimeException) {
            throw AeadStreamException("VTC3 record payload is malformed", e)
        }
    }

    /** Not synchronized: a reader or writer blocked on the socket must not hold up the close. */
    internal fun wipe() {
        framer.wipe()
        plain.fill(0)
    }
}
