package com.veyro.core.link

import com.veyro.core.crypto.ConnectionKeySchedule
import com.veyro.core.crypto.Hkdf
import com.veyro.core.crypto.LinkRole
import com.veyro.core.crypto.X25519
import com.veyro.core.protocol.ChaChaPoly
import com.veyro.core.protocol.PairingQrCode
import com.veyro.core.crypto.PairConfirm
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec

/**
 * Link protocol v3 key schedules against `protocol/testvectors/link/connection-schedule-v3.json`
 * and `pairing-schedule-v3.json` (ADR-058). The expected values come from `tools/GenV3.java`,
 * which uses only JDK primitives and checks itself against the RFC vectors first. The Windows
 * suite parses the same files.
 */
class ConnectionScheduleVectorTest {

    private val file = V3Vectors.load("link/connection-schedule-v3.json")

    @Test
    fun `session schedule matches every vector on both sides`() {
        for (v in file.arr("vectors").map { it.jsonObject }) {
            val input = v.obj("input")
            val expected = v.obj("expected")
            val p = input.str("preamble").toByteArray()
            val a = input.str("ack").toByteArray()
            assertEquals(expected.str("preambleHex"), V3Vectors.hex(p))
            assertEquals(expected.str("ackHex"), V3Vectors.hex(a))
            val transcript = ConnectionKeySchedule.transcript(p, a)
            assertEquals(expected.str("transcriptHex"), V3Vectors.hex(transcript))

            val eI = input.bytes("ephemeralPrivateIHex")
            val eR = input.bytes("ephemeralPrivateRHex")
            assertEquals(expected.str("ephemeralPublicIHex"), V3Vectors.hex(X25519.publicKeyOf(eI)))
            assertEquals(expected.str("ephemeralPublicRHex"), V3Vectors.hex(X25519.publicKeyOf(eR)))
            val dhI = X25519.agree(eI, X25519.publicKeyOf(eR))
            val dhR = X25519.agree(eR, X25519.publicKeyOf(eI))
            assertEquals(expected.str("dhHex"), V3Vectors.hex(dhI))
            assertArrayEquals(dhI, dhR)

            val idI = input.str("idI")
            val idR = input.str("idR")
            val secret = input.bytes("pairingSecretHex")
            val initiator = ConnectionKeySchedule.session(LinkRole.INITIATOR, idI, idR, transcript, dhI, secret)
            val responder = ConnectionKeySchedule.session(LinkRole.RESPONDER, idI, idR, transcript, dhR, secret)
            assertEquals(expected.str("kI2rHex"), V3Vectors.hex(initiator.sendKey))
            assertEquals(expected.str("kR2iHex"), V3Vectors.hex(initiator.recvKey))
            assertEquals(expected.str("kI2rHex"), V3Vectors.hex(responder.recvKey))
            assertEquals(expected.str("kR2iHex"), V3Vectors.hex(responder.sendKey))
            assertEquals(expected.str("expSecretHex"), V3Vectors.hex(initiator.expSecret!!))
            assertArrayEquals(initiator.expSecret, responder.expSecret)
            assertEquals(expected.str("prkHex"), V3Vectors.hex(Hkdf.extract(transcript, dhI + secret)))
        }
    }

    @Test
    fun `two connections with the same pairing secret share no key`() {
        val (a, b) = file.arr("vectors").map { it.jsonObject.obj("expected") }
        for (field in listOf("transcriptHex", "dhHex", "kI2rHex", "kR2iHex", "expSecretHex")) {
            assertFalse("$field must differ between connections", a.str(field) == b.str(field))
        }
        assertFalse(a.str("kI2rHex") == a.str("kR2iHex"))
    }

    @Test
    fun `confirm pairing schedule and the switch to session keys match`() {
        val v = V3Vectors.load("link/pairing-schedule-v3.json").obj("confirm")
        val input = v.obj("input")
        val expected = v.obj("expected")
        val idI = input.str("idI")
        val idR = input.str("idR")
        val transcript = ConnectionKeySchedule.transcript(input.str("preamble").toByteArray(), input.str("ack").toByteArray())
        assertEquals(expected.str("transcriptHex"), V3Vectors.hex(transcript))
        val pkI = X25519.publicKeyOf(input.bytes("staticPrivateIHex"))
        val pkR = X25519.publicKeyOf(input.bytes("staticPrivateRHex"))
        assertEquals(expected.str("commitHex"), V3Vectors.hex(PairConfirm.commitment(pkR, pkI, input.bytes("nBHex"))))

        val staticDh = X25519.agree(input.bytes("staticPrivateIHex"), pkR)
        assertEquals(expected.str("staticDhHex"), V3Vectors.hex(staticDh))
        assertEquals(expected.str("staticInfoHex"), V3Vectors.hex(ConnectionKeySchedule.staticInfo(idI, idR)))
        // What IdentityManager.deriveSharedKey(pk, salt = transcript, info) computes.
        val staticKey = Hkdf.deriveKey(staticDh, transcript, ConnectionKeySchedule.staticInfo(idI, idR), 32)
        assertEquals(expected.str("staticKeyHex"), V3Vectors.hex(staticKey))

        val dh = X25519.agree(input.bytes("ephemeralPrivateIHex"), X25519.publicKeyOf(input.bytes("ephemeralPrivateRHex")))
        assertEquals(expected.str("dhHex"), V3Vectors.hex(dh))
        val boot = ConnectionKeySchedule.pairConfirm(LinkRole.INITIATOR, idI, idR, transcript, input.bytes("nAHex"), input.bytes("nBHex"), dh, staticKey)
        assertEquals(expected.str("kI2rHex"), V3Vectors.hex(boot.sendKey))
        assertEquals(expected.str("kR2iHex"), V3Vectors.hex(boot.recvKey))
        assertEquals(expected.str("chainKeyHex"), V3Vectors.hex(boot.chainKey!!))
        assertTrue("pairing keys export nothing", boot.expSecret == null)

        val session = ConnectionKeySchedule.afterPairing(boot, input.bytes("newPairingSecretHex"))
        assertEquals(expected.str("sessionKI2rHex"), V3Vectors.hex(session.sendKey))
        assertEquals(expected.str("sessionKR2iHex"), V3Vectors.hex(session.recvKey))
        assertEquals(expected.str("sessionExpSecretHex"), V3Vectors.hex(session.expSecret!!))
        assertTrue("afterPairing wipes the chain key", boot.chainKey!!.all { it.toInt() == 0 })
    }

    @Test
    fun `QR pairing schedule, secret derivations and proof match`() {
        val v = V3Vectors.load("link/pairing-schedule-v3.json").obj("qr")
        val input = v.obj("input")
        val expected = v.obj("expected")
        val idI = input.str("idI")
        val idR = input.str("idR")
        val parsed = PairingQrCode.parse(input.str("qrString"), input.num("qrNowEpochSeconds"))
        val qr = (parsed as PairingQrCode.Result.Valid).qr
        assertEquals(input.str("qrSecretHex"), V3Vectors.hex(qr.secret))
        assertEquals(idR, qr.deviceId.value)

        val secret = input.bytes("qrSecretHex")
        assertEquals(expected.str("qrPrkHex"), V3Vectors.hex(ConnectionKeySchedule.qrPrk(secret)))
        val proofKey = ConnectionKeySchedule.qrProofKey(secret)
        val qrMix = ConnectionKeySchedule.qrMix(secret)
        assertEquals(expected.str("proofKeyHex"), V3Vectors.hex(proofKey))
        assertEquals(expected.str("qrMixHex"), V3Vectors.hex(qrMix))

        val transcript = ConnectionKeySchedule.transcript(input.str("preamble").toByteArray(), input.str("ack").toByteArray())
        assertEquals(expected.str("transcriptHex"), V3Vectors.hex(transcript))
        val proof = ConnectionKeySchedule.qrProof(proofKey, idI, idR, transcript)
        assertEquals(expected.str("qrProofHex"), V3Vectors.hex(proof))
        assertEquals(expected.str("qrProofBase64"), FrameCrypto.b64(proof))

        val pkR = X25519.publicKeyOf(input.bytes("staticPrivateRHex"))
        val staticKey = Hkdf.deriveKey(X25519.agree(input.bytes("staticPrivateIHex"), pkR), transcript, ConnectionKeySchedule.staticInfo(idI, idR), 32)
        assertEquals(expected.str("staticKeyHex"), V3Vectors.hex(staticKey))
        val dhR = X25519.agree(input.bytes("ephemeralPrivateRHex"), X25519.publicKeyOf(input.bytes("ephemeralPrivateIHex")))
        val boot = ConnectionKeySchedule.pairQr(LinkRole.RESPONDER, idI, idR, transcript, dhR, staticKey, qrMix)
        assertEquals(expected.str("kI2rHex"), V3Vectors.hex(boot.recvKey))
        assertEquals(expected.str("kR2iHex"), V3Vectors.hex(boot.sendKey))
        assertEquals(expected.str("chainKeyHex"), V3Vectors.hex(boot.chainKey!!))
        val session = ConnectionKeySchedule.afterPairing(boot, input.bytes("newPairingSecretHex"))
        assertEquals(expected.str("sessionKI2rHex"), V3Vectors.hex(session.recvKey))
        assertEquals(expected.str("sessionExpSecretHex"), V3Vectors.hex(session.expSecret!!))
    }
}

/** The RFC vectors in `protocol/testvectors/crypto`, through the platform primitives Veyro uses. */
class ChaChaPolyVectorTest {

    @Test
    fun `RFC 8439 AEAD vector through ChaChaPoly newCipher`() {
        val v = V3Vectors.load("crypto/chacha20poly1305.json").arr("vectors")[0].jsonObject
        val cipher: Cipher = ChaChaPoly.newCipher()
        cipher.init(Cipher.ENCRYPT_MODE, ChaChaPoly.keySpec(v.bytes("keyHex")), IvParameterSpec(v.bytes("nonceHex")))
        cipher.updateAAD(v.bytes("aadHex"))
        val sealed = cipher.doFinal(v.bytes("plaintextHex"))
        assertEquals(v.str("ciphertextHex") + v.str("tagHex"), V3Vectors.hex(sealed))

        val open: Cipher = ChaChaPoly.newCipher()
        open.init(Cipher.DECRYPT_MODE, ChaChaPoly.keySpec(v.bytes("keyHex")), IvParameterSpec(v.bytes("nonceHex")))
        open.updateAAD(v.bytes("aadHex"))
        assertEquals(v.str("plaintextHex"), V3Vectors.hex(open.doFinal(sealed)))
    }

    @Test
    fun `RFC 5869 HKDF vectors`() {
        for (v in V3Vectors.load("crypto/hkdf-sha256.json").arr("vectors").map { it.jsonObject }) {
            val prk = Hkdf.extract(v.bytes("saltHex"), v.bytes("ikmHex"))
            assertEquals(v.str("prkHex"), V3Vectors.hex(prk))
            assertEquals(v.str("okmHex"), V3Vectors.hex(Hkdf.expand(prk, v.bytes("infoHex"), v.getValue("length").jsonPrimitive.int)))
        }
    }

    @Test
    fun `RFC 7748 X25519 vector`() {
        val v = V3Vectors.load("crypto/x25519.json").arr("vectors")[0].jsonObject
        assertEquals(v.str("alicePublicHex"), V3Vectors.hex(X25519.publicKeyOf(v.bytes("alicePrivateHex"))))
        assertEquals(v.str("bobPublicHex"), V3Vectors.hex(X25519.publicKeyOf(v.bytes("bobPrivateHex"))))
        assertEquals(v.str("sharedHex"), V3Vectors.hex(X25519.agree(v.bytes("alicePrivateHex"), v.bytes("bobPublicHex"))))
    }
}
