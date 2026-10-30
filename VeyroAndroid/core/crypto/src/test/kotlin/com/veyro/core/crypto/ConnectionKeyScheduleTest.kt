package com.veyro.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Properties of the v3 connection key schedule (ADR-058, AC 5). Byte values are pinned by the
 * `:core:link` vector tests against the v3 files under `protocol/testvectors/link`.
 */
class ConnectionKeyScheduleTest {

    private fun unhex(s: String) = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private val idI = "2C9VV72DFSF1A2SC0TN6QBAPEC"
    private val idR = "9NHR2CYTZRWMJXWSNTWVRKQW6R"
    private val secret = ByteArray(32) { 7 }
    private val transcript = ConnectionKeySchedule.transcript("p".toByteArray(), "a".toByteArray())

    @Test
    fun `EphemeralKey fromPrivate gives the RFC 7748 public key and agreement`() {
        val alice = EphemeralKey.fromPrivate(unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        val bob = EphemeralKey.fromPrivate(unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))
        assertArrayEquals(unhex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), alice.publicKey)
        assertArrayEquals(unhex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"), alice.agree(bob.publicKey))
        alice.wipe()
        assertThrows(IllegalStateException::class.java) { alice.agree(bob.publicKey) }
        assertEquals("EphemeralKey(<redacted>)", bob.toString())
    }

    @Test
    fun `a low-order peer key is refused`() {
        assertThrows(IllegalArgumentException::class.java) { EphemeralKey.generate().agree(ByteArray(32)) }
    }

    @Test
    fun `direction keys differ and both roles agree`() {
        val dh = ByteArray(32) { 9 }
        val i = ConnectionKeySchedule.session(LinkRole.INITIATOR, idI, idR, transcript, dh, secret)
        val r = ConnectionKeySchedule.session(LinkRole.RESPONDER, idI, idR, transcript, dh, secret)
        assertFalse(i.sendKey.contentEquals(i.recvKey))
        assertArrayEquals(i.sendKey, r.recvKey)
        assertArrayEquals(i.recvKey, r.sendKey)
        assertArrayEquals(i.expSecret, r.expSecret)
        assertNull(i.chainKey)
        assertEquals(idR, i.peerId)
        assertEquals(idI, r.peerId)
        assertFalse(i.toString().contains(i.sendKey.joinToString()))
    }

    @Test
    fun `one changed transcript byte, a wrong DH or a wrong secret changes every key`() {
        val dh = ByteArray(32) { 9 }
        val base = ConnectionKeySchedule.session(LinkRole.INITIATOR, idI, idR, transcript, dh, secret)
        val otherTranscript = transcript.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val variants = listOf(
            ConnectionKeySchedule.session(LinkRole.INITIATOR, idI, idR, otherTranscript, dh, secret),
            ConnectionKeySchedule.session(LinkRole.INITIATOR, idI, idR, transcript, ByteArray(32) { 8 }, secret),
            ConnectionKeySchedule.session(LinkRole.INITIATOR, idI, idR, transcript, dh, ByteArray(32) { 6 }),
            ConnectionKeySchedule.session(LinkRole.INITIATOR, idR, idI, transcript, dh, secret),
        )
        for (v in variants) {
            assertFalse(v.sendKey.contentEquals(base.sendKey))
            assertFalse(v.recvKey.contentEquals(base.recvKey))
            assertFalse(v.expSecret!!.contentEquals(base.expSecret!!))
        }
    }

    @Test
    fun `afterPairing wipes the chain key and gives session keys with an exporter`() {
        val boot = ConnectionKeySchedule.pairQr(LinkRole.INITIATOR, idI, idR, transcript, ByteArray(32) { 1 }, ByteArray(32) { 2 }, ByteArray(32) { 3 })
        assertNull(boot.expSecret)
        val session = ConnectionKeySchedule.afterPairing(boot, secret)
        assertEquals(LinkMode.SESSION, session.mode)
        assertTrue(boot.chainKey!!.all { it.toInt() == 0 })
        assertThrows(IllegalArgumentException::class.java) { ConnectionKeySchedule.afterPairing(session, secret) }
    }

    @Test
    fun `exporter validates its arguments and separates labels and contexts`() {
        val exp = ByteArray(32) { 4 }
        val a = ConnectionKeySchedule.export(exp, "veyro-vtc3", byteArrayOf(1, 2), 32)
        assertFalse(a.contentEquals(ConnectionKeySchedule.export(exp, "veyro-vtc3", byteArrayOf(1), 32)))
        assertFalse(a.contentEquals(ConnectionKeySchedule.export(exp, "veyro-vtc", byteArrayOf(3, 1, 2), 32)))
        assertEquals(8160, ConnectionKeySchedule.export(exp, "x", ByteArray(1024), 8160).size)
        for (bad in listOf("", "a b", "\u00e9", "a".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) { ConnectionKeySchedule.export(exp, bad, ByteArray(0), 32) }
        }
        assertThrows(IllegalArgumentException::class.java) { ConnectionKeySchedule.export(exp, "x", ByteArray(1025), 32) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionKeySchedule.export(exp, "x", ByteArray(0), 8161) }
    }

    @Test
    fun `the QR secret derivations are domain separated`() {
        val s = ByteArray(16) { 1 }
        assertFalse(ConnectionKeySchedule.qrMix(s).contentEquals(ConnectionKeySchedule.qrProofKey(s)))
        val proof = ConnectionKeySchedule.qrProof(ConnectionKeySchedule.qrProofKey(s), idI, idR, transcript)
        assertFalse(proof.contentEquals(ConnectionKeySchedule.qrProof(ConnectionKeySchedule.qrProofKey(s), idR, idI, transcript)))
    }
}
