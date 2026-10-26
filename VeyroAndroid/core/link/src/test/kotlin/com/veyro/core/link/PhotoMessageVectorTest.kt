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
 * Companion photo messages, round-tripped against `protocol/testvectors/link-photos.json`
 * (04-PROTOCOL.md section 6.4, ADR-034). Shared with the Windows suite so the two cannot drift.
 */
class PhotoMessageVectorTest {

    private val json = LinkJson.codec

    private fun vectors(): List<Pair<String, String>> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var file: File? = null
        while (dir != null && file == null) {
            file = File(dir, "protocol/testvectors/link-photos.json").takeIf { it.isFile }
            dir = dir.parentFile
        }
        checkNotNull(file) { "protocol/testvectors/link-photos.json not found above ${System.getProperty("user.dir")}" }
        val root = json.parseToJsonElement(file.readText()).jsonObject
        return root.getValue("vectors").jsonArray.map {
            it.jsonObject.getValue("type").jsonPrimitive.content to it.jsonObject.getValue("json").jsonPrimitive.content
        }
    }

    private val expectedTypes = mapOf(
        "photosReq" to LinkMessage.PhotosRequest::class,
        "photos" to LinkMessage.Photos::class,
        "photoThumbReq" to LinkMessage.PhotoThumbRequest::class,
        "photoThumb" to LinkMessage.PhotoThumb::class,
        "photoFetch" to LinkMessage.PhotoFetch::class,
        "photoNew" to LinkMessage.PhotoNew::class,
        "photoRemoved" to LinkMessage.PhotoRemoved::class,
    )

    @Test
    fun `every vector decodes to its type and re-encodes identically`() {
        val all = vectors()
        assertEquals(8, all.size)

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

        assertEquals(LinkMessage.PhotosRequest(limit = 2000), decoded[0])
        val photo = (decoded[1] as LinkMessage.Photos).items.single()
        assertEquals(4021L, photo.id)
        assertEquals(1793009700000L, photo.ts)
        assertEquals(4000, photo.width)
        assertEquals(3000, photo.height)
        assertEquals(3145728L, photo.size)
        assertEquals("image/jpeg", photo.mime)
        assertEquals("camera", photo.bucket)
        assertEquals(LinkMessage.PhotoThumbRequest(ids = listOf(4021L, 4020L), size = 256), decoded[2])
        assertEquals("/9j/4AAQSkZJRg==", (decoded[3] as LinkMessage.PhotoThumb).jpeg)
        assertNull("an unreadable thumbnail omits jpeg", (decoded[4] as LinkMessage.PhotoThumb).jpeg)
        assertEquals(LinkMessage.PhotoFetch(id = 4021L, requestId = "r-1"), decoded[5])
        val new = (decoded[6] as LinkMessage.PhotoNew).item
        assertEquals("screenshots", new.bucket)
        assertNull(new.width)
        assertEquals(LinkMessage.PhotoRemoved(id = 4019L), decoded[7])
    }

    @Test
    fun `offer requestId round-trips and is omitted when absent`() {
        val plain = LinkMessage.TransferOffer("t-1", emptyList(), 0L, "Phone")
        val plainJson = json.encodeToString<LinkMessage>(plain)
        assertTrue("requestId must be omitted, not null", "requestId" !in plainJson)

        val answering = plain.copy(requestId = "r-1")
        assertEquals(answering, json.decodeFromString<LinkMessage>(json.encodeToString<LinkMessage>(answering)))
    }
}
