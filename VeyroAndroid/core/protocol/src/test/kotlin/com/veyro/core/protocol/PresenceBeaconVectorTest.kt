package com.veyro.core.protocol

import com.veyro.core.model.Capabilities
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Platform
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery encodings, checked against `protocol/testvectors/discovery/presence-beacon.json`.
 *
 * Expected values were derived from `ProjectInfo/04-PROTOCOL.md` section 3 by an independent
 * script linking against neither implementation. The Windows suite loads the same JSON.
 *
 * The public key used is the all-zero key from `identity/device-id.json`, so this file and that
 * one describe the same hypothetical device; a mistake in the identity derivation surfaces here
 * as well.
 */
class PresenceBeaconVectorTest {

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }

    /** pk8 = SHA-256(ascii("veyro-pk-v1") || staticPublicKey)[0..7] */
    private fun pkHash8(publicKey: ByteArray): ByteArray {
        val prefix = "veyro-pk-v1".toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(prefix + publicKey)
        return digest.copyOfRange(0, 8)
    }

    private val publicKey = ByteArray(32) // all-zero, matching identity/device-id.json
    private val deviceId = "6JE8415YZQQYQMBD34569HVYX4"
    private val token = unhex("0102030405060708")

    private fun record(name: String?, withKey: Boolean) = PresenceRecord(
        version = 1,
        deviceId = DeviceId(deviceId),
        platform = Platform.WINDOWS,
        capabilities = Capabilities(31),
        publicKeyHash = pkHash8(publicKey),
        presenceToken = token,
        name = name,
        port = 47650,
        publicKey = if (withKey) publicKey else null,
    )

    @Test
    fun `vector - pk8 derivation`() {
        assertEquals("0efbac270507d720", hex(pkHash8(publicKey)))
    }

    @Test
    fun `vector - full record in public mode`() {
        val expected = "v=1\n" +
            "id=6JE8415YZQQYQMBD34569HVYX4\n" +
            "pf=windows\n" +
            "cap=1f\n" +
            "pk8=0efbac270507d720\n" +
            "tok=0102030405060708\n" +
            "p=47650\n" +
            "n=Test PC\n" +
            "pk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        val bytes = PresenceCodec.encodeToBytes(record("Test PC", withKey = true))
        assertEquals(158, bytes.size)
        assertEquals(expected, String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `vector - private mode omits name and public key entirely`() {
        val expected = "v=1\n" +
            "id=6JE8415YZQQYQMBD34569HVYX4\n" +
            "pf=windows\n" +
            "cap=1f\n" +
            "pk8=0efbac270507d720\n" +
            "tok=0102030405060708\n" +
            "p=47650"

        val text = String(PresenceCodec.encodeToBytes(record(null, withKey = false)), Charsets.UTF_8)
        assertEquals(expected, text)

        // Omitted, never sent empty: an empty value still reveals that something is withheld.
        assertFalse(text.contains("n="))
        assertFalse(text.contains("pk="))
    }

    @Test
    fun `vector - bare QUERY is the smallest legal frame`() {
        val q = BeaconCodec.encodeBareQuery()
        assertEquals(8, q.size)
        assertEquals("5645595201010000", hex(q))
    }

    @Test
    fun `vector - ANNOUNCE carrying the full record`() {
        val frame = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record("Test PC", withKey = true))
        assertEquals(166, frame.size)

        // Header, then the payload bytes derived in the previous test. Built by concatenation
        // rather than one long literal, because hand-wrapping a 332-character hex string is how
        // the first version of this test acquired a transcription error.
        val expectedHeader = "564559520102009e"
        val expectedPayload = hex(PresenceCodec.encodeToBytes(record("Test PC", withKey = true)))
        assertEquals(expectedHeader + expectedPayload, hex(frame))

        // And the payload must be exactly the 158 bytes the vector file publishes.
        assertEquals(158, expectedPayload.length / 2)
    }

    @Test
    fun `rejection - declared length longer than the datagram`() {
        val frame = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record("Test PC", true))
        frame[6] = 0xFF.toByte()
        frame[7] = 0xFF.toByte()
        val r = BeaconCodec.decode(frame)
        assertTrue(r is BeaconCodec.Result.Invalid && r.reason == BeaconCodec.Reason.LENGTH_MISMATCH)
    }

    @Test
    fun `rejection - the received length is authoritative, not the buffer size`() {
        val frame = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record("Test PC", true))
        val buffer = ByteArray(2048)
        frame.copyInto(buffer)

        assertTrue(BeaconCodec.decode(buffer, frame.size) is BeaconCodec.Result.Ok)
        assertTrue(BeaconCodec.decode(buffer) is BeaconCodec.Result.Invalid)
    }

    @Test
    fun `rejection - foreign traffic is rejected on the magic`() {
        val r = BeaconCodec.decode("GET / HTTP/1.1".toByteArray(Charsets.US_ASCII))
        assertTrue(r is BeaconCodec.Result.Invalid && r.reason == BeaconCodec.Reason.BAD_MAGIC)
    }

    @Test
    fun `rejection - a version other than 1 is refused, not guessed`() {
        val frame = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record("Test PC", true))
        frame[4] = 0x02
        val r = BeaconCodec.decode(frame)
        assertTrue(r is BeaconCodec.Result.Invalid && r.reason == BeaconCodec.Reason.BAD_VERSION)
    }

    @Test
    fun `rejection - ANNOUNCE with an empty payload`() {
        val q = BeaconCodec.encodeBareQuery()
        q[5] = BeaconCodec.Type.ANNOUNCE.wire
        val r = BeaconCodec.decode(q)
        assertTrue(r is BeaconCodec.Result.Invalid && r.reason == BeaconCodec.Reason.BAD_PAYLOAD)
    }

    @Test
    fun `rejection - a duplicate key is rejected`() {
        val r = PresenceCodec.decodeFromBytes("v=1\nv=1\nid=X\ncap=0\np=1".toByteArray(Charsets.UTF_8))
        assertTrue(r is PresenceCodec.Result.Invalid && r.reason == PresenceCodec.Reason.DUPLICATE_KEY)
    }

    @Test
    fun `rejection - a malformed deviceId is rejected`() {
        // A deviceId is 26 Crockford Base32 characters. "X" is not one, and must not be accepted
        // merely because the key is present.
        val payload = "v=1\nid=X\npf=windows\ncap=0\npk8=0102030405060708\ntok=0102030405060708\np=1"
        val r = PresenceCodec.decodeFromBytes(payload.toByteArray(Charsets.UTF_8))
        assertTrue(r is PresenceCodec.Result.Invalid && r.reason == PresenceCodec.Reason.BAD_DEVICE_ID)
    }

    @Test
    fun `rejection - a name containing a bidi override is rejected`() {
        val payload = "v=1\nid=$deviceId\npf=windows\ncap=0\npk8=0102030405060708\n" +
            "tok=0102030405060708\np=1\nn=Trusted\u202EPC"
        val r = PresenceCodec.decodeFromBytes(payload.toByteArray(Charsets.UTF_8))
        assertTrue(r is PresenceCodec.Result.Invalid && r.reason == PresenceCodec.Reason.NAME_INVALID)
    }

    @Test
    fun `forward compatibility - unknown keys are ignored, not rejected`() {
        val payload = "v=1\nid=$deviceId\npf=windows\ncap=0\npk8=0102030405060708\n" +
            "tok=0102030405060708\np=1\nfuture=whatever"
        val r = PresenceCodec.decodeFromBytes(payload.toByteArray(Charsets.UTF_8))
        assertTrue(r is PresenceCodec.Result.Ok)
    }
}
