package com.veyro.core.crypto

import com.veyro.core.model.DeviceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the sealed-file identity store.
 *
 * These run on a plain JVM with a real filesystem and a real AES-GCM cipher. The only thing
 * substituted is the *source of the key*, via [SealingKeyProvider] - which is exactly the part
 * that needs a device.
 *
 * Worth being explicit about what is and is not covered:
 *  - **Covered:** container framing, version and magic handling, truncation, GCM tamper detection,
 *    AAD binding, atomic write, destroy semantics, key-loss recovery, round trip through the
 *    identity manager.
 *  - **Not covered here:** that the real Keystore key is hardware-backed and non-exportable. That
 *    is a property of [KeystoreSealingKeyProvider] and can only be verified on hardware.
 */
class SealedFileSeedStoreTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private lateinit var keyProvider: InMemorySealingKeyProvider
    private lateinit var file: File

    @Before
    fun setUp() {
        keyProvider = InMemorySealingKeyProvider()
        file = File(temp.root, "identity.bin")
    }

    private fun newStore(): SealedFileSeedStore = SealedFileSeedStore(file, keyProvider)

    private fun sampleRecord(seedByte: Int = 0x42, created: Long = 1_700_000_000_000L): IdentityRecord {
        val seed = ByteArray(32) { (it + seedByte).toByte() }
        return IdentityRecord(
            seed = seed,
            deviceId = DeviceId.fromPublicKey(X25519.publicKeyOf(seed)),
            createdAtEpochMillis = created,
        )
    }

    // ── Round trip ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `reading before anything is written returns null`() {
        assertNull(newStore().read())
    }

    @Test
    fun `write then read returns the same record`() {
        val store = newStore()
        val record = sampleRecord()
        store.write(record)

        val loaded = store.read()
        assertNotNull(loaded)
        assertArrayEquals(record.seed, loaded!!.seed)
        assertEquals(record.deviceId, loaded.deviceId)
        assertEquals(record.createdAtEpochMillis, loaded.createdAtEpochMillis)
    }

    @Test
    fun `createdAt survives a full byte range including large timestamps`() {
        val store = newStore()
        for (created in longArrayOf(0L, 1L, 1_700_000_000_000L, Long.MAX_VALUE)) {
            store.write(sampleRecord(created = created))
            assertEquals(created, store.read()!!.createdAtEpochMillis)
        }
    }

    @Test
    fun `the seed does not appear in the clear on disk`() {
        val store = newStore()
        val record = sampleRecord(seedByte = 0x11)
        store.write(record)

        assertFalse(
            "the seed must be encrypted at rest, not merely wrapped",
            file.readBytes().containsSubsequence(record.seed),
        )
    }

    @Test
    fun `the deviceId does not appear in the clear on disk`() {
        // It is not secret, but if it were readable the payload would clearly not be encrypted.
        val store = newStore()
        val record = sampleRecord()
        store.write(record)
        assertFalse(
            file.readBytes().containsSubsequence(record.deviceId.value.toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun `file carries the expected container header and size`() {
        newStore().write(sampleRecord())
        val raw = file.readBytes()

        assertEquals("VYID", String(raw, 0, 4, Charsets.US_ASCII))
        assertEquals(1, raw[4].toInt())
        // magic(4) + version(1) + iv(12) + payload(26+8+32=66) + tag(16) = 99
        assertEquals(99, raw.size)
    }

    @Test
    fun `each write uses a fresh IV`() {
        // GCM IV reuse under the same key is catastrophic, so this is a security property, not a
        // style preference.
        val store = newStore()
        val ivs = mutableSetOf<String>()
        repeat(16) {
            store.write(sampleRecord())
            ivs += file.readBytes().copyOfRange(5, 17).joinToString("") { b -> "%02x".format(b) }
        }
        assertEquals("every write must use a unique IV", 16, ivs.size)
    }

    @Test
    fun `overwriting replaces the previous identity`() {
        val store = newStore()
        store.write(sampleRecord(seedByte = 0x01))
        val second = sampleRecord(seedByte = 0x50)
        store.write(second)

        val loaded = store.read()!!
        assertArrayEquals(second.seed, loaded.seed)
        assertEquals(second.deviceId, loaded.deviceId)
    }

    @Test
    fun `no temporary file is left behind`() {
        newStore().write(sampleRecord())
        assertFalse(
            "a leftover .tmp means the atomic rename did not complete",
            File(temp.root, "identity.bin.tmp").exists(),
        )
    }

    @Test
    fun `a parent directory is created if missing`() {
        val nested = File(temp.root, "sub/dir/identity.bin")
        val store = SealedFileSeedStore(nested, InMemorySealingKeyProvider())
        store.write(sampleRecord())
        assertTrue(nested.exists())
    }

    // ── Tampering and corruption ───────────────────────────────────────────────────────────

    @Test
    fun `a flipped bit in the ciphertext is detected`() {
        val store = newStore()
        store.write(sampleRecord())

        val raw = file.readBytes()
        raw[raw.size - 20] = (raw[raw.size - 20].toInt() xor 0x01).toByte()
        file.writeBytes(raw)

        val e = runCatching { store.read() }.exceptionOrNull()
        assertTrue("GCM must reject a modified ciphertext, got $e", e is IdentityUnavailableException)
    }

    @Test
    fun `a flipped bit in the IV is detected`() {
        val store = newStore()
        store.write(sampleRecord())

        val raw = file.readBytes()
        raw[6] = (raw[6].toInt() xor 0x01).toByte()
        file.writeBytes(raw)

        val e = runCatching { store.read() }.exceptionOrNull()
        assertTrue("a modified IV must fail the tag check, got $e", e is IdentityUnavailableException)
    }

    @Test
    fun `every single-byte modification of the payload is detected`() {
        // A tag check that only catches some positions is not a tag check. This walks the whole
        // ciphertext region, which is cheap and makes the guarantee concrete.
        val store = newStore()
        store.write(sampleRecord())
        val original = file.readBytes()

        for (i in 17 until original.size) {
            val raw = original.copyOf()
            raw[i] = (raw[i].toInt() xor 0x80).toByte()
            file.writeBytes(raw)
            val e = runCatching { store.read() }.exceptionOrNull()
            assertTrue(
                "modification at byte $i was not detected",
                e is IdentityUnavailableException,
            )
        }
    }

    @Test
    fun `a truncated file is reported as truncated`() {
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))

        val e = runCatching { newStore().read() }.exceptionOrNull()
        assertTrue(e is IdentityUnavailableException)
        assertTrue("message should say truncated: ${e!!.message}", e.message!!.contains("truncated"))
    }

    @Test
    fun `a file with a bad magic is rejected`() {
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(99) { 0x7A })

        val e = runCatching { newStore().read() }.exceptionOrNull()
        assertTrue(e is IdentityUnavailableException)
        assertTrue("message should name the format: ${e!!.message}", e.message!!.contains("format"))
    }

    @Test
    fun `an unsupported version is rejected rather than misread`() {
        val store = newStore()
        store.write(sampleRecord())
        val raw = file.readBytes()
        raw[4] = 99
        file.writeBytes(raw)

        val e = runCatching { store.read() }.exceptionOrNull()
        assertTrue(e is IdentityUnavailableException)
        assertTrue("message should name the version: ${e!!.message}", e.message!!.contains("version"))
    }

    @Test
    fun `a ciphertext sealed without the AAD is rejected`() {
        // Proves the AAD is actually bound in. Without it, a blob sealed elsewhere under the same
        // key could be substituted here.
        val store = newStore()
        store.write(sampleRecord())
        val raw = file.readBytes()
        val iv = raw.copyOfRange(5, 17)

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                keyProvider.getOrCreate(),
                javax.crypto.spec.GCMParameterSpec(128, iv),
            )
            // deliberately no updateAAD
        }
        val forged = cipher.doFinal(ByteArray(66) { 0x33 })

        val out = ByteArray(17 + forged.size)
        raw.copyInto(out, 0, 0, 17)
        forged.copyInto(out, 17)
        file.writeBytes(out)

        val e = runCatching { store.read() }.exceptionOrNull()
        assertTrue("AAD must be required, got $e", e is IdentityUnavailableException)
    }

    // ── Key loss ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a missing sealing key is reported as recoverable`() {
        val store = newStore()
        store.write(sampleRecord())
        keyProvider.delete()

        val e = runCatching { store.read() }.exceptionOrNull()
        assertTrue("expected IdentityUnavailableException, got $e", e is IdentityUnavailableException)
        assertTrue(
            "the message must tell the user it can be recreated: ${e!!.message}",
            e.message!!.contains("recreated"),
        )
    }

    @Test
    fun `a rotated sealing key fails authentication rather than silently changing identity`() {
        // This is the device-credential-reset case: the alias still exists but holds a new key.
        val store = newStore()
        store.write(sampleRecord())
        keyProvider.rotate()

        val e = runCatching { store.read() }.exceptionOrNull()
        assertTrue("expected a failure, got $e", e is IdentityUnavailableException)
        assertTrue(
            "the message should say it cannot be trusted: ${e!!.message}",
            e.message!!.contains("authentication") || e.message!!.contains("trusted"),
        )
    }

    // ── Destroy ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `destroy removes the file and the key`() {
        val store = newStore()
        store.write(sampleRecord())
        assertTrue(file.exists())

        store.destroy()
        assertFalse(file.exists())
        assertNull("the sealing key must be gone too", keyProvider.existing())
        assertNull(store.read())
    }

    @Test
    fun `destroyed ciphertext is unreadable even if the file comes back`() {
        val store = newStore()
        store.write(sampleRecord())
        val ciphertext = file.readBytes()

        store.destroy()
        file.writeBytes(ciphertext)

        val e = runCatching { store.read() }.exceptionOrNull()
        assertTrue(
            "after destroy the ciphertext must be useless, got $e",
            e is IdentityUnavailableException,
        )
    }

    @Test
    fun `destroy is safe when nothing exists`() {
        newStore().destroy()
    }

    // ── Integration with the identity manager ──────────────────────────────────────────────

    @Test
    fun `identity survives a simulated process restart`() {
        val provider = InMemorySealingKeyProvider()
        val path = File(temp.root, "restart.bin")

        val created = runBlocking {
            DefaultIdentityManager(SealedFileSeedStore(path, provider)).ensureIdentity()
        }
        // New store and new manager over the same file and key: a cold start.
        val reloaded = runBlocking {
            DefaultIdentityManager(SealedFileSeedStore(path, provider)).ensureIdentity()
        }

        assertEquals("deviceId must survive a restart", created.deviceId, reloaded.deviceId)
        assertArrayEquals(created.staticPublicKey, reloaded.staticPublicKey)
        assertEquals(created.createdAtEpochMillis, reloaded.createdAtEpochMillis)
    }

    @Test
    fun `reset through the manager persists the new identity`() {
        val provider = InMemorySealingKeyProvider()
        val path = File(temp.root, "reset.bin")
        val manager = DefaultIdentityManager(SealedFileSeedStore(path, provider))

        val before = runBlocking { manager.ensureIdentity() }
        val after = runBlocking { manager.resetIdentity() }
        assertFalse(before.deviceId == after.deviceId)

        val reloaded = runBlocking {
            DefaultIdentityManager(SealedFileSeedStore(path, provider)).ensureIdentity()
        }
        assertEquals("the reset identity must be what persists", after.deviceId, reloaded.deviceId)
    }

    @Test
    fun `a key lost between runs surfaces as IdentityUnavailable, not a new identity`() {
        // The failure the user actually experiences: phone PIN changed, Keystore key invalidated.
        // It must not silently look like a brand-new device.
        val provider = InMemorySealingKeyProvider()
        val path = File(temp.root, "lost.bin")
        runBlocking { DefaultIdentityManager(SealedFileSeedStore(path, provider)).ensureIdentity() }

        provider.delete()

        val e = runCatching {
            runBlocking { DefaultIdentityManager(SealedFileSeedStore(path, provider)).ensureIdentity() }
        }.exceptionOrNull()
        assertTrue("expected IdentityUnavailableException, got $e", e is IdentityUnavailableException)
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..(size - needle.size)) {
            for (i in needle.indices) {
                if (this[start + i] != needle[i]) continue@outer
            }
            return true
        }
        return false
    }
}
