package com.veyro.core.protocol.tcp

import com.veyro.core.model.TransferRoute

/**
 * Raw TCP wire frames for the high-speed binary payload transfer stream.
 *
 * Each frame carries a monotonic sequence number [seq] and an associated [transferId].
 */
public sealed interface TcpFrame {
    public val seq: Long
    public val transferId: String

    /**
     * Authenticates the incoming TCP socket against the active session and transfer.
     */
    public data class Auth(
        override val seq: Long,
        override val transferId: String,
        val authToken: String,
        val senderDeviceId: String,
        val route: TransferRoute,
    ) : TcpFrame

    /**
     * Listener's response to an [Auth] frame.
     */
    public data class AuthAck(
        override val seq: Long,
        override val transferId: String,
        val ok: Boolean,
        val reason: String? = null,
    ) : TcpFrame

    /**
     * Declares the start of an item's chunk stream.
     */
    public data class ItemBegin(
        override val seq: Long,
        override val transferId: String,
        val itemIndex: Int,
        val sizeBytes: Long,
        val chunkCount: Int,
        val chunkSize: Int,
    ) : TcpFrame

    /**
     * Raw binary chunk payload without Base64 or JSON wrapping.
     */
    public data class Chunk(
        override val seq: Long,
        override val transferId: String,
        val itemIndex: Int,
        val chunkIndex: Int,
        val data: ByteArray,
    ) : TcpFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Chunk) return false
            return seq == other.seq &&
                transferId == other.transferId &&
                itemIndex == other.itemIndex &&
                chunkIndex == other.chunkIndex &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = seq.hashCode()
            result = 31 * result + transferId.hashCode()
            result = 31 * result + itemIndex
            result = 31 * result + chunkIndex
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /**
     * Declares completion of an item's stream with its expected SHA-256 hash.
     */
    public data class ItemEnd(
        override val seq: Long,
        override val transferId: String,
        val itemIndex: Int,
        val sha256Hex: String,
    ) : TcpFrame

    /**
     * Reports verification result for an item.
     */
    public data class ItemResult(
        override val seq: Long,
        override val transferId: String,
        val itemIndex: Int,
        val ok: Boolean,
        val errorMessage: String? = null,
    ) : TcpFrame

    /**
     * Signals that all items have completed transmission.
     */
    public data class TransferEnd(
        override val seq: Long,
        override val transferId: String,
        val completed: Boolean,
    ) : TcpFrame

    /**
     * Signals cancellation of the transfer.
     */
    public data class Cancel(
        override val seq: Long,
        override val transferId: String,
        val reason: String,
    ) : TcpFrame
}
