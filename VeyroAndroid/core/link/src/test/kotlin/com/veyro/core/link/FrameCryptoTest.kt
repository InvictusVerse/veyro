package com.veyro.core.link

import com.veyro.core.crypto.LinkKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Session frame sealing and opening (04-PROTOCOL.md 5, ADR-058). Every rejection here closes a
 * real connection; see [PeerConnectionV3Test] for that half.
 */
class FrameCryptoTest {

    private val idI = "INITIATOR"
    private val idR = "RESPONDER"
    private val keys: Pair<LinkKeys, LinkKeys> = TestLinkKeys.sessionPair(idI, idR)
    private fun sealer() = SessionSealer(keys.first.sendKey, idI, FrameCrypto.DIR_I2R)
    private fun opener() = SessionOpener(keys.second.recvKey, idI, FrameCrypto.DIR_I2R)

    @Test
    fun `a sealed frame opens in order and carries only f and c`() {
        val s = sealer()
        val o = opener()
        val wire = s.seal("""{"x":1}""")
        val obj = PeerMessageJson.codec.parseToJsonElement(wire).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(setOf("f", "c"), obj.keys)
        assertEquals("""{"x":1}""", o.open(wire))
        assertEquals("""{"x":2}""", o.open(s.seal("""{"x":2}""")))
        assertEquals(2, o.nextCounter)
    }

    @Test
    fun `the same plaintext seals differently at each counter`() {
        val s = sealer()
        assertNotEquals(s.seal("same"), s.seal("same"))
    }

    @Test
    fun `replay within the connection fails authentication`() {
        val s = sealer()
        val o = opener()
        val first = s.seal("one")
        o.open(first)
        val e = assertThrows(SessionFrameException::class.java) { o.open(first) }
        assertTrue(e.authFailed)
    }

    @Test
    fun `a skipped or reordered frame fails authentication`() {
        val s = sealer()
        s.seal("zero")
        val one = s.seal("one")
        assertTrue(assertThrows(SessionFrameException::class.java) { opener().open(one) }.authFailed)
    }

    @Test
    fun `a reflected frame fails under the other direction key`() {
        val s = sealer()
        val reflected = SessionOpener(keys.first.recvKey, idI, FrameCrypto.DIR_R2I)
        assertTrue(assertThrows(SessionFrameException::class.java) { reflected.open(s.seal("back")) }.authFailed)
    }

    @Test
    fun `a frame from another connection with the same pairing secret fails`() {
        val other = TestLinkKeys.sessionPair(idI, idR)
        val foreign = SessionSealer(other.first.sendKey, idI, FrameCrypto.DIR_I2R).seal("recorded")
        assertTrue(assertThrows(SessionFrameException::class.java) { opener().open(foreign) }.authFailed)
    }

    @Test
    fun `a wrong f is refused before any crypto`() {
        val wire = sealer().seal("x").replace("\"f\":\"$idI\"", "\"f\":\"$idR\"")
        val e = assertThrows(SessionFrameException::class.java) { opener().open(wire) }
        assertFalse(e.authFailed)
    }

    @Test
    fun `flipped bits in ciphertext or tag fail authentication`() {
        val wire = PeerMessageJson.codec.decodeFromString(LinkWireFrame.serializer(), sealer().seal("""{"payload":1}"""))
        val raw = Base64.getDecoder().decode(wire.ciphertext)
        for (index in listOf(0, raw.size - 1)) {
            val tampered = raw.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            val text = PeerMessageJson.codec.encodeToString(LinkWireFrame.serializer(), wire.copy(ciphertext = Base64.getEncoder().encodeToString(tampered)))
            assertTrue(assertThrows(SessionFrameException::class.java) { opener().open(text) }.authFailed)
        }
    }

    @Test
    fun `truncated or non-canonical c is malformed`() {
        val wire = PeerMessageJson.codec.decodeFromString(LinkWireFrame.serializer(), sealer().seal("x"))
        for (c in listOf("", "AAAA", wire.ciphertext.dropLast(4), wire.ciphertext.trimEnd('='), " " + wire.ciphertext, "-" + wire.ciphertext.drop(1))) {
            val text = PeerMessageJson.codec.encodeToString(LinkWireFrame.serializer(), wire.copy(ciphertext = c))
            assertThrows(SessionFrameException::class.java) { opener().open(text) }
        }
    }

    @Test
    fun `strict base64 accepts only canonical padded standard base64`() {
        assertArrayEquals(byteArrayOf(1, 2, 3), FrameCrypto.decodeStrictBase64("AQID"))
        assertArrayEquals(byteArrayOf(1, 2), FrameCrypto.decodeStrictBase64("AQI="))
        assertNull(FrameCrypto.decodeStrictBase64("AQJ="))   // non-zero padding bits
        assertNull(FrameCrypto.decodeStrictBase64("AQI"))    // missing padding
        assertNull(FrameCrypto.decodeStrictBase64("AQ=I"))   // padding inside
        assertNull(FrameCrypto.decodeStrictBase64("AQ I"))
        assertNull(FrameCrypto.decodeStrictBase64("AQ_I"))   // base64url alphabet
        assertNull(FrameCrypto.decodeKey32(Base64.getEncoder().encodeToString(ByteArray(31))))
        assertEquals(32, FrameCrypto.decodeKey32(Base64.getEncoder().encodeToString(ByteArray(32)))?.size)
    }

    @Test
    fun `session secrets are 32 random bytes`() {
        assertEquals(32, FrameCrypto.newSessionSecret().size)
        assertFalse(FrameCrypto.newSessionSecret().contentEquals(FrameCrypto.newSessionSecret()))
    }
}
