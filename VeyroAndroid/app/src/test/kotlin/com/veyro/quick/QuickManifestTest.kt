package com.veyro.quick

import com.veyro.ui.keepalive.OemBackgroundGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** ADR-061, ADR-062: the manifest entries the code relies on. */
class QuickManifestTest {

    private val manifest: org.w3c.dom.Document by lazy {
        val file = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).firstOrNull { it.exists() }
        assertNotNull("AndroidManifest.xml must exist", file)
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file!!)
    }

    private fun elements(tag: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `local network access is declared`() {
        assertTrue(elements("uses-permission").any { it.getAttribute("android:name") == "android.permission.ACCESS_LOCAL_NETWORK" })
    }

    @Test
    fun `every maker package the checklist may open is visible through queries`() {
        val queried = elements("queries").flatMap { q ->
            val packages = q.getElementsByTagName("package")
            (0 until packages.length).map { (packages.item(it) as Element).getAttribute("android:name") }
        }.toSet()
        assertEquals(OemBackgroundGuide.allPackages, queried)
    }

    @Test
    fun `tiles can be bound only by the system, and the shortcut components stay private`() {
        val tiles = elements("service").filter { it.getAttribute("android:name").endsWith("TileService") }
        assertEquals(3, tiles.size)
        for (tile in tiles) {
            assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.getAttribute("android:permission"))
        }
        val clipboard = elements("activity").single { it.getAttribute("android:name") == ".quick.SendClipboardActivity" }
        assertEquals("false", clipboard.getAttribute("android:exported"))
        val receiver = elements("receiver").single { it.getAttribute("android:name") == ".quick.QuickActionReceiver" }
        assertEquals("false", receiver.getAttribute("android:exported"))
    }
}
