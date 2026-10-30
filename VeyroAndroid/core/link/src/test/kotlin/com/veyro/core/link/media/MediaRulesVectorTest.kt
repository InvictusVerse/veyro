package com.veyro.core.link.media

import com.veyro.core.link.PeerMessage
import com.veyro.core.link.PeerMessageJson
import com.veyro.core.link.StreamAskAudio
import com.veyro.core.link.StreamCaps
import com.veyro.core.link.StreamConfig
import com.veyro.core.link.StreamKeyframe
import com.veyro.core.link.StreamRules
import com.veyro.core.link.StreamStart
import com.veyro.core.link.StreamState
import com.veyro.core.link.StreamStop
import com.veyro.core.protocol.media.AccessUnitFragmenter
import com.veyro.core.protocol.media.StreamGeometry
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stream control messages (`link-stream.json`), fragmentation (`media/fragmentation.json`) and
 * the coded size rule (`media/stream-geometry.json`), shared with the Windows suite.
 */
class MediaRulesVectorTest {

    private val json = PeerMessageJson.codec

    @Test
    fun `every stream message decodes to its type and re-encodes identically`() {
        val types = mapOf(
            "streamCaps" to StreamCaps::class, "streamStart" to StreamStart::class, "streamState" to StreamState::class,
            "streamConfig" to StreamConfig::class, "streamKeyframe" to StreamKeyframe::class, "streamStop" to StreamStop::class,
            "streamAskAudio" to StreamAskAudio::class,
        )
        val vectors = MediaVectors.load("link-stream.json").getValue("vectors").jsonArray.map { it.jsonObject }
        assertEquals(13, vectors.size)
        for (vector in vectors) {
            val type = vector.getValue("type").jsonPrimitive.content
            val text = vector.getValue("json").jsonPrimitive.content
            val decoded = json.decodeFromString<PeerMessage>(text)
            assertTrue("$type decoded to ${decoded::class.simpleName}", types.getValue(type).isInstance(decoded))
            assertEquals("$type did not round-trip", json.parseToJsonElement(text), json.parseToJsonElement(json.encodeToString<PeerMessage>(decoded)))
        }
    }

    @Test
    fun `the vectors the phone acts on pass its rules`() {
        val vectors = MediaVectors.load("link-stream.json").getValue("vectors").jsonArray
            .map { json.decodeFromString<PeerMessage>(it.jsonObject.getValue("json").jsonPrimitive.content) }
        for (m in vectors) {
            when (m) {
                is StreamStart -> assertTrue(StreamRules.isValid(m))
                is StreamConfig -> assertTrue(StreamRules.isValid(m))
                is StreamKeyframe -> assertTrue(StreamRules.isValid(m))
                is StreamStop -> assertTrue(StreamRules.isValid(m))
                is StreamAskAudio -> assertTrue(StreamRules.isValid(m))
                is StreamState -> assertTrue(m.audioPermission == null || m.audioPermission in StreamRules.AUDIO_PERMISSIONS)
                else -> Unit
            }
        }
    }

    @Test
    fun `the phone drops what breaks a field rule`() {
        val invalid = MediaVectors.load("link-stream.json").getValue("invalid").jsonArray.map { it.jsonObject }
            .filter { it.getValue("receiver").jsonPrimitive.content == "phone" }
        assertTrue(invalid.size >= 10)
        for (entry in invalid) {
            val why = entry.getValue("why").jsonPrimitive.content
            val valid = when (val m = json.decodeFromString<PeerMessage>(entry.getValue("json").jsonPrimitive.content)) {
                is StreamStart -> StreamRules.isValid(m)
                is StreamConfig -> StreamRules.isValid(m)
                is StreamKeyframe -> StreamRules.isValid(m)
                is StreamStop -> StreamRules.isValid(m)
                is StreamAskAudio -> StreamRules.isValid(m)
                else -> true
            }
            assertFalse("accepted: $why", valid)
        }
    }

    @Test
    fun `private IPv4 only`() {
        for (ok in listOf("10.0.0.1", "172.16.0.9", "172.31.255.255", "192.168.31.191", "169.254.1.2")) assertTrue(ok, StreamRules.isPrivateIpv4(ok))
        for (bad in listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "10.0.0", "10.0.0.256", "010.0.0.1", "::1", "", "a.b.c.d")) assertFalse(bad, StreamRules.isPrivateIpv4(bad))
    }

    @Test
    fun `fragmentation matches`() {
        val root = MediaVectors.load("media/fragmentation.json")
        val max = root.getValue("maxFragment").jsonPrimitive.int
        for (case in root.getValue("cases").jsonArray.map { it.jsonObject }) {
            val plan = AccessUnitFragmenter.plan(
                case.getValue("size").jsonPrimitive.int,
                case.getValue("key").jsonPrimitive.boolean,
                case.getValue("discontinuity").jsonPrimitive.boolean,
                max,
            )
            val expected = case.getValue("fragments").jsonArray.map { f ->
                val a = f.jsonArray.map { it.jsonPrimitive.int }
                AccessUnitFragmenter.Fragment(a[0], a[1], a[2])
            }
            assertEquals(expected, plan)
        }
    }

    @Test
    fun `stream geometry matches`() {
        val cases = MediaVectors.load("media/stream-geometry.json").getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 9)
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val screen = case.getValue("screen").jsonArray.map { it.jsonPrimitive.int }
            val encoder = case.getValue("encoder").jsonArray.map { it.jsonPrimitive.int }
            val fit = if (case.getValue("fit").jsonPrimitive.content == "fit") StreamGeometry.Fit.FIT else StreamGeometry.Fit.STRETCH
            val result = StreamGeometry.compute(screen[0], screen[1], case.getValue("maxLong").jsonPrimitive.int, encoder[0], encoder[1], fit)
            val expected = case.getValue("expected")
            if (expected is JsonNull) {
                assertNull(name, result)
            } else {
                val e = expected.jsonArray.map { it.jsonPrimitive.int }
                assertNotNull(name, result)
                assertEquals(name, StreamGeometry.Result(e[0], e[1], e[2], e[3], e[4], e[5]), result)
            }
        }
    }
}
