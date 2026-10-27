package com.veyro.core.link

import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Clipboard items, round-tripped against `protocol/testvectors/link-clipboard.json`
 * (04-PROTOCOL.md 6.5, ADR-043). Shared with the Windows suite so the two cannot drift.
 */
class ClipboardVectorTest {

    private val json = LinkJson.codec

    private fun vectors(): List<String> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var file: File? = null
        while (dir != null && file == null) {
            file = File(dir, "protocol/testvectors/link-clipboard.json").takeIf { it.isFile }
            dir = dir.parentFile
        }
        checkNotNull(file) { "protocol/testvectors/link-clipboard.json not found above ${System.getProperty("user.dir")}" }
        val root = json.parseToJsonElement(file.readText()).jsonObject
        return root.getValue("vectors").jsonArray.map { it.jsonObject.getValue("json").jsonPrimitive.content }
    }

    @Test
    fun `every vector decodes to Clip and re-encodes identically`() {
        val all = vectors()
        assertEquals(3, all.size)
        for (text in all) {
            val decoded = json.decodeFromString<LinkMessage>(text)
            assertTrue(decoded is LinkMessage.Clip)
            val reEncoded = json.encodeToString<LinkMessage>(decoded)
            assertEquals(json.parseToJsonElement(text) as JsonObject, json.parseToJsonElement(reEncoded) as JsonObject)
        }
    }

    @Test
    fun `vectors carry the expected values`() {
        val clips = vectors().map { json.decodeFromString<LinkMessage>(it) as LinkMessage.Clip }
        assertEquals(
            LinkMessage.Clip("c-1", LinkMessage.Clip.KIND_TEXT, text = "https://youtu.be/dQw4w9WgXcQ", apply = true, ts = 1793011200000),
            clips[0],
        )
        assertEquals(false, clips[1].apply)
        assertEquals(LinkMessage.Clip.KIND_IMAGE, clips[2].kind)
        assertEquals("iVBORw0KGgo=", clips[2].image)
        assertEquals(null, clips[2].text)
    }

    @Test
    fun `the Android build advertises CLIPBOARD at 0x0008`() {
        assertEquals(0x0008, Capability.CLIPBOARD.bit)
        assertTrue(Capability.CLIPBOARD in Capabilities.ANDROID_CURRENT)
    }
}
