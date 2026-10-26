package com.veyro.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Noise handshake properties.
 *
 * ## What these tests do and do not prove
 *
 * They prove the **properties** that can be checked without published vectors: both sides derive
 * the same keys and the same SAS, traffic decrypts, tampering fails, replay fails, a wrong static
 * key fails, out-of-order messages fail, and a prologue mismatch fails.
 *
 * They do **not** prove interoperability. A handshake can satisfy every property here and still be
 * wrong in a way that only shows up against another implementation - a swapped pre-message order,
 * a big-endian nonce, the wrong cipher assigned to each direction. Those bugs are self-consistent
 * and therefore invisible to a test that plays both sides.
 *
 * Closing that gap needs the official Noise test vectors (ADR-004), which is still open. Until
 * then this handshake is **unproven for interop**, and `12-TESTING.md` section 3 says so.
 */
class NoiseHandshakeTest {

    private val random = SecureRandom()

    private fun keyPair(): Pair<ByteArray, ByteArray> {
        val priv = X25519.generatePrivateKey(random)
        return priv to X25519.publicKeyOf(priv)
    }

    /** Runs a complete KK handshake and returns both sides' transport ciphers. */
    private fun completeKk(
        initiatorPriv: ByteArray,
        initiatorPub: ByteArray,
        responderPriv: ByteArray,
        responderPub: ByteArray,
        prologue: ByteArray = VlpPrologue.session(),
        responderExpectsPub: ByteArray = initiatorPub,
    ): Pair<TransportCiphers, TransportCiphers> {
        val initiator = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, true, initiatorPriv, responderPub, prologue, random,
        )
        val responder = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, false, responderPriv, responderExpectsPub, prologue, random,
        )

        val m1 = initiator.writeMessage(ByteArray(0))!!
        responder.readMessage(m1)
        val m2 = responder.writeMessage(ByteArray(0))!!
        initiator.readMessage(m2)

        assertTrue(initiator.isComplete)
        assertTrue(responder.isComplete)
        return initiator.split() to responder.split()
    }

    // ── KK: the paired-session case ────────────────────────────────────────────────────────

    @Test
    fun `KK completes and both sides agree on the session`() {
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()
        val (a, b) = completeKk(ipriv, ipub, rpriv, rpub)

        assertArrayEquals("session ids must match", a.sessionId, b.sessionId)
        assertEquals("the SAS must match or pairing verification is meaningless", a.shortAuthString, b.shortAuthString)
        assertArrayEquals("pairing secrets must match", a.pairingSecret, b.pairingSecret)
    }

    @Test
    fun `traffic flows in both directions`() {
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()
        val (a, b) = completeKk(ipriv, ipub, rpriv, rpub)

        val up = "hello from the initiator".toByteArray()
        assertArrayEquals(up, b.decrypt(a.encrypt(up)))

        val down = "hello from the responder".toByteArray()
        assertArrayEquals(down, a.decrypt(b.encrypt(down)))
    }

    @Test
    fun `many messages keep their order and decrypt`() {
        // Exercises the nonce counter. A nonce that does not advance, or advances differently on
        // the two sides, breaks somewhere after the first message rather than immediately.
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()
        val (a, b) = completeKk(ipriv, ipub, rpriv, rpub)

        for (i in 0 until 64) {
            val payload = "message $i".toByteArray()
            assertArrayEquals(payload, b.decrypt(a.encrypt(payload)))
        }
    }

    @Test
    fun `a handshake payload is delivered`() {
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()
        val initiator = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, true, ipriv, rpub, VlpPrologue.session(), random,
        )
        val responder = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, false, rpriv, ipub, VlpPrologue.session(), random,
        )

        val sent = "early data".toByteArray()
        val received = responder.readMessage(initiator.writeMessage(sent)!!)
        assertArrayEquals(sent, received)
    }

    @Test
    fun `each handshake produces a different session`() {
        // Forward secrecy depends on fresh ephemerals. Identical sessions across runs would mean
        // the ephemerals are not actually random.
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()
        val first = completeKk(ipriv, ipub, rpriv, rpub).first
        val second = completeKk(ipriv, ipub, rpriv, rpub).first

        assertFalse(
            "the same static keys must still give a fresh session each time",
            first.sessionId.contentEquals(second.sessionId),
        )
        assertFalse(first.pairingSecret.contentEquals(second.pairingSecret))
    }

    // ── Rejection ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a wrong expected static key fails the handshake`() {
        // The core property: an attacker who claims a deviceId but lacks the key cannot complete
        // a session (REQ-S06).
        val (ipriv, _) = keyPair()
        val (rpriv, rpub) = keyPair()
        val (_, wrongPub) = keyPair()

        val initiator = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, true, ipriv, rpub, VlpPrologue.session(), random,
        )
        val responder = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, false, rpriv, wrongPub, VlpPrologue.session(), random,
        )

        val m1 = initiator.writeMessage(ByteArray(0))!!
        var failed = false
        try {
            responder.readMessage(m1)
        } catch (expected: Throwable) {
            failed = true
        }
        assertTrue("a mismatched static key must fail", failed)
    }

    @Test
    fun `a tampered handshake message fails`() {
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()
        val initiator = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, true, ipriv, rpub, VlpPrologue.session(), random,
        )
        val responder = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, false, rpriv, ipub, VlpPrologue.session(), random,
        )

        val m1 = initiator.writeMessage(ByteArray(0))!!
        m1[m1.size - 1] = (m1[m1.size - 1].toInt() xor 0x01).toByte()

        var failed = false
        try {
            responder.readMessage(m1)
        } catch (expected: Throwable) {
            failed = true
        }
        assertTrue("a modified handshake message must fail the tag check", failed)
    }

    @Test
    fun `every single-byte tamper of the first message is rejected`() {
        // A tag check that only catches some positions is not a tag check. The ephemeral prefix is
        // included: corrupting it changes the DH result and must also fail.
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()
        val reference = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, true, ipriv, rpub, VlpPrologue.session(), random,
        ).writeMessage(ByteArray(0))!!

        for (i in reference.indices) {
            val m = reference.copyOf()
            m[i] = (m[i].toInt() xor 0x80).toByte()
            val responder = NoiseHandshake.start(
                NoiseHandshake.Pattern.KK, false, rpriv, ipub, VlpPrologue.session(), random,
            )
            var failed = false
            try {
                responder.readMessage(m)
            } catch (expected: Throwable) {
                failed = true
            }
            assertTrue("tamper at byte $i was not rejected", failed)
        }
    }

    @Test
    fun `tampered transport traffic fails`() {
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()
        val (a, b) = completeKk(ipriv, ipub, rpriv, rpub)

        val sealed = a.encrypt("payload".toByteArray())
        sealed[0] = (sealed[0].toInt() xor 0x01).toByte()

        var failed = false
        try {
            b.decrypt(sealed)
        } catch (expected: Throwable) {
            failed = true
        }
        assertTrue("tampered transport data must fail", failed)
    }

    @Test
    fun `replaying a recorded handshake message does not produce a session`() {
        // REQ-S05. Fresh ephemerals on both sides mean a recorded first message cannot be reused
        // to establish a new session with a responder that has moved on.
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()

        val initiator = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, true, ipriv, rpub, VlpPrologue.session(), random,
        )
        val recorded = initiator.writeMessage(ByteArray(0))!!

        val responderA = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, false, rpriv, ipub, VlpPrologue.session(), random,
        )
        responderA.readMessage(recorded)
        val replyA = responderA.writeMessage(ByteArray(0))!!

        // Replay the same first message to a second responder instance.
        val responderB = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, false, rpriv, ipub, VlpPrologue.session(), random,
        )
        responderB.readMessage(recorded)
        val replyB = responderB.writeMessage(ByteArray(0))!!

        // Both accepted the replayed message - that alone is expected for a stateless responder.
        // The property that matters is that the resulting sessions differ, so a recorded transcript
        // cannot be resumed and the attacker holds no usable keys.
        assertFalse(
            "a replayed handshake must not reproduce the original session",
            replyA.contentEquals(replyB),
        )
        assertFalse(
            responderA.split().sessionId.contentEquals(responderB.split().sessionId),
        )
    }

    @Test
    fun `a prologue mismatch fails the handshake`() {
        // This is what closes the downgrade path: the version and mode are bound into the
        // transcript, so rewriting either gives a mismatch instead of weaker parameters.
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()
        val initiator = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, true, ipriv, rpub, VlpPrologue.session(), random,
        )
        val responder = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, false, rpriv, ipub, VlpPrologue.pairing(), random,
        )

        var failed = false
        try {
            responder.readMessage(initiator.writeMessage(ByteArray(0))!!)
        } catch (expected: Throwable) {
            failed = true
        }
        assertTrue("a session handshake must not complete against a pairing prologue", failed)
    }

    @Test
    fun `writing out of turn is refused`() {
        val (ipriv, _) = keyPair()
        val (_, rpub) = keyPair()
        val initiator = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, true, ipriv, rpub, VlpPrologue.session(), random,
        )
        initiator.writeMessage(ByteArray(0))

        var failed = false
        try {
            initiator.writeMessage(ByteArray(0))
        } catch (expected: IllegalStateException) {
            failed = true
        }
        assertTrue("the initiator must not write twice in a row", failed)
    }

    @Test
    fun `reading out of turn is refused`() {
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()
        val responder = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, false, rpriv, ipub, VlpPrologue.session(), random,
        )
        var failed = false
        try {
            // The responder must read first; writing before reading is not a legal step.
            responder.writeMessage(ByteArray(0))
        } catch (expected: IllegalStateException) {
            failed = true
        }
        assertTrue("the responder must not write before reading", failed)
    }

    @Test
    fun `a truncated handshake message is rejected without throwing an index error`() {
        val (ipriv, ipub) = keyPair()
        val (rpriv, _) = keyPair()
        for (size in intArrayOf(0, 1, 16, 31, 32, 40, 47)) {
            val responder = NoiseHandshake.start(
                NoiseHandshake.Pattern.KK, false, rpriv, ipub, VlpPrologue.session(), random,
            )
            var threw = false
            try {
                responder.readMessage(ByteArray(size))
            } catch (expected: Throwable) {
                threw = true
                assertFalse(
                    "must not be an array bounds error: ${expected::class.java.simpleName}",
                    expected is ArrayIndexOutOfBoundsException,
                )
            }
            assertTrue("a $size-byte message must be rejected", threw)
        }
    }

    @Test
    fun `split before completion is refused`() {
        val (ipriv, _) = keyPair()
        val (_, rpub) = keyPair()
        val initiator = NoiseHandshake.start(
            NoiseHandshake.Pattern.KK, true, ipriv, rpub, VlpPrologue.session(), random,
        )
        var failed = false
        try {
            initiator.split()
        } catch (expected: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
    }

    // ── XK: the pairing case ───────────────────────────────────────────────────────────────

    @Test
    fun `XK completes and both sides agree`() {
        val (ipriv, _) = keyPair()
        val (rpriv, rpub) = keyPair()

        val initiator = NoiseHandshake.start(
            NoiseHandshake.Pattern.XK, true, ipriv, rpub, VlpPrologue.pairing(), random,
        )
        // The responder does not know the initiator's static key - that is the point of XK.
        val responder = NoiseHandshake.start(
            NoiseHandshake.Pattern.XK, false, rpriv, null, VlpPrologue.pairing(), random,
        )

        responder.readMessage(initiator.writeMessage(ByteArray(0))!!)
        initiator.readMessage(responder.writeMessage(ByteArray(0))!!)

        val a = initiator.split()
        val b = responder.split()
        assertArrayEquals(a.sessionId, b.sessionId)
        assertEquals(a.shortAuthString, b.shortAuthString)
        assertArrayEquals(a.pairingSecret, b.pairingSecret)
    }

    @Test
    fun `XK requires the initiator to know the responder static key`() {
        // Without the out-of-band key from the QR code there is nothing binding the handshake to
        // the intended device, and an active attacker could MITM it. So this must be impossible to
        // express, not merely discouraged.
        val (ipriv, _) = keyPair()
        var failed = false
        try {
            NoiseHandshake.start(
                NoiseHandshake.Pattern.XK, true, ipriv, null, VlpPrologue.pairing(), random,
            )
        } catch (expected: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `KK requires both static keys up front`() {
        val (ipriv, _) = keyPair()
        var failed = false
        try {
            NoiseHandshake.start(
                NoiseHandshake.Pattern.KK, true, ipriv, null, VlpPrologue.session(), random,
            )
        } catch (expected: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `XK and KK produce different sessions from the same keys`() {
        // The pattern name is mixed into the transcript, so the two modes cannot be confused for
        // one another even with identical key material.
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()

        val kk = completeKk(ipriv, ipub, rpriv, rpub).first

        val xkInit = NoiseHandshake.start(
            NoiseHandshake.Pattern.XK, true, ipriv, rpub, VlpPrologue.pairing(), random,
        )
        val xkResp = NoiseHandshake.start(
            NoiseHandshake.Pattern.XK, false, rpriv, null, VlpPrologue.pairing(), random,
        )
        xkResp.readMessage(xkInit.writeMessage(ByteArray(0))!!)
        xkInit.readMessage(xkResp.writeMessage(ByteArray(0))!!)

        assertFalse(kk.sessionId.contentEquals(xkInit.split().sessionId))
    }

    // ── Derived values ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the SAS is six digits`() {
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()
        for (i in 0 until 20) {
            val (a, _) = completeKk(ipriv, ipub, rpriv, rpub)
            assertEquals(6, a.shortAuthString.length)
            assertTrue(a.shortAuthString.all { it in '0'..'9' })
        }
    }

    @Test
    fun `derived values are distinct from one another`() {
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()
        val (a, _) = completeKk(ipriv, ipub, rpriv, rpub)
        assertEquals(16, a.sessionId.size)
        assertEquals(32, a.pairingSecret.size)
        assertFalse(a.pairingSecret.copyOf(16).contentEquals(a.sessionId))
    }

    @Test
    fun `the engine produces working handshakes`() {
        val (ipriv, ipub) = keyPair()
        val (rpriv, rpub) = keyPair()
        val initiatorEngine = NoiseHandshakeEngine({ ipriv }, random)
        val responderEngine = NoiseHandshakeEngine({ rpriv }, random)

        val initiator = initiatorEngine.beginSession(rpub, initiator = true)
        val responder = responderEngine.beginSession(ipub, initiator = false)

        responder.readMessage(initiator.writeMessage()!!)
        initiator.readMessage(responder.writeMessage()!!)

        assertNotNull(initiator.split().sessionId)
        assertArrayEquals(initiator.split().sessionId, responder.split().sessionId)
    }
}
