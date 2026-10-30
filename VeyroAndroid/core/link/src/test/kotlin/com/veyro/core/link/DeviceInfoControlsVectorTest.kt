package com.veyro.core.link

import com.veyro.core.model.DeviceNames
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
 * `deviceInfo` (ADR-059) and the phone-control fields of `devStatus` / `devControl` (ADR-060),
 * round-tripped against `protocol/testvectors/link-device-info-controls.json`, plus the shared
 * name rule. The Windows suite drives the same file.
 */
class DeviceInfoControlsVectorTest {

    private val json = PeerMessageJson.codec

    private fun root(): JsonObject {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var file: File? = null
        while (dir != null && file == null) {
            file = File(dir, "protocol/testvectors/link-device-info-controls.json").takeIf { it.isFile }
            dir = dir.parentFile
        }
        checkNotNull(file) { "protocol/testvectors/link-device-info-controls.json not found above ${System.getProperty("user.dir")}" }
        return json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun vectors(): List<Pair<String, String>> = root().getValue("vectors").jsonArray.map {
        it.jsonObject.getValue("type").jsonPrimitive.content to it.jsonObject.getValue("json").jsonPrimitive.content
    }

    private val expectedTypes = mapOf(
        "deviceInfo" to PeerMessage.DeviceInfo::class,
        "devStatus" to PeerMessage.DeviceStatus::class,
        "devControl" to PeerMessage.DeviceControl::class,
    )

    @Test
    fun `every vector decodes to its type and re-encodes identically`() {
        val all = vectors()
        assertEquals(7, all.size)
        for ((type, text) in all) {
            val decoded = json.decodeFromString<PeerMessage>(text)
            assertTrue("vector '$type' decoded to ${decoded::class.simpleName}", expectedTypes.getValue(type).isInstance(decoded))
            val reEncoded = json.encodeToString<PeerMessage>(decoded)
            assertEquals("vector '$type' did not round-trip", json.parseToJsonElement(text), json.parseToJsonElement(reEncoded))
        }
    }

    @Test
    fun `vectors carry the expected values`() {
        val decoded = vectors().map { (_, text) -> json.decodeFromString<PeerMessage>(text) }
        assertEquals(PeerMessage.DeviceInfo(name = "Office PC"), decoded[0])
        assertEquals(PeerMessage.DeviceInfo(name = "Pixel 9", clipReceive = false), decoded[1])
        assertEquals(PeerMessage.DeviceInfo(clipReceive = true), decoded[2])
        assertEquals(
            PeerMessage.DeviceStatus(
                battery = 64,
                charging = false,
                torch = false,
                mediaVolume = 40,
                brightness = 75,
                autoBrightness = true,
                controls = PeerMessage.PhoneControlState(
                    torch = PeerMessage.PhoneControlState.OK,
                    volume = PeerMessage.PhoneControlState.OK,
                    brightness = PeerMessage.PhoneControlState.PERMISSION,
                ),
            ),
            decoded[3],
        )
        val off = decoded[4] as PeerMessage.DeviceStatus
        assertNull("no flashlight: no torch field", off.torch)
        assertEquals(PeerMessage.PhoneControlState.UNSUPPORTED, off.controls?.torch)
        assertEquals(PeerMessage.PhoneControlState.OFF, off.controls?.volume)
        assertEquals(PeerMessage.DeviceControl(torch = true), decoded[5])
        assertEquals(PeerMessage.DeviceControl(mediaVolume = 55, brightness = 30, autoBrightness = false), decoded[6])
    }

    @Test
    fun `an older devStatus and devControl still decode`() {
        assertEquals(
            PeerMessage.DeviceStatus(battery = 80, charging = true),
            json.decodeFromString<PeerMessage>("""{"t":"devStatus","battery":80,"charging":true}"""),
        )
        assertEquals(PeerMessage.DeviceControl(ring = true), json.decodeFromString<PeerMessage>("""{"t":"devControl","ring":true}"""))
    }

    @Test
    fun `the shared name rule matches the vectors`() {
        val names = root().getValue("names").jsonObject
        val accept = names.getValue("accept").jsonArray
        val reject = names.getValue("reject").jsonArray
        assertTrue(accept.size >= 5 && reject.size >= 8)
        for (pair in accept) {
            val (input, expected) = pair.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(expected, DeviceNames.clean(input))
        }
        for (input in reject) {
            assertNull("a rejected name of ${input.jsonPrimitive.content.length} chars was accepted", DeviceNames.clean(input.jsonPrimitive.content))
        }
    }
}
