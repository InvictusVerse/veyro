package com.veyro.core.link

import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Extended device status, PC lock and wallpaper, round-tripped against
 * `protocol/testvectors/link-status-wallpaper.json` (04-PROTOCOL.md 6.2.1, ADR-038). Shared with
 * the Windows suite so the two cannot drift.
 */
class StatusWallpaperVectorTest {

    private val json = LinkJson.codec

    private fun vectors(): List<Pair<String, String>> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var file: File? = null
        while (dir != null && file == null) {
            file = File(dir, "protocol/testvectors/link-status-wallpaper.json").takeIf { it.isFile }
            dir = dir.parentFile
        }
        checkNotNull(file) { "protocol/testvectors/link-status-wallpaper.json not found above ${System.getProperty("user.dir")}" }
        val root = json.parseToJsonElement(file.readText()).jsonObject
        return root.getValue("vectors").jsonArray.map {
            it.jsonObject.getValue("type").jsonPrimitive.content to it.jsonObject.getValue("json").jsonPrimitive.content
        }
    }

    private val expectedTypes = mapOf(
        "devStatus" to LinkMessage.DeviceStatus::class,
        "pcLock" to LinkMessage.PcLock::class,
        "wallpaper" to LinkMessage.Wallpaper::class,
    )

    @Test
    fun `every vector decodes to its type and re-encodes identically`() {
        val all = vectors()
        assertEquals(5, all.size)

        for ((type, text) in all) {
            val decoded = json.decodeFromString<LinkMessage>(text)
            val expected = expectedTypes.getValue(type)
            assertTrue("vector '$type' decoded to ${decoded::class.simpleName}", expected.isInstance(decoded))

            val reEncoded = json.encodeToString<LinkMessage>(decoded)
            assertEquals(
                "vector '$type' did not round-trip",
                json.parseToJsonElement(text) as JsonObject,
                json.parseToJsonElement(reEncoded) as JsonObject,
            )
        }
    }

    @Test
    fun `vectors carry the expected values`() {
        val decoded = vectors().map { (_, text) -> json.decodeFromString<LinkMessage>(text) }

        assertEquals(
            LinkMessage.DeviceStatus(
                battery = 57,
                charging = false,
                ringer = "normal",
                dnd = false,
                wifi = 3,
                cell = 2,
                carrier = "Carrier A",
                bluetooth = true,
                mobileData = false,
            ),
            decoded[0],
        )
        val noRadio = decoded[1] as LinkMessage.DeviceStatus
        assertEquals(-1, noRadio.wifi)
        assertEquals(-1, noRadio.cell)
        assertNull("absent fields stay absent", noRadio.battery)
        assertSame(LinkMessage.PcLock, decoded[2])
        assertEquals("/9j/4AAQSkZJRg==", (decoded[3] as LinkMessage.Wallpaper).jpeg)
        assertNull("no wallpaper omits jpeg", (decoded[4] as LinkMessage.Wallpaper).jpeg)
    }

    @Test
    fun `an old devStatus without radio fields still decodes`() {
        val old = json.decodeFromString<LinkMessage>("""{"t":"devStatus","battery":80,"charging":true}""")
        assertEquals(LinkMessage.DeviceStatus(battery = 80, charging = true), old)
    }

    @Test
    fun `the Android build advertises WALLPAPER at 0x4000`() {
        assertEquals(0x4000, Capability.WALLPAPER.bit)
        assertTrue(Capability.WALLPAPER in Capabilities.ANDROID_CURRENT)
    }
}
