package com.veyro.core.protocol

/**
 * The UDP beacon frame (04-PROTOCOL.md section 3.2).
 *
 * ```
 * offset size  field
 * 0      4     magic   = "VEYR"
 * 4      1     version = 1
 * 5      1     type    = 0x01 QUERY | 0x02 ANNOUNCE
 * 6      2     payloadLength (u16 BE)
 * 8      n     payload = PresenceCodec bytes
 * ```
 *
 * This is the fallback that makes hotspot scenarios work. Several access points and hotspot drivers
 * drop multicast while forwarding directed broadcast, and some do the reverse, which is why both
 * are sent and why mDNS alone is not enough.
 *
 * A decoder here is parsing packets that anything on the network can send, so it is total: no
 * exceptions, no partial results, and the declared length is never trusted against the actual
 * buffer size.
 */
public object BeaconCodec {

    public const val HEADER_BYTES: Int = 8
    public const val MAX_FRAME_BYTES: Int = HEADER_BYTES + PresenceCodec.MAX_ENCODED_BYTES

    public enum class Type(public val wire: Byte) {
        /** "Is anyone there?" Answered with a **unicast** ANNOUNCE, never a broadcast. */
        QUERY(0x01),

        /** "I am here." */
        ANNOUNCE(0x02),
        ;

        public companion object {
            public fun fromWire(value: Byte): Type? = entries.firstOrNull { it.wire == value }
        }
    }

    public sealed interface Result {
        public data class Ok(val type: Type, val record: PresenceRecord) : Result

        /**
         * A query carries no useful payload, so it decodes to its own case rather than being
         * forced to produce a record.
         */
        public data class Query(val record: PresenceRecord?) : Result

        public data class Invalid(val reason: Reason, val detail: String) : Result
    }

    public enum class Reason {
        TOO_SHORT,
        BAD_MAGIC,
        BAD_VERSION,
        BAD_TYPE,
        LENGTH_MISMATCH,
        TOO_LARGE,
        BAD_PAYLOAD,
    }

    public fun encode(type: Type, record: PresenceRecord): ByteArray {
        val payload = PresenceCodec.encodeToBytes(record)
        require(payload.size <= PresenceCodec.MAX_ENCODED_BYTES) {
            "payload of ${payload.size} bytes exceeds the beacon limit"
        }
        val out = ByteArray(HEADER_BYTES + payload.size)
        ProtocolConstants.UDP_MAGIC.copyInto(out, 0)
        out[4] = ProtocolConstants.VERSION.toByte()
        out[5] = type.wire
        out[6] = ((payload.size shr 8) and 0xFF).toByte()
        out[7] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    /**
     * Decodes a received datagram.
     *
     * @param bytes the datagram buffer
     * @param length how many bytes were actually received. Passed separately because
     * `DatagramPacket` hands back a fixed-size buffer with a separate length, and using
     * `bytes.size` instead would read uninitialised buffer tail as payload.
     */
    public fun decode(bytes: ByteArray, length: Int = bytes.size): Result {
        if (length < HEADER_BYTES) {
            return Result.Invalid(Reason.TOO_SHORT, "$length bytes")
        }
        if (length > MAX_FRAME_BYTES) {
            return Result.Invalid(Reason.TOO_LARGE, "$length bytes")
        }
        for (i in ProtocolConstants.UDP_MAGIC.indices) {
            if (bytes[i] != ProtocolConstants.UDP_MAGIC[i]) {
                // Almost every packet on a busy multicast group is something else entirely, so
                // this is the normal path, not an error worth logging.
                return Result.Invalid(Reason.BAD_MAGIC, "not a Veyro beacon")
            }
        }
        if (bytes[4].toInt() != ProtocolConstants.VERSION) {
            return Result.Invalid(Reason.BAD_VERSION, "version ${bytes[4].toInt()}")
        }
        val type = Type.fromWire(bytes[5])
            ?: return Result.Invalid(Reason.BAD_TYPE, "type ${bytes[5].toInt()}")

        val declared = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        // Never trust a declared length against the real buffer. This is the classic parser bug
        // and in a network-facing decoder it is a read past the end of received data.
        if (HEADER_BYTES + declared != length) {
            return Result.Invalid(
                Reason.LENGTH_MISMATCH,
                "declared $declared, frame carries ${length - HEADER_BYTES}",
            )
        }

        if (declared == 0) {
            // A bare query with no payload is legal: it says "announce yourselves" and nothing more.
            return if (type == Type.QUERY) {
                Result.Query(null)
            } else {
                Result.Invalid(Reason.BAD_PAYLOAD, "announce with no payload")
            }
        }

        val payload = bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + declared)
        return when (val decoded = PresenceCodec.decodeFromBytes(payload)) {
            is PresenceCodec.Result.Ok -> when (type) {
                Type.ANNOUNCE -> Result.Ok(type, decoded.record)
                Type.QUERY -> Result.Query(decoded.record)
            }
            is PresenceCodec.Result.Invalid ->
                Result.Invalid(Reason.BAD_PAYLOAD, "${decoded.reason}: ${decoded.detail}")
        }
    }

    /** A query with no payload: the smallest legal frame. */
    public fun encodeBareQuery(): ByteArray {
        val out = ByteArray(HEADER_BYTES)
        ProtocolConstants.UDP_MAGIC.copyInto(out, 0)
        out[4] = ProtocolConstants.VERSION.toByte()
        out[5] = Type.QUERY.wire
        // length stays 0
        return out
    }
}
