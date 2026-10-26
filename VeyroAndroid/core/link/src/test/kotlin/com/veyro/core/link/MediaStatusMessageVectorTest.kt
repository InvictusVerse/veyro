package com.veyro.core.link

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Companion media and device-status messages, round-tripped against
 * `protocol/testvectors/link-media-status.json` (04-PROTOCOL.md section 6.2, ADR-026).
 *
 * Read from the shared file so the Android and Windows suites cannot drift apart. Each entry must
 * decode to the listed type and re-encode to semantically identical JSON.
 */
class MediaStatusMessageVectorTest {

    private val json = LinkJson.codec

    private fun vectors(): List<Pair<String, String>> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var file: File? = null
        while (dir != null && file == null) {
            file = File(dir, "protocol/testvectors/link-media-status.json").takeIf { it.isFile }
            dir = dir.parentFile
        }
        checkNotNull(file) { "protocol/testvectors/link-media-status.json not found above ${System.getProperty("user.dir")}" }
        val root = json.parseToJsonElement(file.readText()).jsonObject
        return root.getValue("vectors").jsonArray.map {
            it.jsonObject.getValue("type").jsonPrimitive.content to it.jsonObject.getValue("json").jsonPrimitive.content
        }
    }

    private val expectedTypes = mapOf(
        "mediaState" to LinkMessage.MediaState::class,
        "mediaCmd" to LinkMessage.MediaCommand::class,
        "devStatus" to LinkMessage.DeviceStatus::class,
        "devControl" to LinkMessage.DeviceControl::class,
    )

    @Test
    fun `every vector decodes to its type and re-encodes identically`() {
        val all = vectors()
        assertEquals(6, all.size)

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

        val state = decoded[0] as LinkMessage.MediaState
        val session = state.sessions.single()
        assertEquals("s1", session.id)
        assertTrue(session.playing && session.canSeek)
        assertEquals(61000L, session.positionMs)
        assertEquals(1793011200000L, session.ts)
        assertNull(session.art)
        assertEquals(40, state.volume)

        val empty = decoded[1] as LinkMessage.MediaState
        assertTrue(empty.sessions.isEmpty())
        assertNull(empty.volume)

        assertEquals(LinkMessage.MediaCommand(LinkMessage.MediaCommand.SEEK, "s1", 90000L), decoded[2])
        assertEquals(LinkMessage.MediaCommand(LinkMessage.MediaCommand.VOLUME, null, 25L), decoded[3])
        assertEquals(LinkMessage.DeviceStatus(battery = 82, charging = true, ringer = "vibrate", dnd = false), decoded[4])
        assertEquals(LinkMessage.DeviceControl(ring = true), decoded[5])
    }
}
