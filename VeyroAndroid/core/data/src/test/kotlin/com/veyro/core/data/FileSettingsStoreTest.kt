package com.veyro.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Settings persistence, defaults, and the UTF-8 name clamp. */
class FileSettingsStoreTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private lateinit var dir: File
    private val defaultName = "Pixel 9"

    @Before
    fun setUp() {
        dir = temp.newFolder("data")
    }

    private fun store() = FileSettingsStore(dir, defaultName)

    @Test
    fun `defaults apply when nothing is stored`() = runBlocking {
        val s = store()
        assertEquals(defaultName, s.deviceName.first())
        assertFalse("always-discoverable must default to off (NFR-03)", s.alwaysDiscoverable.first())
        assertNull(s.destinationDir.first())
    }

    @Test
    fun `device name persists`() = runBlocking {
        store().setDeviceName("Study PC")
        assertEquals("Study PC", store().deviceName.first())
    }

    @Test
    fun `discoverability persists`() = runBlocking {
        store().setAlwaysDiscoverable(true)
        assertTrue(store().alwaysDiscoverable.first())
        store().setAlwaysDiscoverable(false)
        assertFalse(store().alwaysDiscoverable.first())
    }

    @Test
    fun `destination persists and can be cleared`() = runBlocking {
        val target = File(dir, "Downloads")
        store().setDestinationDir(target)
        assertEquals(target.absolutePath, store().destinationDir.first()!!.absolutePath)

        store().setDestinationDir(null)
        assertNull(store().destinationDir.first())
    }

    @Test
    fun `blank name falls back to the default`() = runBlocking {
        store().setDeviceName("   ")
        assertEquals(defaultName, store().deviceName.first())
    }

    @Test
    fun `name is trimmed`() = runBlocking {
        store().setDeviceName("  Office  ")
        assertEquals("Office", store().deviceName.first())
    }

    @Test
    fun `an over-long ascii name is clamped to 32 bytes`() = runBlocking {
        // The name goes into an mDNS TXT record, which is size-limited. Clamping here means what
        // the user sees in Settings is what peers will actually see.
        store().setDeviceName("A".repeat(100))
        val stored = store().deviceName.first()
        assertEquals(32, stored.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `a multi-byte name is clamped without splitting a character`() = runBlocking {
        // A naive take(32) counts UTF-16 chars and would overflow; a naive byte cut would produce
        // invalid UTF-8. Neither is acceptable in a TXT record.
        store().setDeviceName("日本語のデバイス名前テストです本当に長い")
        val stored = store().deviceName.first()
        assertTrue("must fit the byte budget", stored.toByteArray(Charsets.UTF_8).size <= 32)
        // Round-tripping proves no character was cut in half.
        assertEquals(stored, String(stored.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `an emoji name is clamped without producing invalid utf8`() = runBlocking {
        store().setDeviceName("🙂".repeat(20))
        val stored = store().deviceName.first()
        assertTrue(stored.toByteArray(Charsets.UTF_8).size <= 32)
        assertEquals(stored, String(stored.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `a corrupt settings file falls back to defaults rather than failing`() = runBlocking {
        File(dir, "settings.json").writeText("{ this is not json")
        // Settings are not security-relevant, so defaults are a safe recovery - unlike the trust
        // store, where falling back to "empty" is a deliberate security decision.
        assertEquals(defaultName, store().deviceName.first())
    }

    @Test
    fun `an unknown field is ignored for forward compatibility`() = runBlocking {
        File(dir, "settings.json").writeText(
            """{"version":1,"deviceName":"Kept","somethingNew":true}""",
        )
        assertEquals("Kept", store().deviceName.first())
    }

    @Test
    fun `media sharing and device control default off, also in an older file, and persist`() = runBlocking {
        // A settings file written before ADR-026 has neither key.
        File(dir, "settings.json").writeText("""{"version":1,"notificationMirrorEnabled":true}""")
        assertFalse(store().mediaShareEnabled.first())
        assertFalse(store().deviceControlAllowed.first())
        assertTrue(store().notificationMirrorEnabled.first())

        store().setMediaShareEnabled(true)
        store().setDeviceControlAllowed(true)
        assertTrue(store().mediaShareEnabled.first())
        assertTrue(store().deviceControlAllowed.first())
    }

    @Test
    fun `messages and calls default off, also in an older file, and persist`() = runBlocking {
        // A settings file written before ADR-027 has no such key.
        File(dir, "settings.json").writeText("""{"version":1,"mediaShareEnabled":true}""")
        assertFalse(store().messagesAndCallsEnabled.first())
        assertTrue(store().mediaShareEnabled.first())

        store().setMessagesAndCallsEnabled(true)
        assertTrue(store().messagesAndCallsEnabled.first())
        store().setMessagesAndCallsEnabled(false)
        assertFalse(store().messagesAndCallsEnabled.first())
    }

    @Test
    fun `no temporary file is left behind`() = runBlocking {
        store().setDeviceName("X")
        assertFalse(File(dir, "settings.json.tmp").exists())
    }

    @Test
    fun `the file is human readable`() = runBlocking {
        // The reason for JSON here rather than CBOR: being able to read it while debugging.
        store().setDeviceName("Readable")
        val text = File(dir, "settings.json").readText()
        assertTrue(text.contains("Readable"))
        assertTrue(text.contains("deviceName"))
    }
}