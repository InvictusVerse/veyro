package com.veyro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NetworkSecurityConfigTest {

    @Test
    fun `network security config permits cleartext traffic for local p2p connections`() {
        val candidates = listOf(
            File("src/main/res/xml/network_security_config.xml"),
            File("app/src/main/res/xml/network_security_config.xml"),
        )
        val configFile = candidates.firstOrNull { it.exists() }
        assertNotNull("network_security_config.xml must exist", configFile)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(configFile!!)
        val root = doc.documentElement
        assertEquals("network-security-config", root.nodeName)

        val baseConfigNodes = root.getElementsByTagName("base-config")
        assertTrue("base-config must be defined", baseConfigNodes.length > 0)
        val baseConfig = baseConfigNodes.item(0) as Element
        assertEquals(
            "Cleartext traffic must be permitted for local WebSockets",
            "true",
            baseConfig.getAttribute("cleartextTrafficPermitted"),
        )

        val debugOverrides = root.getElementsByTagName("debug-overrides")
        assertTrue("debug-overrides must be defined", debugOverrides.length > 0)
    }

    @Test
    fun `android manifest declares networkSecurityConfig and usesCleartextTraffic`() {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        val manifestFile = candidates.firstOrNull { it.exists() }
        assertNotNull("AndroidManifest.xml must exist", manifestFile)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile!!)
        val applicationNodes = doc.getElementsByTagName("application")
        assertTrue("application tag must exist", applicationNodes.length > 0)
        val app = applicationNodes.item(0) as Element

        assertEquals(
            "@xml/network_security_config",
            app.getAttribute("android:networkSecurityConfig"),
        )
        assertEquals(
            "true",
            app.getAttribute("android:usesCleartextTraffic"),
        )
    }
}
