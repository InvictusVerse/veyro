package com.veyro.core.link

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Companion notification messages, round-tripped against `protocol/testvectors/link-notification.json`
 * (04-PROTOCOL.md section 6.1, ADR-025).
 *
 * Read from the shared file rather than copied in, so the Android and Windows suites cannot drift
 * apart. Each entry must decode to the listed type and re-encode to semantically identical JSON
 * (same keys and values; member order is not part of the contract).
 */
class NotificationMessageVectorTest {

    private val json = PeerMessageJson.codec

    private fun vectorFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "protocol/testvectors/link-notification.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("protocol/testvectors/link-notification.json not found above ${System.getProperty("user.dir")}")
    }

    private val expectedTypes = mapOf(
        "notif" to PeerMessage.Notification::class,
        "notifAction" to PeerMessage.NotificationActionRequest::class,
        "notifReply" to PeerMessage.NotificationReplyRequest::class,
        "notifDismiss" to PeerMessage.NotificationDismissRequest::class,
    )

    @Test
    fun `every vector decodes to its type and re-encodes identically`() {
        val root = json.parseToJsonElement(vectorFile().readText()).jsonObject
        val vectors = root.getValue("vectors").jsonArray
        assertEquals(5, vectors.size)

        for (vector in vectors) {
            val type = vector.jsonObject.getValue("type").jsonPrimitive.content
            val text = vector.jsonObject.getValue("json").jsonPrimitive.content

            val decoded = json.decodeFromString<PeerMessage>(text)
            val expected = expectedTypes.getValue(type)
            assertTrue("vector '$type' decoded to ${decoded::class.simpleName}", expected.isInstance(decoded))

            val reEncoded = json.encodeToString<PeerMessage>(decoded)
            assertEquals(
                "vector '$type' did not round-trip",
                json.parseToJsonElement(text) as JsonObject,
                json.parseToJsonElement(reEncoded) as JsonObject,
            )
        }
    }

    @Test
    fun `posted vector carries the expected fields`() {
        val root = json.parseToJsonElement(vectorFile().readText()).jsonObject
        val first = root.getValue("vectors").jsonArray.first().jsonObject.getValue("json").jsonPrimitive.content
        val n = json.decodeFromString<PeerMessage>(first) as PeerMessage.Notification

        assertEquals(PeerMessage.Notification.KIND_POSTED, n.kind)
        assertEquals(listOf(PeerMessage.NotificationActionInfo(1, "Mark as read")), n.actions)
        assertEquals(listOf(PeerMessage.NotificationLine("Sam", "Lunch?")), n.messages)
        assertEquals(true, n.canReply)
        assertEquals(1793011200000L, n.ts)
    }
}
