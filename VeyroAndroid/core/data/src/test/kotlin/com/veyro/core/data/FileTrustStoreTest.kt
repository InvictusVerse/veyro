package com.veyro.core.data

import com.veyro.core.crypto.X25519
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Platform
import com.veyro.core.model.TrustedPeer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * Trust store: persistence, authentication, and the fail-closed corruption policy.
 *
 * Plain JVM with a real filesystem and real HMAC. The HMAC key is a fixed test value; in the app it
 * is derived from the identity seed, which is what makes an identity reset invalidate the file.
 */
class FileTrustStoreTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val macKey = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = temp.newFolder("data")
    }

    private fun store(key: ByteArray = macKey) =
        FileTrustStore(dir, key, TestAppDispatchers())

    private fun peer(seedByte: Int, name: String = "PC", autoAccept: Boolean = false): TrustedPeer {
        val seed = ByteArray(32) { (it + seedByte).toByte() }
        val pub = X25519.publicKeyOf(seed)
        return TrustedPeer(
            deviceId = DeviceId.fromPublicKey(pub),
            staticPublicKey = pub,
            pairingSecret = ByteArray(32) { (it + seedByte + 7).toByte() },
            name = name,
            platform = Platform.WINDOWS,
            pairedAtEpochMillis = 1_700_000_000_000L,
            autoAcceptTransfers = autoAccept,
        )
    }

    // ── Round trip ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `empty store reads as empty`() = runBlocking {
        assertTrue(store().snapshot().isEmpty())
    }

    @Test
    fun `a peer survives a reload`() = runBlocking {
        val p = peer(1)
        store().put(p)

        val reloaded = store().snapshot()
        assertEquals(1, reloaded.size)
        assertEquals(p.deviceId, reloaded[0].deviceId)
        assertTrue(p.staticPublicKey.contentEquals(reloaded[0].staticPublicKey))
        assertTrue(p.pairingSecret.contentEquals(reloaded[0].pairingSecret))
        assertEquals(p.name, reloaded[0].name)
        assertEquals(p.platform, reloaded[0].platform)
        assertEquals(p.pairedAtEpochMillis, reloaded[0].pairedAtEpochMillis)
    }

    @Test
    fun `several peers round trip`() = runBlocking {
        val s = store()
        s.put(peer(1, "PC"))
        s.put(peer(40, "Laptop"))
        s.put(peer(80, "Tablet"))
        assertEquals(3, store().snapshot().size)
    }

    @Test
    fun `putting the same deviceId replaces rather than duplicates`() = runBlocking {
        val s = store()
        s.put(peer(1, "Old name"))
        s.put(peer(1, "New name"))

        val all = store().snapshot()
        assertEquals("a repeated deviceId must not create a second record", 1, all.size)
        assertEquals("New name", all[0].name)
    }

    @Test
    fun `find locates by deviceId`() = runBlocking {
        val p = peer(5)
        val s = store()
        s.put(p)
        s.put(peer(60))
        assertNotNull(s.find(p.deviceId))
        assertNull(s.find(peer(99).deviceId))
    }

    @Test
    fun `remove deletes and persists`() = runBlocking {
        val p = peer(1)
        val s = store()
        s.put(p)
        s.put(peer(40))
        s.remove(p.deviceId)

        assertEquals(1, store().snapshot().size)
        assertNull(store().find(p.deviceId))
    }

    @Test
    fun `remove of an unknown id is a no-op`() = runBlocking {
        val s = store()
        s.put(peer(1))
        s.remove(peer(99).deviceId)
        assertEquals(1, s.snapshot().size)
    }

    @Test
    fun `auto accept persists`() = runBlocking {
        val p = peer(1)
        val s = store()
        s.put(p)
        assertFalse("auto-accept must default to off (REQ-T03)", s.snapshot()[0].autoAcceptTransfers)

        s.setAutoAccept(p.deviceId, true)
        assertTrue(store().snapshot()[0].autoAcceptTransfers)

        s.setAutoAccept(p.deviceId, false)
        assertFalse(store().snapshot()[0].autoAcceptTransfers)
    }

    @Test
    fun `clear empties the store`() = runBlocking {
        val s = store()
        s.put(peer(1))
        s.clear()
        assertTrue(s.snapshot().isEmpty())
        assertTrue(store().snapshot().isEmpty())
    }

    // ── Authentication ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a different mac key makes the file fail closed`() = runBlocking {
        store().put(peer(1))

        // This is exactly what happens after an identity reset: the derived key changes.
        val withWrongKey = store(otherKey).snapshot()
        assertTrue(
            "an unauthenticated trust file must be treated as empty, never partially trusted",
            withWrongKey.isEmpty(),
        )
    }

    @Test
    fun `a tampered file is treated as empty`() = runBlocking {
        store().put(peer(1))

        val f = File(dir, "trust.cbor")
        val raw = f.readBytes()
        raw[raw.size - 5] = (raw[raw.size - 5].toInt() xor 0xFF).toByte()
        f.writeBytes(raw)

        assertTrue(
            "failing closed is the policy: silently adding a trusted peer must be impossible",
            store().snapshot().isEmpty(),
        )
    }

    @Test
    fun `appending bytes to the file is detected`() = runBlocking {
        store().put(peer(1))
        val f = File(dir, "trust.cbor")
        f.appendBytes(byteArrayOf(0, 0, 0))
        assertTrue(store().snapshot().isEmpty())
    }

    @Test
    fun `a truncated file is detected`() = runBlocking {
        store().put(peer(1))
        val f = File(dir, "trust.cbor")
        val raw = f.readBytes()
        f.writeBytes(raw.copyOf(raw.size - 4))
        assertTrue(store().snapshot().isEmpty())
    }

    @Test
    fun `a garbage file is detected`() = runBlocking {
        File(dir, "trust.cbor").writeBytes(ByteArray(120) { 0x5A })
        assertTrue(store().snapshot().isEmpty())
    }

    @Test
    fun `the load error is reported, not swallowed`() = runBlocking {
        store().put(peer(1))
        val f = File(dir, "trust.cbor")
        val raw = f.readBytes()
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        f.writeBytes(raw)

        val s = store()
        s.snapshot()
        // The user must be told to re-pair; an error only in logcat is an error they cannot act on.
        val message = s.lastLoadError.first()
        assertNotNull("a corrupt trust store must surface a message", message)
        assertTrue(
            "the message should tell the user what to do: $message",
            message!!.contains("paired", ignoreCase = true),
        )
    }

    @Test
    fun `the mac covers the whole payload`() = runBlocking {
        // Walk every byte of the payload region and confirm each modification is caught. An HMAC
        // that only covers part of the file is not an HMAC.
        store().put(peer(1))
        val f = File(dir, "trust.cbor")
        val original = f.readBytes()
        val headerEnd = 4 + 1 + 1 + 32

        for (i in headerEnd until original.size) {
            val raw = original.copyOf()
            raw[i] = (raw[i].toInt() xor 0x80).toByte()
            f.writeBytes(raw)
            assertTrue("modification at byte $i was not detected", store().snapshot().isEmpty())
        }
    }

    // ── Validation ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a peer whose id does not match its key is refused`() = runBlocking {
        val good = peer(1)
        val mismatched = good.copy(staticPublicKey = X25519.publicKeyOf(ByteArray(32) { 9 }))

        var threw = false
        try {
            store().put(mismatched)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(
            "storing a peer whose deviceId is not the commitment to its key must be refused",
            threw,
        )
    }

    @Test
    fun `no temporary file is left behind`() = runBlocking {
        store().put(peer(1))
        assertFalse(File(dir, "trust.cbor.tmp").exists())
    }

    @Test
    fun `revoked peers are kept but marked`() = runBlocking {
        val p = peer(1).copy(revoked = true)
        store().put(p)
        val loaded = store().snapshot()
        assertEquals(1, loaded.size)
        assertTrue("revocation must persist, not delete the record", loaded[0].revoked)
    }

    @Test
    fun `the flow emits the current list`() = runBlocking {
        val s = store()
        s.put(peer(1))
        s.put(peer(40))
        assertEquals(2, s.peers.first().size)
    }

    // ── Removed while away (ADR-057) ───────────────────────────────────────────────────────

    private var now = 1_800_000_000_000L

    private fun timedStore() = FileTrustStore(dir, macKey, TestAppDispatchers(), clock = { now })

    @Test
    fun `remove and notify drops the peer but keeps its secret across a reload`() = runBlocking {
        val p = peer(1)
        timedStore().put(p)
        timedStore().removeAndNotify(p.deviceId)

        val reloaded = timedStore()
        assertNull("the device is no longer trusted", reloaded.find(p.deviceId))
        assertTrue(p.pairingSecret.contentEquals(reloaded.pendingUnpairSecret(p.deviceId)))
    }

    /**
     * Review issue 12 (ADR-058): `ConnectionManager.tellRemovedPeer` zeroes the array it is given,
     * so the store must hand out a fresh copy each time, never its own.
     */
    @Test
    fun `the kept secret is handed out as a fresh copy the caller may wipe`() = runBlocking {
        val p = peer(1)
        val s = timedStore()
        s.put(p)
        s.removeAndNotify(p.deviceId)

        s.pendingUnpairSecret(p.deviceId)!!.fill(0)
        assertTrue("wiping one copy leaves the kept secret intact", p.pairingSecret.contentEquals(s.pendingUnpairSecret(p.deviceId)))
    }

    @Test
    fun `told or paired again, the kept secret goes`() = runBlocking {
        val told = peer(1)
        val paired = peer(40)
        val s = timedStore()
        s.put(told)
        s.put(paired)
        s.removeAndNotify(told.deviceId)
        s.removeAndNotify(paired.deviceId)

        s.clearPendingUnpair(told.deviceId)
        s.put(paired.copy(pairingSecret = ByteArray(32) { 3 }))

        val reloaded = timedStore()
        assertNull(reloaded.pendingUnpairSecret(told.deviceId))
        assertNull("a new pairing replaces the old secret", reloaded.pendingUnpairSecret(paired.deviceId))
        assertFalse("nothing is owed, so no file is left", File(dir, "removed.cbor").exists())
    }

    @Test
    fun `a kept secret expires`() = runBlocking {
        val p = peer(1)
        timedStore().put(p)
        timedStore().removeAndNotify(p.deviceId)

        now += FileTrustStore.PENDING_UNPAIR_KEEP_MS + 1
        assertNull(timedStore().pendingUnpairSecret(p.deviceId))
    }

    @Test
    fun `a tampered removal list is ignored and trust is untouched`() = runBlocking {
        val kept = peer(40)
        val s = timedStore()
        s.put(peer(1))
        s.put(kept)
        s.removeAndNotify(peer(1).deviceId)

        val f = File(dir, "removed.cbor")
        val raw = f.readBytes()
        raw[raw.size - 3] = (raw[raw.size - 3].toInt() xor 0x40).toByte()
        f.writeBytes(raw)

        val reloaded = timedStore()
        assertNull(reloaded.pendingUnpairSecret(peer(1).deviceId))
        assertEquals(listOf(kept.deviceId), reloaded.snapshot().map { it.deviceId })
    }

    @Test
    fun `remove alone keeps nothing`() = runBlocking {
        val p = peer(1)
        val s = timedStore()
        s.put(p)
        s.remove(p.deviceId)
        assertNull(s.pendingUnpairSecret(p.deviceId))
    }

    // ── ADR-059: the device's own name and a local nickname ──────────────────────────────

    @Test
    fun `a nickname survives a reload and wins for display`() = runBlocking {
        val p = peer(1, name = "DESKTOP-7Q2")
        store().put(p)
        store().setNickname(p.deviceId, "Study PC")

        val reloaded = store().find(p.deviceId)!!
        assertEquals("DESKTOP-7Q2", reloaded.name)
        assertEquals("Study PC", reloaded.nickname)
        assertEquals("Study PC", reloaded.displayName)
    }

    @Test
    fun `an announced name replaces the stored one and keeps the nickname`() = runBlocking {
        val p = peer(1, name = "Old name")
        val s = store()
        s.put(p)
        s.setNickname(p.deviceId, "Mine")

        assertTrue(s.setAnnouncedName(p.deviceId, "New name"))
        assertFalse("the same name again changes nothing", s.setAnnouncedName(p.deviceId, "New name"))
        assertFalse("an unknown device is not created", s.setAnnouncedName(peer(2).deviceId, "Stranger"))

        val reloaded = store().find(p.deviceId)!!
        assertEquals("New name", reloaded.name)
        assertEquals("Mine", reloaded.nickname)
        assertEquals(1, store().snapshot().size)
    }

    @Test
    fun `clearing the nickname goes back to the device's own name`() = runBlocking {
        val p = peer(1, name = "Pixel")
        val s = store()
        s.put(p)
        s.setNickname(p.deviceId, "Work phone")
        s.setNickname(p.deviceId, null)

        val reloaded = store().find(p.deviceId)!!
        assertNull(reloaded.nickname)
        assertEquals("Pixel", reloaded.displayName)
    }

    @Test
    fun `a nickname change keeps auto-accept and the key`() = runBlocking {
        val p = peer(1, autoAccept = true)
        val s = store()
        s.put(p)
        s.setNickname(p.deviceId, "Laptop")
        s.setAnnouncedName(p.deviceId, "Laptop 2")
        val reloaded = store().find(p.deviceId)!!
        assertTrue(reloaded.autoAcceptTransfers)
        assertTrue(p.pairingSecret.contentEquals(reloaded.pairingSecret))
    }}
