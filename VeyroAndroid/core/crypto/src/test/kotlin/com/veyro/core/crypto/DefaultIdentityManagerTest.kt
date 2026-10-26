package com.veyro.core.crypto

import com.veyro.core.model.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Identity lifecycle: generate once, persist, verify on load, reset deliberately.
 *
 * These run on the plain JVM, with no Robolectric and no emulator, because [SeedStore] is an
 * interface. The Android-specific part - sealing with a hardware-backed Keystore key - is a thin
 * adapter with no logic in it, so the behaviour worth testing is all reachable from here.
 */
class DefaultIdentityManagerTest {

    private fun manager(
        store: SeedStore,
        clock: () -> Long = { 1_700_000_000_000L },
    ): DefaultIdentityManager = DefaultIdentityManager(store, clock, SecureRandom())

    /** Stands in for the Keystore key having been invalidated: the ciphertext is unreadable. */
    private class BrokenSeedStore : SeedStore {
        override fun read(): IdentityRecord? = throw IllegalStateException("sealing key is gone")
        override fun write(record: IdentityRecord) = Unit
        override fun destroy() = Unit
    }

    /** Hands back a seed that does not match the recorded deviceId - a swapped or corrupt file. */
    private class MismatchedSeedStore(private val seed: ByteArray, private val claimed: DeviceId) :
        SeedStore {
        override fun read(): IdentityRecord = IdentityRecord(seed, claimed, 0L)
        override fun write(record: IdentityRecord) = Unit
        override fun destroy() = Unit
    }

    // ── First run ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `generates an identity on first use`() = runTest {
        val store = InMemorySeedStore()
        val identity = manager(store).ensureIdentity()

        assertEquals(DeviceId.LENGTH, identity.deviceId.value.length)
        assertEquals(32, identity.staticPublicKey.size)
        assertNotNull("the record must have been persisted", store.read())
    }

    @Test
    fun `generated identity commits to its own key`() = runTest {
        val identity = manager(InMemorySeedStore()).ensureIdentity()
        assertTrue(
            "deviceId must be the commitment to the public key, or spoofing becomes possible",
            DeviceId.matches(identity.deviceId, identity.staticPublicKey),
        )
    }

    @Test
    fun `createdAt is recorded and does not drift on later launches`() = runTest {
        val store = InMemorySeedStore()
        val created = 1_600_000_000_000L
        assertEquals(created, manager(store, clock = { created }).ensureIdentity().createdAtEpochMillis)

        // Same store, much later clock: this is a restart. createdAt must come from the record,
        // not from "now", or the "paired since" date walks forward every launch.
        val later = manager(store, clock = { created + 86_400_000L }).ensureIdentity()
        assertEquals(created, later.createdAtEpochMillis)
    }

    // ── Stability (REQ-I04) ────────────────────────────────────────────────────────────────

    @Test
    fun `identity is stable across manager instances`() = runTest {
        val store = InMemorySeedStore()
        val first = manager(store).ensureIdentity()
        val second = manager(store).ensureIdentity()

        assertEquals("deviceId must be stable across restarts", first.deviceId, second.deviceId)
        assertArrayEquals(first.staticPublicKey, second.staticPublicKey)
    }

    @Test
    fun `ensureIdentity is idempotent within one instance`() = runTest {
        val m = manager(InMemorySeedStore())
        assertEquals(m.ensureIdentity(), m.ensureIdentity())
    }

    @Test
    fun `concurrent first calls produce exactly one identity`() = runTest {
        // Dispatchers.Default, deliberately: `runTest` runs on a single-threaded scheduler, so
        // launching 32 coroutines inside it would serialise them and prove nothing about
        // concurrency. This is the one test where real threads are the point.
        val m = manager(InMemorySeedStore())
        val ids = withContext(Dispatchers.Default) {
            (1..32)
                .map { async { m.ensureIdentity().deviceId } }
                .awaitAll()
                .toSet()
        }
        assertEquals("concurrent callers must not mint several identities", 1, ids.size)
    }

    @Test
    fun `separate installs get different identities`() = runTest {
        val a = manager(InMemorySeedStore()).ensureIdentity()
        val b = manager(InMemorySeedStore()).ensureIdentity()
        assertFalse("independent installs must not collide", a.deviceId == b.deviceId)
    }

    // ── currentIdentity must never generate ────────────────────────────────────────────────

    @Test
    fun `currentIdentity returns null before any identity exists, and writes nothing`() = runTest {
        val store = InMemorySeedStore()
        assertNull(manager(store).currentIdentity())
        assertNull("a read must not create an identity as a side effect", store.read())
    }

    @Test
    fun `currentIdentity returns the identity once loaded`() = runTest {
        val m = manager(InMemorySeedStore())
        val created = m.ensureIdentity()
        assertEquals(created, m.currentIdentity())
    }

    @Test
    fun `currentIdentity on a fresh instance is null until ensureIdentity is called`() = runTest {
        // Documented behaviour: a fresh instance has loaded nothing, even if a store has a record.
        // Callers that need the identity must call ensureIdentity; this keeps the "am I loaded"
        // question answerable without forcing disk I/O.
        val store = InMemorySeedStore()
        manager(store).ensureIdentity()
        assertNull(manager(store).currentIdentity())
        assertNotNull(manager(store).ensureIdentity())
    }

    // ── Key agreement ──────────────────────────────────────────────────────────────────────

    @Test
    fun `two identities agree on the same shared secret`() = runTest {
        val storeA = InMemorySeedStore()
        val storeB = InMemorySeedStore()
        val a = manager(storeA).ensureIdentity()
        val b = manager(storeB).ensureIdentity()

        // Fresh managers over the same stores: the realistic path where a process restarts and
        // then runs a handshake.
        assertArrayEquals(
            manager(storeA).agree(b.staticPublicKey),
            manager(storeB).agree(a.staticPublicKey),
        )
    }

    @Test
    fun `agree rejects a peer key of the wrong size`() = runTest {
        val m = manager(InMemorySeedStore())
        m.ensureIdentity()
        var threw = false
        try {
            m.agree(ByteArray(31))
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `agree rejects a low-order peer key`() = runTest {
        val m = manager(InMemorySeedStore())
        m.ensureIdentity()
        var message: String? = null
        try {
            m.agree(ByteArray(32))
        } catch (e: IllegalArgumentException) {
            message = e.message
        }
        assertNotNull("a low-order point must be refused", message)
    }

    @Test
    fun `agree works after loading from the store`() = runTest {
        val store = InMemorySeedStore()
        val original = manager(store).ensureIdentity()
        val restarted = manager(store)
        restarted.ensureIdentity()

        val peer = manager(InMemorySeedStore()).ensureIdentity()
        assertEquals(32, restarted.agree(peer.staticPublicKey).size)
        assertEquals(original.deviceId, restarted.currentIdentity()!!.deviceId)
    }

    // ── Corruption and failure ─────────────────────────────────────────────────────────────

    @Test
    fun `a seed that does not match its recorded deviceId is refused`() = runTest {
        val real = manager(InMemorySeedStore()).ensureIdentity()
        // Right file, wrong seed - exactly what a swap or a partial overwrite looks like.
        val store = MismatchedSeedStore(X25519.generatePrivateKey(), real.deviceId)

        var raised: IdentityUnavailableException? = null
        try {
            manager(store).ensureIdentity()
        } catch (e: IdentityUnavailableException) {
            raised = e
        }
        assertNotNull("a mismatched identity must fail loudly, not silently change device", raised)
        assertTrue(
            "the message must tell the user a reset is needed: ${raised!!.message}",
            raised.message!!.contains("reset", ignoreCase = true),
        )
    }

    @Test
    fun `an unreadable store surfaces as IdentityUnavailableException`() = runTest {
        var raised: IdentityUnavailableException? = null
        try {
            manager(BrokenSeedStore()).ensureIdentity()
        } catch (e: IdentityUnavailableException) {
            raised = e
        }
        assertNotNull("a lost sealing key must be reported, not crashed on", raised)
    }

    @Test
    fun `a failed load leaves nothing cached`() = runTest {
        val m = manager(BrokenSeedStore())
        runCatching { m.ensureIdentity() }
        assertNull(m.currentIdentity())
    }

    // ── Reset (REQ-I10) ────────────────────────────────────────────────────────────────────

    @Test
    fun `reset produces a new key pair and persists it`() = runTest {
        val store = InMemorySeedStore()
        val m = manager(store)
        val before = m.ensureIdentity()
        val after = m.resetIdentity()

        assertFalse("a reset must produce a genuinely new key pair", before.deviceId == after.deviceId)
        assertFalse(before.staticPublicKey.contentEquals(after.staticPublicKey))
        assertTrue(DeviceId.matches(after.deviceId, after.staticPublicKey))
        assertEquals("the new identity must be what is persisted", after.deviceId, store.read()!!.deviceId)
    }

    @Test
    fun `reset is durable across a restart`() = runTest {
        val store = InMemorySeedStore()
        val before = manager(store).ensureIdentity()
        val after = manager(store).resetIdentity()

        val reloaded = manager(store).ensureIdentity()
        assertEquals(after.deviceId, reloaded.deviceId)
        assertFalse(before.deviceId == reloaded.deviceId)
    }

    @Test
    fun `a reset invalidates the previous shared secret`() = runTest {
        // The security property behind REQ-I10: a peer that only knows the old public key can no
        // longer arrive at the same secret, so it cannot continue as the old identity.
        val store = InMemorySeedStore()
        val peerStore = InMemorySeedStore()
        val m = manager(store)
        val oldPublicKey = m.ensureIdentity().staticPublicKey
        val peer = manager(peerStore).ensureIdentity()

        val secretWithOld = manager(store).agree(peer.staticPublicKey)
        val newIdentity = m.resetIdentity()
        val secretWithNew = m.agree(peer.staticPublicKey)

        assertFalse(
            "a reset must invalidate the old shared secret",
            secretWithOld.contentEquals(secretWithNew),
        )
        assertFalse(
            "a peer holding only the old public key must no longer match",
            oldPublicKey.contentEquals(newIdentity.staticPublicKey),
        )
    }

    // ── Hygiene ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `identity toString does not contain key material`() = runTest {
        val text = manager(InMemorySeedStore()).ensureIdentity().toString()
        assertTrue("toString must not leak anything: $text", !text.contains("seed"))
    }

    @Test
    fun `identity record toString redacts the seed`() {
        val seed = ByteArray(32) { 0x41 }
        val record = IdentityRecord(seed, DeviceId.fromPublicKey(X25519.publicKeyOf(seed)), 0L)
        val text = record.toString()
        assertTrue("expected a redaction marker: $text", text.contains("<redacted>"))
        assertTrue("must not print seed bytes: $text", !text.contains("41 41"))
    }

    @Test
    fun `identity record rejects a wrong-size seed`() {
        var threw = false
        try {
            IdentityRecord(ByteArray(31), DeviceId.fromPublicKey(ByteArray(32)), 0L)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `in-memory store clears the seed on destroy`() {
        val seed = ByteArray(32) { 0x7F }
        val store = InMemorySeedStore(IdentityRecord(seed, DeviceId.fromPublicKey(ByteArray(32)), 0L))
        store.destroy()
        assertNull(store.read())
        assertTrue("destroy should wipe the buffer it held", seed.none { it.toInt() != 0 })
    }
}
