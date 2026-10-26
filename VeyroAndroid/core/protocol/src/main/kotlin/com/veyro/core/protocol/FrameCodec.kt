package com.veyro.core.protocol

/**
 * Length-prefixed framing for the secure channel (04-PROTOCOL.md section 4.2 and 5.1).
 *
 * Two layers, and the distinction matters:
 *
 * **Noise framing** (the wire): `u32BE length || noiseMessage`. A Noise transport message carries
 * at most 65535 bytes, so anything larger must be split *before* encryption, never by truncating a
 * Noise message.
 *
 * **Application framing** (inside the plaintext): `u8 type || u32BE payloadLength || payload`. A
 * frame may span several Noise messages; the reader reassembles by the declared length.
 *
 * ## Why the split exists
 *
 * A 4 MiB chunk cannot be one Noise message. Either the transport fragments it or every caller
 * has to know about the 65535-byte limit, and the second option guarantees someone eventually
 * gets it wrong on one platform only. Fragmentation lives here, once, and is mirrored in C#.
 */
public object FrameCodec {

    /** Noise transport message limit, minus the 16-byte AEAD tag. */
    public const val MAX_NOISE_PLAINTEXT: Int = Vlp.MAX_NOISE_PLAINTEXT

    /** `u8 type || u32BE length`. */
    public const val APP_HEADER_BYTES: Int = 5

    /** Length prefix on the wire for each Noise message. */
    public const val WIRE_PREFIX_BYTES: Int = 4

    public sealed interface Decoded {
        public data class Frame(val type: Byte, val payload: ByteArray) : Decoded {
            override fun equals(other: Any?): Boolean =
                other is Frame && type == other.type && payload.contentEquals(other.payload)

            override fun hashCode(): Int = 31 * type.toInt() + payload.contentHashCode()
        }

        /** More Noise messages are needed before a complete frame is available. */
        public data object Incomplete : Decoded

        public data class Invalid(val reason: Reason, val detail: String) : Decoded
    }

    public enum class Reason {
        UNKNOWN_TYPE,
        LENGTH_TOO_LARGE,
        HEADER_TRUNCATED,
    }

    /** Encodes one application frame, fragmented into Noise-sized plaintext pieces. */
    public fun encodeFrame(type: Byte, payload: ByteArray): List<ByteArray> {
        val full = ByteArray(APP_HEADER_BYTES + payload.size)
        full[0] = type
        full[1] = ((payload.size ushr 24) and 0xFF).toByte()
        full[2] = ((payload.size ushr 16) and 0xFF).toByte()
        full[3] = ((payload.size ushr 8) and 0xFF).toByte()
        full[4] = (payload.size and 0xFF).toByte()
        payload.copyInto(full, APP_HEADER_BYTES)

        if (full.size <= MAX_NOISE_PLAINTEXT) return listOf(full)

        val pieces = ArrayList<ByteArray>((full.size + MAX_NOISE_PLAINTEXT - 1) / MAX_NOISE_PLAINTEXT)
        var offset = 0
        while (offset < full.size) {
            val end = minOf(offset + MAX_NOISE_PLAINTEXT, full.size)
            pieces += full.copyOfRange(offset, end)
            offset = end
        }
        return pieces
    }

    /** The `u32BE length || message` prefix used for every Noise message on the wire. */
    public fun wirePrefix(messageLength: Int): ByteArray {
        require(messageLength in 1..Vlp.MAX_NOISE_MESSAGE) {
            "Noise message length out of range: $messageLength"
        }
        return byteArrayOf(
            ((messageLength ushr 24) and 0xFF).toByte(),
            ((messageLength ushr 16) and 0xFF).toByte(),
            ((messageLength ushr 8) and 0xFF).toByte(),
            (messageLength and 0xFF).toByte(),
        )
    }

    public fun readWirePrefix(bytes: ByteArray, offset: Int = 0): Int {
        require(bytes.size - offset >= WIRE_PREFIX_BYTES) { "not enough bytes for a length prefix" }
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    /**
     * Reassembles application frames from decrypted Noise plaintext.
     *
     * Stateful by necessity: a frame can span several Noise messages, so the partial header and
     * payload have to be carried between calls. Not thread-safe - one instance per session
     * direction.
     */
    public class Reassembler(
        private val maxPayload: Int = Vlp.MAX_CONTROL_FRAME_BYTES,
    ) {
        private var buffer = ByteArray(0)

        /** Feeds decrypted plaintext and returns every complete frame it yields. */
        public fun offer(plaintext: ByteArray): List<Decoded> {
            buffer += plaintext
            val out = mutableListOf<Decoded>()

            while (true) {
                if (buffer.size < APP_HEADER_BYTES) break

                val type = buffer[0]
                if (!isKnownType(type)) {
                    out += Decoded.Invalid(Reason.UNKNOWN_TYPE, "type ${type.toInt()}")
                    // Unrecoverable: without a valid header we cannot know where this frame ends,
                    // so resynchronising is impossible and the session must be torn down.
                    buffer = ByteArray(0)
                    break
                }

                val length = ((buffer[1].toInt() and 0xFF) shl 24) or
                    ((buffer[2].toInt() and 0xFF) shl 16) or
                    ((buffer[3].toInt() and 0xFF) shl 8) or
                    (buffer[4].toInt() and 0xFF)

                // Check before allocating. A declared length of 2 GB from a hostile peer would
                // otherwise be an instant OOM - a one-packet denial of service.
                if (length < 0 || length > maxPayload) {
                    out += Decoded.Invalid(Reason.LENGTH_TOO_LARGE, "declared $length")
                    buffer = ByteArray(0)
                    break
                }

                val total = APP_HEADER_BYTES + length
                if (buffer.size < total) {
                    out += Decoded.Incomplete
                    break
                }

                out += Decoded.Frame(type, buffer.copyOfRange(APP_HEADER_BYTES, total))
                buffer = buffer.copyOfRange(total, buffer.size)
            }
            return out
        }

        /** Bytes currently buffered. Surfaced so a stalled session is diagnosable. */
        public fun pending(): Int = buffer.size

        public fun reset() {
            buffer = ByteArray(0)
        }

        private fun isKnownType(type: Byte): Boolean =
            type == Vlp.FRAME_CONTROL || type == Vlp.FRAME_BULK || type == Vlp.FRAME_KEEPALIVE
    }
}
