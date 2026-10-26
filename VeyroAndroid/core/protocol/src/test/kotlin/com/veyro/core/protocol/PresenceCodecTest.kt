package com.veyro.core.protocol

import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presence record and beacon codecs.
 *
 * Both decoders parse input that anything on the network can send, so most of these tests are
 * rejection tests. The encode path is the easy half; the decoder is the attack surface.
 */
class PresenceCodecTest {

    private val deviceId = DeviceId.fromPublicKey(ByteArray(32) { (it + 1).toByte() })

    private val publicKey = ByteArray(32) { (it + 5).toByte() }

    private fun record(
        name: String? = "Study PC",
        port: Int = 47653,
        caps: Capabilities = Capabilities.of(Capability.FILE_TRANSFER, Capability.RESUME),
        key: ByteArray? = publicKey,
    ) = PresenceRecord(
        version = Vlp.VERSION,
        deviceId = deviceId,
        platform = Platform.WINDOWS,
        capabilities = caps,
        publicKeyHash = ByteArray(8) { (it + 10).toByte() },
        presenceToken = ByteArray(8) { (it + 20).toByte() },
        name = name,
        port = port,
        publicKey = key,
    )

    private fun ok(result: PresenceCodec.Result): PresenceRecord {
        assertTrue("expected Ok, got $result", result is PresenceCodec.Result.Ok)
        return (result as PresenceCodec.Result.Ok).record
    }

    private fun invalid(result: PresenceCodec.Result): PresenceCodec.Result.Invalid {
        assertTrue("expected Invalid, got $result", result is PresenceCodec.Result.Invalid)
        return result as PresenceCodec.Result.Invalid
    }

    // ── Round trip ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `record round trips through bytes`() {
        val original = record()
        val decoded = ok(PresenceCodec.decodeFromBytes(PresenceCodec.encodeToBytes(original)))
        assertEquals(original, decoded)
    }

    @Test
    fun `record round trips through pairs`() {
        val original = record()
        val decoded = ok(PresenceCodec.decodeFromPairs(PresenceCodec.encodeToPairs(original)))
        assertEquals(original, decoded)
    }

    @Test
    fun `private mode omits the name entirely`() {
        // Not "name=" with an empty value: an empty value still tells an observer that a name
        // exists and is being withheld (REQ-D06).
        val pairs = PresenceCodec.encodeToPairs(record(name = null))
        assertTrue(
            "the name key must be absent, not empty",
            pairs.none { it.first == PresenceRecord.KEY_NAME },
        )
        assertNull(ok(PresenceCodec.decodeFromPairs(pairs)).name)
    }

    @Test
    fun `a unicode name round trips`() {
        val original = record(name = "Büro-PC 日本")
        assertEquals(original, ok(PresenceCodec.decodeFromBytes(PresenceCodec.encodeToBytes(original))))
    }

    @Test
    fun `an encoded record fits an mdns txt record`() {
        val size = PresenceCodec.encodeToBytes(record(name = "A".repeat(32))).size
        assertTrue("encoded record is $size bytes, too large for a TXT record", size <= 400)
    }

    @Test
    fun `unknown keys are ignored for forward compatibility`() {
        // Section 11: additive keys are allowed within a version. A future peer sending a new key
        // must not become undiscoverable to this build.
        val pairs = PresenceCodec.encodeToPairs(record()) + ("futureKey" to "futureValue")
        assertEquals(record(), ok(PresenceCodec.decodeFromPairs(pairs)))
    }

    // ── Rejection ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `empty input is rejected`() {
        assertEquals(PresenceCodec.Reason.EMPTY, invalid(PresenceCodec.decodeFromBytes(ByteArray(0))).reason)
        assertEquals(PresenceCodec.Reason.EMPTY, invalid(PresenceCodec.decodeFromPairs(emptyList())).reason)
    }

    @Test
    fun `oversized input is rejected before parsing`() {
        val huge = ByteArray(PresenceCodec.MAX_ENCODED_BYTES + 1) { 'a'.code.toByte() }
        assertEquals(PresenceCodec.Reason.TOO_LARGE, invalid(PresenceCodec.decodeFromBytes(huge)).reason)
    }

    @Test
    fun `a pair without an equals sign is rejected`() {
        assertEquals(
            PresenceCodec.Reason.MALFORMED_PAIR,
            invalid(PresenceCodec.decodeFromBytes("v=1\ngarbage".toByteArray())).reason,
        )
    }

    @Test
    fun `a duplicate key is rejected rather than resolved`() {
        // Two conflicting claims. Silently picking either would let an attacker get a different
        // reading on Android than on Windows.
        val pairs = PresenceCodec.encodeToPairs(record()) + (PresenceRecord.KEY_PORT to "1234")
        assertEquals(PresenceCodec.Reason.DUPLICATE_KEY, invalid(PresenceCodec.decodeFromPairs(pairs)).reason)
    }

    @Test
    fun `a wrong protocol version is refused not guessed`() {
        val pairs = PresenceCodec.encodeToPairs(record())
            .map { if (it.first == PresenceRecord.KEY_VERSION) it.first to "99" else it }
        assertEquals(PresenceCodec.Reason.BAD_VERSION, invalid(PresenceCodec.decodeFromPairs(pairs)).reason)
    }

    @Test
    fun `missing required keys are reported`() {
        for (key in listOf(
            PresenceRecord.KEY_VERSION,
            PresenceRecord.KEY_CAPABILITIES,
            PresenceRecord.KEY_PORT,
        )) {
            val pairs = PresenceCodec.encodeToPairs(record()).filterNot { it.first == key }
            val result = invalid(PresenceCodec.decodeFromPairs(pairs))
            assertTrue(
                "missing $key gave ${result.reason}",
                result.reason == PresenceCodec.Reason.MISSING_REQUIRED,
            )
        }
    }

    @Test
    fun `a malformed device id is rejected`() {
        for (bad in listOf("", "SHORT", "U".repeat(26), "x".repeat(40))) {
            val pairs = PresenceCodec.encodeToPairs(record())
                .map { if (it.first == PresenceRecord.KEY_DEVICE_ID) it.first to bad else it }
            assertEquals(
                "device id '$bad' should be rejected",
                PresenceCodec.Reason.BAD_DEVICE_ID,
                invalid(PresenceCodec.decodeFromPairs(pairs)).reason,
            )
        }
    }

    @Test
    fun `a wrong-length hash or token is rejected`() {
        val shortHex = "0011"
        var pairs = PresenceCodec.encodeToPairs(record())
            .map { if (it.first == PresenceRecord.KEY_PUBKEY_HASH) it.first to shortHex else it }
        assertEquals(PresenceCodec.Reason.BAD_HASH, invalid(PresenceCodec.decodeFromPairs(pairs)).reason)

        pairs = PresenceCodec.encodeToPairs(record())
            .map { if (it.first == PresenceRecord.KEY_TOKEN) it.first to shortHex else it }
        assertEquals(PresenceCodec.Reason.BAD_TOKEN, invalid(PresenceCodec.decodeFromPairs(pairs)).reason)
    }

    @Test
    fun `an out-of-range port is rejected`() {
        for (bad in listOf("0", "65536", "-1", "notanumber", "")) {
            val pairs = PresenceCodec.encodeToPairs(record())
                .map { if (it.first == PresenceRecord.KEY_PORT) it.first to bad else it }
            assertEquals(
                "port '$bad' should be rejected",
                PresenceCodec.Reason.BAD_PORT,
                invalid(PresenceCodec.decodeFromPairs(pairs)).reason,
            )
        }
    }

    @Test
    fun `an over-long name is rejected`() {
        val pairs = PresenceCodec.encodeToPairs(record())
            .map { if (it.first == PresenceRecord.KEY_NAME) it.first to "A".repeat(200) else it }
        assertEquals(PresenceCodec.Reason.NAME_TOO_LONG, invalid(PresenceCodec.decodeFromPairs(pairs)).reason)
    }

    @Test
    fun `a name with control characters is rejected`() {
        // A newline in a name that gets rendered in a device list is a UI-spoofing vector.
        for (bad in listOf("PC\nTrusted", "PC\u0000", "PC\u007F", "PC\tX")) {
            val pairs = PresenceCodec.encodeToPairs(record())
                .map { if (it.first == PresenceRecord.KEY_NAME) it.first to bad else it }
            assertEquals(
                "name with a control char should be rejected",
                PresenceCodec.Reason.NAME_INVALID,
                invalid(PresenceCodec.decodeFromPairs(pairs)).reason,
            )
        }
    }

    @Test
    fun `a name with a bidi override is rejected`() {
        // Bidi overrides reorder rendered text, so a hostile device could display as a trusted
        // one. Cheap to reject; REQ-U04 is about devices being distinguishable.
        val pairs = PresenceCodec.encodeToPairs(record())
            .map { if (it.first == PresenceRecord.KEY_NAME) it.first to "PC\u202Egnirts" else it }
        assertEquals(PresenceCodec.Reason.NAME_INVALID, invalid(PresenceCodec.decodeFromPairs(pairs)).reason)
    }

    // ── The public key ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the public key round trips`() {
        // This is what lets pairing work without a camera: Noise_XK needs the responder''s static
        // key before the handshake starts, and a TXT attribute carries it.
        val decoded = ok(PresenceCodec.decodeFromBytes(PresenceCodec.encodeToBytes(record())))
        assertTrue(publicKey.contentEquals(decoded.publicKey))
    }

    @Test
    fun `the public key is omitted in private mode`() {
        val pairs = PresenceCodec.encodeToPairs(record(key = null))
        assertTrue(
            "there is nothing to gain from publishing it once paired (REQ-D06)",
            pairs.none { it.first == PresenceRecord.KEY_PUBLIC_KEY },
        )
        assertNull(ok(PresenceCodec.decodeFromPairs(pairs)).publicKey)
    }

    @Test
    fun `a wrong-length public key is rejected`() {
        for (size in intArrayOf(0, 16, 31, 33, 64)) {
            val pairs = PresenceCodec.encodeToPairs(record()).map {
                if (it.first == PresenceRecord.KEY_PUBLIC_KEY) {
                    it.first to Base64Url.encode(ByteArray(size))
                } else {
                    it
                }
            }
            assertEquals(
                "a $size-byte key must be rejected: X25519 keys are exactly 32 bytes",
                PresenceCodec.Reason.BAD_PUBLIC_KEY,
                invalid(PresenceCodec.decodeFromPairs(pairs)).reason,
            )
        }
    }

    @Test
    fun `a malformed base64 public key is rejected`() {
        val pairs = PresenceCodec.encodeToPairs(record()).map {
            if (it.first == PresenceRecord.KEY_PUBLIC_KEY) it.first to "not!valid!base64" else it
        }
        assertEquals(
            PresenceCodec.Reason.BAD_PUBLIC_KEY,
            invalid(PresenceCodec.decodeFromPairs(pairs)).reason,
        )
    }

    @Test
    fun `the published key is unpadded base64url`() {
        // Padding carries no information and every byte of an mDNS TXT record costs space. 32 bytes
        // encodes to 43 characters with no "=".
        val encoded = PresenceCodec.encodeToPairs(record())
            .first { it.first == PresenceRecord.KEY_PUBLIC_KEY }.second
        assertEquals(43, encoded.length)
        assertTrue("must not contain padding", !encoded.contains('='))
        assertTrue("must be URL-safe", !encoded.contains('+') && !encoded.contains('/'))
    }

    // ── Construction guards ────────────────────────────────────────────────────────────────

    @Test
    fun `record construction rejects wrong-size fields`() {
        for (block in listOf<() -> Unit>(
            { record().copy(publicKeyHash = ByteArray(4)) },
            { record().copy(presenceToken = ByteArray(16)) },
            { record().copy(port = 0) },
            { record().copy(port = 70000) },
            { record().copy(publicKey = ByteArray(31)) },
        )) {
            var threw = false
            try {
                block()
            } catch (expected: IllegalArgumentException) {
                threw = true
            }
            assertTrue("expected rejection", threw)
        }
    }
}

/** The UDP beacon frame. Every test here is about not trusting the sender. */
class BeaconCodecTest {

    private val deviceId = DeviceId.fromPublicKey(ByteArray(32) { (it + 3).toByte() })

    private fun record() = PresenceRecord(
        version = Vlp.VERSION,
        deviceId = deviceId,
        platform = Platform.ANDROID,
        capabilities = Capabilities.ANDROID_CURRENT,
        publicKeyHash = ByteArray(8) { 1 },
        presenceToken = ByteArray(8) { 2 },
        name = "Phone",
        port = 40000,
        publicKey = ByteArray(32) { (it + 7).toByte() },
    )

    @Test
    fun `announce round trips`() {
        val frame = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record())
        val result = BeaconCodec.decode(frame)
        assertTrue("got $result", result is BeaconCodec.Result.Ok)
        result as BeaconCodec.Result.Ok
        assertEquals(BeaconCodec.Type.ANNOUNCE, result.type)
        assertEquals(record(), result.record)
    }

    @Test
    fun `a query with a payload decodes as a query`() {
        val frame = BeaconCodec.encode(BeaconCodec.Type.QUERY, record())
        val result = BeaconCodec.decode(frame)
        assertTrue("got $result", result is BeaconCodec.Result.Query)
        assertEquals(record(), (result as BeaconCodec.Result.Query).record)
    }

    @Test
    fun `a bare query is the smallest legal frame`() {
        val frame = BeaconCodec.encodeBareQuery()
        assertEquals(BeaconCodec.HEADER_BYTES, frame.size)
        val result = BeaconCodec.decode(frame)
        assertTrue("got $result", result is BeaconCodec.Result.Query)
        assertNull((result as BeaconCodec.Result.Query).record)
    }

    @Test
    fun `the header layout is exactly as specified`() {
        val frame = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record())
        assertEquals("VEYR", String(frame, 0, 4, Charsets.US_ASCII))
        assertEquals(Vlp.VERSION, frame[4].toInt())
        assertEquals(0x02, frame[5].toInt())
        val declared = ((frame[6].toInt() and 0xFF) shl 8) or (frame[7].toInt() and 0xFF)
        assertEquals(frame.size - BeaconCodec.HEADER_BYTES, declared)
    }

    @Test
    fun `a short frame is rejected`() {
        for (size in 0 until BeaconCodec.HEADER_BYTES) {
            val result = BeaconCodec.decode(ByteArray(size))
            assertEquals(
                "size $size",
                BeaconCodec.Reason.TOO_SHORT,
                (result as BeaconCodec.Result.Invalid).reason,
            )
        }
    }

    @Test
    fun `a foreign packet is rejected on the magic`() {
        // The normal case on a busy multicast group: most packets are something else entirely.
        val result = BeaconCodec.decode(ByteArray(40) { 0x7F })
        assertEquals(BeaconCodec.Reason.BAD_MAGIC, (result as BeaconCodec.Result.Invalid).reason)
    }

    @Test
    fun `a wrong version is rejected`() {
        val frame = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record())
        frame[4] = 9
        val result = BeaconCodec.decode(frame)
        assertEquals(BeaconCodec.Reason.BAD_VERSION, (result as BeaconCodec.Result.Invalid).reason)
    }

    @Test
    fun `an unknown type is rejected`() {
        val frame = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record())
        frame[5] = 0x55
        val result = BeaconCodec.decode(frame)
        assertEquals(BeaconCodec.Reason.BAD_TYPE, (result as BeaconCodec.Result.Invalid).reason)
    }

    @Test
    fun `a declared length longer than the frame is rejected`() {
        // The classic parser bug. In a network decoder, trusting this reads past the end of the
        // data actually received.
        val frame = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record())
        frame[6] = 0x7F
        frame[7] = 0xFF.toByte()
        val result = BeaconCodec.decode(frame)
        assertEquals(BeaconCodec.Reason.LENGTH_MISMATCH, (result as BeaconCodec.Result.Invalid).reason)
    }

    @Test
    fun `a declared length shorter than the frame is rejected`() {
        val frame = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record())
        frame[6] = 0
        frame[7] = 1
        val result = BeaconCodec.decode(frame)
        assertEquals(BeaconCodec.Reason.LENGTH_MISMATCH, (result as BeaconCodec.Result.Invalid).reason)
    }

    @Test
    fun `the received length is honoured over the buffer size`() {
        // DatagramPacket hands back a fixed-size buffer plus a separate length. Using the buffer
        // size would read uninitialised tail bytes as payload.
        val frame = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record())
        val oversizedBuffer = ByteArray(2048)
        frame.copyInto(oversizedBuffer)

        assertTrue(
            "with the correct length it must decode",
            BeaconCodec.decode(oversizedBuffer, frame.size) is BeaconCodec.Result.Ok,
        )
        assertTrue(
            "using the whole buffer must fail rather than read the tail",
            BeaconCodec.decode(oversizedBuffer, oversizedBuffer.size) is BeaconCodec.Result.Invalid,
        )
    }

    @Test
    fun `an announce with no payload is rejected`() {
        val frame = ByteArray(BeaconCodec.HEADER_BYTES)
        com.veyro.core.protocol.Vlp.UDP_MAGIC.copyInto(frame, 0)
        frame[4] = Vlp.VERSION.toByte()
        frame[5] = BeaconCodec.Type.ANNOUNCE.wire
        val result = BeaconCodec.decode(frame)
        assertEquals(BeaconCodec.Reason.BAD_PAYLOAD, (result as BeaconCodec.Result.Invalid).reason)
    }

    @Test
    fun `a corrupt payload is reported as a payload problem`() {
        val frame = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record())
        // Scribble over the payload but keep the length consistent.
        for (i in BeaconCodec.HEADER_BYTES until frame.size) frame[i] = 'Z'.code.toByte()
        val result = BeaconCodec.decode(frame)
        assertEquals(BeaconCodec.Reason.BAD_PAYLOAD, (result as BeaconCodec.Result.Invalid).reason)
    }

    @Test
    fun `every single-byte corruption is handled without throwing`() {
        // Totality matters more than the specific verdict: a decoder for network input must never
        // throw, because an exception on the receive loop kills discovery entirely.
        val original = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record())
        for (i in original.indices) {
            for (mask in intArrayOf(0x01, 0x80, 0xFF)) {
                val frame = original.copyOf()
                frame[i] = (frame[i].toInt() xor mask).toByte()
                val result = BeaconCodec.decode(frame)
                assertTrue(
                    "byte $i mask $mask produced $result",
                    result is BeaconCodec.Result.Ok ||
                        result is BeaconCodec.Result.Query ||
                        result is BeaconCodec.Result.Invalid,
                )
            }
        }
    }
}
