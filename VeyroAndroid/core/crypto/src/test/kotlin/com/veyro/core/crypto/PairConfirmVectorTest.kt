package com.veyro.core.crypto

import com.veyro.core.model.DeviceId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Confirm-code pairing derivations against `protocol/testvectors/pair-confirm.json`
 * (04-PROTOCOL.md section 4.3, ADR-031).
 *
 * The values were produced by a standalone Python reference written from the spec. They are pinned
 * inline (the module has no JSON dependency, matching `DeviceIdTest`), and one test checks that the
 * shared file still carries every pinned value, so the two cannot drift apart silently. The
 * Windows suite asserts the same file.
 */
class PairConfirmVectorTest {

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }

    private val skA = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val skB = unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    private val nA = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    private val nB = unhex("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf")

    private val pkAHex = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
    private val pkBHex = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    private val pkABase64 = "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo="
    private val pkBBase64 = "3p7bfXt9wbTTW2HC7OQ1Nz+DQ8hbeGdNrfx+FG+IK08="
    private val idA = "2C9VV72DFSF1A2SC0TN6QBAPEC"
    private val idB = "9NHR2CYTZRWMJXWSNTWVRKQW6R"
    private val sharedHex = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
    private val commitHex = "483ae04cfe9ae4f218fa2eea12f6e871a634955f36e609a9472b56a552cba4c3"
    private val commitBase64 = "SDrgTP6a5PIY+i7qEvbocaY0lV825gmpRytWpVLLpMM="
    private val code = "077622"
    private val codeDisplay = "077 622"
    private val bootstrapKeyHex = "b84180eb49c155584f6e82a5a38aff54065afa2b01577a25c466af68a57b2475"

    private val pkA get() = X25519.publicKeyOf(skA)
    private val pkB get() = X25519.publicKeyOf(skB)

    @Test
    fun `public keys and ids`() {
        assertEquals(pkAHex, hex(pkA))
        assertEquals(pkBHex, hex(pkB))
        assertEquals(pkABase64, Base64.getEncoder().encodeToString(pkA))
        assertEquals(pkBBase64, Base64.getEncoder().encodeToString(pkB))
        assertEquals(idA, DeviceId.fromPublicKey(pkA).value)
        assertEquals(idB, DeviceId.fromPublicKey(pkB).value)
        assertEquals(sharedHex, hex(X25519.agree(skA, pkB)))
        assertEquals(sharedHex, hex(X25519.agree(skB, pkA)))
    }

    @Test
    fun `commitment`() {
        val commit = PairConfirm.commitment(pkB, pkA, nB)
        assertEquals(commitHex, hex(commit))
        assertEquals(commitBase64, Base64.getEncoder().encodeToString(commit))
        assertTrue(PairConfirm.verifyCommitment(commit, pkB, pkA, nB))
    }

    @Test
    fun `commitment mismatch is detected`() {
        val commit = unhex(commitHex)
        val otherNb = nB.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(PairConfirm.verifyCommitment(commit, pkB, pkA, otherNb))
        // Swapped key order must not verify either: the order is part of the spec.
        assertFalse(PairConfirm.verifyCommitment(commit, pkA, pkB, nB))
        assertFalse(PairConfirm.verifyCommitment(commit.copyOf(31), pkB, pkA, nB))
    }

    @Test
    fun `code`() {
        assertEquals(code, PairConfirm.code(pkA, pkB, nA, nB))
        assertEquals(codeDisplay, PairConfirm.formatCode(code))
        // Swapping roles changes the code, so both sides must agree on who is A.
        assertNotEquals(code, PairConfirm.code(pkB, pkA, nB, nA))
    }

    // ADR-058: the v2 bootstrap key (bootstrapKeyHex in pair-confirm.json) is retired. The static
    // half of the v3 confirm schedule goes through the same identity call; its value is pinned by
    // link/pairing-schedule-v3.json (confirm: transcriptHex, staticKeyHex).
    private val v3TranscriptHex = "96d9e04d4e6853917249a880f153f546ed1369d49887b2bd6d288401bea6b746"
    private val v3StaticKeyHex = "e5a8fc1b998e73e89405ec843981059b50ba870a982fa854d773ee1bd812ad52"

    @Test
    fun `v3 static key from the shared secret`() {
        val key = Hkdf.deriveKey(unhex(sharedHex), unhex(v3TranscriptHex), ConnectionKeySchedule.staticInfo(idA, idB), 32)
        assertEquals(v3StaticKeyHex, hex(key))
    }

    @Test
    fun `v3 static key through the identity matches on both sides`() = runTest {
        val phone = DefaultIdentityManager(InMemorySeedStore().also { it.write(IdentityRecord(skA.copyOf(), DeviceId(idA), 0L)) })
        val pc = DefaultIdentityManager(InMemorySeedStore().also { it.write(IdentityRecord(skB.copyOf(), DeviceId(idB), 0L)) })
        val info = ConnectionKeySchedule.staticInfo(idA, idB)
        val kPhone = phone.deriveSharedKey(pkB, unhex(v3TranscriptHex), info)
        val kPc = pc.deriveSharedKey(pkA, unhex(v3TranscriptHex), info)
        assertEquals(v3StaticKeyHex, hex(kPhone))
        assertArrayEquals(kPhone, kPc)
    }
    @Test
    fun `shared vector file carries the pinned values`() {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "protocol/testvectors/pair-confirm.json").isFile) dir = dir.parentFile
        val file = File(requireNotNull(dir) { "protocol/testvectors not found above user.dir" }, "protocol/testvectors/pair-confirm.json")
        val text = file.readText()
        listOf(
            hex(skA), hex(skB), hex(nA), hex(nB), pkAHex, pkBHex, pkABase64, pkBBase64, idA, idB,
            sharedHex, commitHex, commitBase64, code, codeDisplay, bootstrapKeyHex,
        ).forEach { value -> assertTrue("pair-confirm.json lacks $value", text.contains("\"$value\"")) }
    }
}
