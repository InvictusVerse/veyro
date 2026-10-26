package com.veyro.core.protocol.tcp

import com.veyro.core.model.TransferRoute
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Binary encoder and decoder for the raw TCP transport stream.
 *
 * Wire header layout:
 * - 4 bytes: Magic "VTC1" (0x56, 0x54, 0x43, 0x31)
 * - 1 byte:  Frame Type (u8)
 * - 2 bytes: Transfer ID length in bytes (u16 BE)
 * - N bytes: Transfer ID (UTF-8)
 * - 8 bytes: Monotonic Sequence Number (u64 BE)
 * - 4 bytes: Payload length in bytes (u32 BE)
 * - M bytes: Frame Payload
 */
public object TcpFrameCodec {

    public val MAGIC: ByteArray = byteArrayOf(0x56, 0x54, 0x43, 0x31) // "VTC1"
    public const val MAX_PAYLOAD_SIZE: Int = 16 * 1024 * 1024 // 16 MiB max frame payload
    public const val MAX_TRANSFER_ID_LENGTH: Int = 256

    public const val TYPE_AUTH: Byte = 0x01
    public const val TYPE_AUTH_ACK: Byte = 0x02
    public const val TYPE_ITEM_BEGIN: Byte = 0x03
    public const val TYPE_CHUNK: Byte = 0x04
    public const val TYPE_ITEM_END: Byte = 0x05
    public const val TYPE_ITEM_RESULT: Byte = 0x06
    public const val TYPE_TRANSFER_END: Byte = 0x07
    public const val TYPE_CANCEL: Byte = 0x08

    public fun writeFrame(output: OutputStream, frame: TcpFrame, autoFlush: Boolean = (frame !is TcpFrame.Chunk)) {
        val transferIdBytes = frame.transferId.toByteArray(StandardCharsets.UTF_8)
        require(transferIdBytes.size <= MAX_TRANSFER_ID_LENGTH) {
            "Transfer ID exceeds max length of $MAX_TRANSFER_ID_LENGTH bytes"
        }
        val type = frameType(frame)

        if (frame is TcpFrame.Chunk) {
            // Specialized zero-copy path for Chunk frames:
            // Wire header layout:
            // 4B magic + 1B type + 2B transferIdLen + N bytes transferId + 8B seq + 4B payloadLen
            // Payload layout: 4B itemIndex + 4B chunkIndex + 4B dataLen + data bytes
            val chunkPayloadLen = 12 + frame.data.size
            val headerLen = 4 + 1 + 2 + transferIdBytes.size + 8 + 4 + 12
            val header = ByteArray(headerLen)
            System.arraycopy(MAGIC, 0, header, 0, 4)
            header[4] = type
            header[5] = ((transferIdBytes.size ushr 8) and 0xFF).toByte()
            header[6] = (transferIdBytes.size and 0xFF).toByte()
            System.arraycopy(transferIdBytes, 0, header, 7, transferIdBytes.size)

            var offset = 7 + transferIdBytes.size
            writeLongBE(header, offset, frame.seq)
            offset += 8
            writeIntBE(header, offset, chunkPayloadLen)
            offset += 4

            // Chunk payload header
            writeIntBE(header, offset, frame.itemIndex)
            offset += 4
            writeIntBE(header, offset, frame.chunkIndex)
            offset += 4
            writeIntBE(header, offset, frame.data.size)

            output.write(header)
            if (frame.data.isNotEmpty()) {
                output.write(frame.data)
            }
            if (autoFlush) {
                output.flush()
            }
            return
        }

        val payload = encodePayload(frame)
        // 4B magic + 1B type + 2B transferIdLen + N + 8B seq + 4B payloadLen
        val header = ByteArray(4 + 1 + 2 + transferIdBytes.size + 8 + 4)
        System.arraycopy(MAGIC, 0, header, 0, 4)
        header[4] = type
        header[5] = ((transferIdBytes.size ushr 8) and 0xFF).toByte()
        header[6] = (transferIdBytes.size and 0xFF).toByte()
        System.arraycopy(transferIdBytes, 0, header, 7, transferIdBytes.size)

        var offset = 7 + transferIdBytes.size
        writeLongBE(header, offset, frame.seq)
        offset += 8
        writeIntBE(header, offset, payload.size)

        output.write(header)
        if (payload.isNotEmpty()) {
            output.write(payload)
        }
        if (autoFlush) {
            output.flush()
        }
    }

    public fun encode(frame: TcpFrame): ByteArray {
        val out = ByteArrayOutputStream()
        writeFrame(out, frame)
        return out.toByteArray()
    }

    /**
     * Reads the next [TcpFrame] from [input], or returns null if the stream reaches clean EOF
     * at a frame boundary (0 bytes read).
     *
     * Throws [EOFException] if the stream closes in the middle of a frame, and [IOException]
     * if the frame header or payload is corrupted.
     */
    @Throws(IOException::class)
    public fun readFrameOrNull(input: InputStream): TcpFrame? {
        val prefix = ByteArray(7)
        val firstByte = input.read(prefix, 0, 1)
        if (firstByte < 0) {
            // Clean EOF at frame boundary
            return null
        }

        // Complete the remaining 6 prefix bytes
        input.readFully(prefix, 1, 6)
        if (prefix[0] != MAGIC[0] || prefix[1] != MAGIC[1] ||
            prefix[2] != MAGIC[2] || prefix[3] != MAGIC[3]
        ) {
            throw IOException("Invalid TCP frame magic header: expected 'VTC1'")
        }
        val type = prefix[4]
        val transferIdLen = ((prefix[5].toInt() and 0xFF) shl 8) or (prefix[6].toInt() and 0xFF)
        if (transferIdLen <= 0 || transferIdLen > MAX_TRANSFER_ID_LENGTH) {
            throw IOException("Invalid transfer ID length: $transferIdLen")
        }

        val transferIdBytes = ByteArray(transferIdLen)
        input.readFully(transferIdBytes)
        val transferId = String(transferIdBytes, StandardCharsets.UTF_8)

        // Read seq (8) + payloadLen (4) = 12 bytes
        val suffix = ByteArray(12)
        input.readFully(suffix)
        val seq = readLongBE(suffix, 0)
        val payloadLen = readIntBE(suffix, 8)

        if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_SIZE) {
            throw IOException("Invalid payload length: $payloadLen (bounds: 0..$MAX_PAYLOAD_SIZE)")
        }

        if (type == TYPE_CHUNK) {
            // Optimized Chunk read path: avoid double allocation of payload array
            if (payloadLen < 12) throw IOException("Truncated Chunk payload: $payloadLen bytes")
            val chunkMeta = ByteArray(12)
            input.readFully(chunkMeta)
            val itemIndex = readIntBE(chunkMeta, 0)
            val chunkIndex = readIntBE(chunkMeta, 4)
            val dataLen = readIntBE(chunkMeta, 8)
            if (dataLen < 0 || payloadLen != 12 + dataLen) {
                throw IOException("Corrupted chunk data length: $dataLen vs payload size $payloadLen")
            }
            val data = ByteArray(dataLen)
            if (dataLen > 0) {
                input.readFully(data)
            }
            return TcpFrame.Chunk(seq, transferId, itemIndex, chunkIndex, data)
        }

        val payload = ByteArray(payloadLen)
        if (payloadLen > 0) {
            input.readFully(payload)
        }

        return decodePayload(type, seq, transferId, payload)
    }

    @Throws(IOException::class)
    public fun readFrame(input: InputStream): TcpFrame {
        return readFrameOrNull(input) ?: throw EOFException("Stream closed prematurely: read 0 of 7 expected bytes")
    }

    @Throws(IOException::class)
    public fun decode(bytes: ByteArray): TcpFrame {
        return readFrame(bytes.inputStream())
    }

    private fun frameType(frame: TcpFrame): Byte = when (frame) {
        is TcpFrame.Auth -> TYPE_AUTH
        is TcpFrame.AuthAck -> TYPE_AUTH_ACK
        is TcpFrame.ItemBegin -> TYPE_ITEM_BEGIN
        is TcpFrame.Chunk -> TYPE_CHUNK
        is TcpFrame.ItemEnd -> TYPE_ITEM_END
        is TcpFrame.ItemResult -> TYPE_ITEM_RESULT
        is TcpFrame.TransferEnd -> TYPE_TRANSFER_END
        is TcpFrame.Cancel -> TYPE_CANCEL
    }

    private fun encodePayload(frame: TcpFrame): ByteArray = when (frame) {
        is TcpFrame.Auth -> {
            val tokenBytes = frame.authToken.toByteArray(StandardCharsets.UTF_8)
            val devIdBytes = frame.senderDeviceId.toByteArray(StandardCharsets.UTF_8)
            val routeByte = if (frame.route == TransferRoute.WIFI_DIRECT) 0x01.toByte() else 0x02.toByte()
            val out = ByteArray(2 + tokenBytes.size + 2 + devIdBytes.size + 1)
            var off = 0
            writeShortBE(out, off, tokenBytes.size)
            off += 2
            System.arraycopy(tokenBytes, 0, out, off, tokenBytes.size)
            off += tokenBytes.size
            writeShortBE(out, off, devIdBytes.size)
            off += 2
            System.arraycopy(devIdBytes, 0, out, off, devIdBytes.size)
            off += devIdBytes.size
            out[off] = routeByte
            out
        }
        is TcpFrame.AuthAck -> {
            val reasonBytes = frame.reason?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
            val out = ByteArray(1 + 2 + reasonBytes.size)
            out[0] = if (frame.ok) 0x01 else 0x00
            writeShortBE(out, 1, reasonBytes.size)
            if (reasonBytes.isNotEmpty()) {
                System.arraycopy(reasonBytes, 0, out, 3, reasonBytes.size)
            }
            out
        }
        is TcpFrame.ItemBegin -> {
            val out = ByteArray(4 + 8 + 4 + 4)
            writeIntBE(out, 0, frame.itemIndex)
            writeLongBE(out, 4, frame.sizeBytes)
            writeIntBE(out, 12, frame.chunkCount)
            writeIntBE(out, 16, frame.chunkSize)
            out
        }
        is TcpFrame.Chunk -> {
            val out = ByteArray(4 + 4 + 4 + frame.data.size)
            writeIntBE(out, 0, frame.itemIndex)
            writeIntBE(out, 4, frame.chunkIndex)
            writeIntBE(out, 8, frame.data.size)
            if (frame.data.isNotEmpty()) {
                System.arraycopy(frame.data, 0, out, 12, frame.data.size)
            }
            out
        }
        is TcpFrame.ItemEnd -> {
            val hashBytes = frame.sha256Hex.toByteArray(StandardCharsets.UTF_8)
            val out = ByteArray(4 + 2 + hashBytes.size)
            writeIntBE(out, 0, frame.itemIndex)
            writeShortBE(out, 4, hashBytes.size)
            System.arraycopy(hashBytes, 0, out, 6, hashBytes.size)
            out
        }
        is TcpFrame.ItemResult -> {
            val errBytes = frame.errorMessage?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
            val out = ByteArray(4 + 1 + 2 + errBytes.size)
            writeIntBE(out, 0, frame.itemIndex)
            out[4] = if (frame.ok) 0x01 else 0x00
            writeShortBE(out, 5, errBytes.size)
            if (errBytes.isNotEmpty()) {
                System.arraycopy(errBytes, 0, out, 7, errBytes.size)
            }
            out
        }
        is TcpFrame.TransferEnd -> {
            byteArrayOf(if (frame.completed) 0x01 else 0x00)
        }
        is TcpFrame.Cancel -> {
            val reasonBytes = frame.reason.toByteArray(StandardCharsets.UTF_8)
            val out = ByteArray(2 + reasonBytes.size)
            writeShortBE(out, 0, reasonBytes.size)
            System.arraycopy(reasonBytes, 0, out, 2, reasonBytes.size)
            out
        }
    }

    private fun decodePayload(type: Byte, seq: Long, transferId: String, payload: ByteArray): TcpFrame = when (type) {
        TYPE_AUTH -> {
            if (payload.size < 5) throw IOException("Truncated Auth payload: ${payload.size} bytes")
            val tokenLen = readShortBE(payload, 0)
            if (payload.size < 2 + tokenLen + 3) throw IOException("Truncated Auth token payload")
            val token = String(payload, 2, tokenLen, StandardCharsets.UTF_8)
            var off = 2 + tokenLen
            val devIdLen = readShortBE(payload, off)
            off += 2
            if (payload.size < off + devIdLen + 1) throw IOException("Truncated Auth devId payload")
            val devId = String(payload, off, devIdLen, StandardCharsets.UTF_8)
            off += devIdLen
            val routeByte = payload[off]
            val route = if (routeByte == 0x01.toByte()) TransferRoute.WIFI_DIRECT else TransferRoute.LAN_TCP
            TcpFrame.Auth(seq, transferId, token, devId, route)
        }
        TYPE_AUTH_ACK -> {
            if (payload.size < 3) throw IOException("Truncated AuthAck payload: ${payload.size} bytes")
            val ok = payload[0] == 0x01.toByte()
            val reasonLen = readShortBE(payload, 1)
            val reason = if (reasonLen > 0 && payload.size >= 3 + reasonLen) {
                String(payload, 3, reasonLen, StandardCharsets.UTF_8)
            } else null
            TcpFrame.AuthAck(seq, transferId, ok, reason)
        }
        TYPE_ITEM_BEGIN -> {
            if (payload.size < 20) throw IOException("Truncated ItemBegin payload: ${payload.size} bytes")
            val itemIndex = readIntBE(payload, 0)
            val sizeBytes = readLongBE(payload, 4)
            val chunkCount = readIntBE(payload, 12)
            val chunkSize = readIntBE(payload, 16)
            TcpFrame.ItemBegin(seq, transferId, itemIndex, sizeBytes, chunkCount, chunkSize)
        }
        TYPE_CHUNK -> {
            if (payload.size < 12) throw IOException("Truncated Chunk payload: ${payload.size} bytes")
            val itemIndex = readIntBE(payload, 0)
            val chunkIndex = readIntBE(payload, 4)
            val dataLen = readIntBE(payload, 8)
            if (dataLen < 0 || payload.size < 12 + dataLen) {
                throw IOException("Corrupted chunk data length: $dataLen vs payload size ${payload.size}")
            }
            val data = ByteArray(dataLen)
            if (dataLen > 0) {
                System.arraycopy(payload, 12, data, 0, dataLen)
            }
            TcpFrame.Chunk(seq, transferId, itemIndex, chunkIndex, data)
        }
        TYPE_ITEM_END -> {
            if (payload.size < 6) throw IOException("Truncated ItemEnd payload: ${payload.size} bytes")
            val itemIndex = readIntBE(payload, 0)
            val hashLen = readShortBE(payload, 4)
            if (payload.size < 6 + hashLen) throw IOException("Truncated ItemEnd hash payload")
            val hash = String(payload, 6, hashLen, StandardCharsets.UTF_8)
            TcpFrame.ItemEnd(seq, transferId, itemIndex, hash)
        }
        TYPE_ITEM_RESULT -> {
            if (payload.size < 7) throw IOException("Truncated ItemResult payload: ${payload.size} bytes")
            val itemIndex = readIntBE(payload, 0)
            val ok = payload[4] == 0x01.toByte()
            val errLen = readShortBE(payload, 5)
            val err = if (errLen > 0 && payload.size >= 7 + errLen) {
                String(payload, 7, errLen, StandardCharsets.UTF_8)
            } else null
            TcpFrame.ItemResult(seq, transferId, itemIndex, ok, err)
        }
        TYPE_TRANSFER_END -> {
            if (payload.isEmpty()) throw IOException("Empty TransferEnd payload")
            val completed = payload[0] == 0x01.toByte()
            TcpFrame.TransferEnd(seq, transferId, completed)
        }
        TYPE_CANCEL -> {
            if (payload.size < 2) throw IOException("Truncated Cancel payload")
            val reasonLen = readShortBE(payload, 0)
            val reason = if (reasonLen > 0 && payload.size >= 2 + reasonLen) {
                String(payload, 2, reasonLen, StandardCharsets.UTF_8)
            } else ""
            TcpFrame.Cancel(seq, transferId, reason)
        }
        else -> throw IOException("Unknown TCP frame type: $type")
    }

    private fun writeShortBE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeIntBE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeLongBE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 56) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 48) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 40) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 32) and 0xFF).toByte()
        buf[offset + 4] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 5] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 6] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 7] = (value and 0xFF).toByte()
    }

    private fun readShortBE(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
    }

    private fun readIntBE(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
    }

    private fun readLongBE(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].toLong() and 0xFF) shl 56) or
            ((buf[offset + 1].toLong() and 0xFF) shl 48) or
            ((buf[offset + 2].toLong() and 0xFF) shl 40) or
            ((buf[offset + 3].toLong() and 0xFF) shl 32) or
            ((buf[offset + 4].toLong() and 0xFF) shl 24) or
            ((buf[offset + 5].toLong() and 0xFF) shl 16) or
            ((buf[offset + 6].toLong() and 0xFF) shl 8) or
            (buf[offset + 7].toLong() and 0xFF)
    }

    @Throws(IOException::class)
    public fun InputStream.readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        var totalRead = 0
        while (totalRead < length) {
            val read = this.read(buffer, offset + totalRead, length - totalRead)
            if (read < 0) {
                throw EOFException("Stream closed prematurely: read $totalRead of $length expected bytes")
            }
            totalRead += read
        }
    }
}
