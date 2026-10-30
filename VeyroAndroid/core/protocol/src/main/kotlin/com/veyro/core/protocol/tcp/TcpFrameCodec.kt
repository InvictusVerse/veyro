package com.veyro.core.protocol.tcp

import com.veyro.core.model.TransferRoute
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Payload encoder and decoder for the raw TCP data plane (VTC3, ADR-058, 04-PROTOCOL.md 7.3).
 *
 * The record framing, the AEAD sealing and the counters live in [Vtc3Writer] and [Vtc3Reader].
 * This object keeps only the per-type payload encodings, which are unchanged from VTC1 and are
 * the VTC3 plaintexts.
 *
 * Record header layout:
 * - 4 bytes: Magic "VTC3" (0x56, 0x54, 0x43, 0x33)
 * - 1 byte:  Frame Type (u8)
 * - 2 bytes: Transfer ID length in bytes (u16 BE)
 * - N bytes: Transfer ID (UTF-8)
 * - 8 bytes: Sequence number = the AEAD counter (u64 BE)
 * - 4 bytes: Ciphertext length in bytes, tag included (u32 BE)
 * - M bytes: ciphertext || 16-byte tag
 */
public object TcpFrameCodec {

    public val MAGIC: ByteArray = byteArrayOf(0x56, 0x54, 0x43, 0x33) // "VTC3"
    public const val MAX_PAYLOAD_SIZE: Int = 16 * 1024 * 1024 // 16 MiB max frame plaintext
    public const val MAX_TRANSFER_ID_LENGTH: Int = 256

    public const val TYPE_AUTH: Byte = 0x01
    public const val TYPE_AUTH_ACK: Byte = 0x02
    public const val TYPE_ITEM_BEGIN: Byte = 0x03
    public const val TYPE_CHUNK: Byte = 0x04
    public const val TYPE_ITEM_END: Byte = 0x05
    public const val TYPE_ITEM_RESULT: Byte = 0x06
    public const val TYPE_TRANSFER_END: Byte = 0x07
    public const val TYPE_CANCEL: Byte = 0x08

    /** The cleartext stream-open record that carries one side's 16-byte salt. Never sealed. */
    public const val TYPE_STREAM_OPEN: Byte = 0x09

    /** Plaintext length of [frame]'s payload. */
    internal fun payloadSize(frame: TcpFrame): Int = when (frame) {
        is TcpFrame.Chunk -> 12 + frame.data.size
        else -> encodePayload(frame).size
    }

    /** Writes [frame]'s payload into [dst] at [offset]; returns the length. Chunk data is copied once. */
    internal fun encodePayloadInto(frame: TcpFrame, dst: ByteArray, offset: Int): Int {
        if (frame is TcpFrame.Chunk) {
            writeIntBE(dst, offset, frame.itemIndex)
            writeIntBE(dst, offset + 4, frame.chunkIndex)
            writeIntBE(dst, offset + 8, frame.data.size)
            System.arraycopy(frame.data, 0, dst, offset + 12, frame.data.size)
            return 12 + frame.data.size
        }
        val payload = encodePayload(frame)
        System.arraycopy(payload, 0, dst, offset, payload.size)
        return payload.size
    }

    internal fun frameTypeOf(frame: TcpFrame): Byte = frameType(frame)
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

    internal fun encodePayload(frame: TcpFrame): ByteArray = when (frame) {
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

    internal fun decodePayload(type: Byte, seq: Long, transferId: String, payload: ByteArray, size: Int = payload.size): TcpFrame = when (type) {
        TYPE_AUTH -> {
            if (size < 5) throw IOException("Truncated Auth payload: ${size} bytes")
            val tokenLen = readShortBE(payload, 0)
            if (size < 2 + tokenLen + 3) throw IOException("Truncated Auth token payload")
            val token = String(payload, 2, tokenLen, StandardCharsets.UTF_8)
            var off = 2 + tokenLen
            val devIdLen = readShortBE(payload, off)
            off += 2
            if (size < off + devIdLen + 1) throw IOException("Truncated Auth devId payload")
            val devId = String(payload, off, devIdLen, StandardCharsets.UTF_8)
            off += devIdLen
            val routeByte = payload[off]
            val route = if (routeByte == 0x01.toByte()) TransferRoute.WIFI_DIRECT else TransferRoute.LAN_TCP
            TcpFrame.Auth(seq, transferId, token, devId, route)
        }
        TYPE_AUTH_ACK -> {
            if (size < 3) throw IOException("Truncated AuthAck payload: ${size} bytes")
            val ok = payload[0] == 0x01.toByte()
            val reasonLen = readShortBE(payload, 1)
            val reason = if (reasonLen > 0 && size >= 3 + reasonLen) {
                String(payload, 3, reasonLen, StandardCharsets.UTF_8)
            } else null
            TcpFrame.AuthAck(seq, transferId, ok, reason)
        }
        TYPE_ITEM_BEGIN -> {
            if (size < 20) throw IOException("Truncated ItemBegin payload: ${size} bytes")
            val itemIndex = readIntBE(payload, 0)
            val sizeBytes = readLongBE(payload, 4)
            val chunkCount = readIntBE(payload, 12)
            val chunkSize = readIntBE(payload, 16)
            TcpFrame.ItemBegin(seq, transferId, itemIndex, sizeBytes, chunkCount, chunkSize)
        }
        TYPE_CHUNK -> {
            if (size < 12) throw IOException("Truncated Chunk payload: ${size} bytes")
            val itemIndex = readIntBE(payload, 0)
            val chunkIndex = readIntBE(payload, 4)
            val dataLen = readIntBE(payload, 8)
            if (dataLen < 0 || size < 12 + dataLen) {
                throw IOException("Corrupted chunk data length: $dataLen vs payload size ${size}")
            }
            val data = ByteArray(dataLen)
            if (dataLen > 0) {
                System.arraycopy(payload, 12, data, 0, dataLen)
            }
            TcpFrame.Chunk(seq, transferId, itemIndex, chunkIndex, data)
        }
        TYPE_ITEM_END -> {
            if (size < 6) throw IOException("Truncated ItemEnd payload: ${size} bytes")
            val itemIndex = readIntBE(payload, 0)
            val hashLen = readShortBE(payload, 4)
            if (size < 6 + hashLen) throw IOException("Truncated ItemEnd hash payload")
            val hash = String(payload, 6, hashLen, StandardCharsets.UTF_8)
            TcpFrame.ItemEnd(seq, transferId, itemIndex, hash)
        }
        TYPE_ITEM_RESULT -> {
            if (size < 7) throw IOException("Truncated ItemResult payload: ${size} bytes")
            val itemIndex = readIntBE(payload, 0)
            val ok = payload[4] == 0x01.toByte()
            val errLen = readShortBE(payload, 5)
            val err = if (errLen > 0 && size >= 7 + errLen) {
                String(payload, 7, errLen, StandardCharsets.UTF_8)
            } else null
            TcpFrame.ItemResult(seq, transferId, itemIndex, ok, err)
        }
        TYPE_TRANSFER_END -> {
            if (size == 0) throw IOException("Empty TransferEnd payload")
            val completed = payload[0] == 0x01.toByte()
            TcpFrame.TransferEnd(seq, transferId, completed)
        }
        TYPE_CANCEL -> {
            if (size < 2) throw IOException("Truncated Cancel payload")
            val reasonLen = readShortBE(payload, 0)
            val reason = if (reasonLen > 0 && size >= 2 + reasonLen) {
                String(payload, 2, reasonLen, StandardCharsets.UTF_8)
            } else ""
            TcpFrame.Cancel(seq, transferId, reason)
        }
        else -> throw IOException("Unknown TCP frame type: $type")
    }

    internal fun writeShortBE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    internal fun writeIntBE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    internal fun writeLongBE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 56) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 48) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 40) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 32) and 0xFF).toByte()
        buf[offset + 4] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 5] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 6] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 7] = (value and 0xFF).toByte()
    }

    internal fun readShortBE(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
    }

    internal fun readIntBE(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
    }

    internal fun readLongBE(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].toLong() and 0xFF) shl 56) or
            ((buf[offset + 1].toLong() and 0xFF) shl 48) or
            ((buf[offset + 2].toLong() and 0xFF) shl 40) or
            ((buf[offset + 3].toLong() and 0xFF) shl 32) or
            ((buf[offset + 4].toLong() and 0xFF) shl 24) or
            ((buf[offset + 5].toLong() and 0xFF) shl 16) or
            ((buf[offset + 6].toLong() and 0xFF) shl 8) or
            (buf[offset + 7].toLong() and 0xFF)
    }
}
