package com.veyro.core.link

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Control-plane JSON, checked against `protocol/testvectors/link/control-messages.json`.
 *
 * These vectors deliberately do **not** pin member order: JSON objects are unordered, and the
 * frame MAC is computed over the bytes as received rather than over a re-serialization, so order
 * is not a correctness requirement. What is pinned is the exact key set, the discriminator
 * values, that defaults are emitted, and that nulls are omitted rather than written as `null`.
 *
 * The Windows suite loads the same JSON and asserts the same properties.
 */
class ControlMessageVectorTest {

    private val json = LinkJson.codec

    private fun keysOf(text: String): Set<String> =
        (json.parseToJsonElement(text) as JsonObject).keys

    private fun requireKeys(text: String, vararg keys: String) {
        val actual = keysOf(text)
        keys.forEach { assertTrue("expected key '$it' in $text", it in actual) }
    }

    private fun forbidKeys(text: String, vararg keys: String) {
        val actual = keysOf(text)
        keys.forEach { assertFalse("key '$it' must be omitted in $text", it in actual) }
    }

    private val deviceId = "6JE8415YZQQYQMBD34569HVYX4"

    // ── Handshake ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `vector - preamble emits defaults`() {
        val encoded = json.encodeToString(LinkPreamble(id = deviceId, name = "Test Phone"))
        // encodeDefaults = true, so platform and protocolVersion appear even at their defaults.
        requireKeys(encoded, "id", "name", "platform", "protocolVersion")

        val decoded = json.decodeFromString<LinkPreamble>(
            """{"id":"$deviceId","name":"Test Phone","platform":"android","protocolVersion":1}""",
        )
        assertEquals(deviceId, decoded.id)
        assertEquals("android", decoded.platform)
        assertEquals(1, decoded.protocolVersion)
    }

    @Test
    fun `vector - preamble ack omits a null reason`() {
        val accept = json.encodeToString(LinkPreambleAck(mode = LinkPreambleAck.MODE_SESSION))
        requireKeys(accept, "mode")
        forbidKeys(accept, "reason")

        val refused = json.encodeToString(
            LinkPreambleAck(mode = LinkPreambleAck.MODE_REFUSED, reason = "not paired"),
        )
        requireKeys(refused, "mode", "reason")
    }

    @Test
    fun `vector - the four modes`() {
        assertEquals("session", LinkPreambleAck.MODE_SESSION)
        assertEquals("pairing", LinkPreambleAck.MODE_PAIRING)
        assertEquals("busy", LinkPreambleAck.MODE_BUSY)
        assertEquals("refused", LinkPreambleAck.MODE_REFUSED)
    }

    // ── Wire frame ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `vector - session frame carries c and not p`() {
        val frame = LinkWireFrame(
            from = deviceId,
            nonce = "AAECAwQFBgcICQoL",
            ciphertext = "Y2lwaGVydGV4dA==",
            mac = "bWFj",
        )
        val encoded = json.encodeToString(frame)
        requireKeys(encoded, "f", "n", "c", "m")
        forbidKeys(encoded, "p")

        assertEquals(frame, json.decodeFromString<LinkWireFrame>(encoded))
    }

    @Test
    fun `vector - pairing frame carries p and not c`() {
        val frame = LinkWireFrame(
            from = deviceId,
            nonce = "AAECAwQFBgcICQoL",
            plaintext = "cGxhaW50ZXh0",
            mac = "bWFj",
        )
        val encoded = json.encodeToString(frame)
        requireKeys(encoded, "f", "n", "p", "m")
        forbidKeys(encoded, "c")
    }

    // ── Envelope ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `vector - envelope wrapping a ping`() {
        val text = """{"v":1,"msgId":"m-1","from":"$deviceId","seq":7,"ts":1760000000000,""" +
            """"payload":{"t":"ping","nonce":"abc"}}"""

        val env = json.decodeFromString<LinkEnvelope>(text)
        assertEquals(1, env.v)
        assertEquals(7L, env.seq)
        assertEquals(1760000000000L, env.ts)

        val payload = env.payload
        assertTrue(payload is LinkMessage.Ping)
        assertEquals("abc", (payload as LinkMessage.Ping).nonce)

        requireKeys(json.encodeToString(env), "v", "msgId", "from", "seq", "ts", "payload")
    }

    // ── Message discriminators ─────────────────────────────────────────────────────────────

    private fun discriminatorOf(message: LinkMessage): String {
        val obj = json.parseToJsonElement(json.encodeToString(message)) as JsonObject
        return obj["t"].toString().trim('"')
    }

    @Test
    fun `vector - every discriminator value`() {
        assertEquals("ping", discriminatorOf(LinkMessage.Ping("a")))
        assertEquals("pong", discriminatorOf(LinkMessage.Pong("a")))
        assertEquals("hello", discriminatorOf(LinkMessage.Hello(deviceId, "n", "windows")))
        assertEquals("helloAck", discriminatorOf(LinkMessage.HelloAck(deviceId, "n", "windows")))
        assertEquals("pairHello", discriminatorOf(LinkMessage.PairHello(deviceId, "n", "windows", "1.0.0")))
        assertEquals("pairOk", discriminatorOf(LinkMessage.PairOk(deviceId, "n", "windows", "c2s=")))
        assertEquals("pairFail", discriminatorOf(LinkMessage.PairFail("no")))
        assertEquals("offer", discriminatorOf(LinkMessage.TransferOffer("t", emptyList(), 0, "n")))
        assertEquals("decision", discriminatorOf(LinkMessage.TransferDecision("t", true)))
        assertEquals("progressNotice", discriminatorOf(LinkMessage.TransferProgressNotice("t", 0, 0)))
        assertEquals("itemBegin", discriminatorOf(LinkMessage.ItemBegin("t", 0, 0, 0, 0)))
        assertEquals("itemEnd", discriminatorOf(LinkMessage.ItemEnd("t", 0, "h")))
        assertEquals("itemResult", discriminatorOf(LinkMessage.ItemResult("t", 0, true)))
        assertEquals("transferEnd", discriminatorOf(LinkMessage.TransferEnd("t", true)))
        assertEquals("chunk", discriminatorOf(LinkMessage.Chunk("t", 0, 0, "aGk=")))
        assertEquals("cancel", discriminatorOf(LinkMessage.Cancel("t", "why")))
    }

    @Test
    fun `vector - hello emits its defaults`() {
        val encoded = json.encodeToString<LinkMessage>(
            LinkMessage.Hello(deviceId, "Test PC", "windows"),
        )
        requireKeys(encoded, "t", "deviceId", "deviceName", "platform", "capabilities", "maxChunk")
    }

    @Test
    fun `vector - offer omits a null wifi address and preserves route order`() {
        val offer = LinkMessage.TransferOffer(
            transferId = "tx-1",
            items = listOf(LinkMessage.Item(0, "photo.jpg", 1024, 1760000000000L)),
            totalBytes = 1024,
            senderName = "Test PC",
            preferredRoutes = listOf("LAN_TCP", "WIFI_DIRECT"),
        )
        val encoded = json.encodeToString<LinkMessage>(offer)
        requireKeys(encoded, "t", "transferId", "items", "totalBytes", "senderName", "preferredRoutes")
        forbidKeys(encoded, "senderWifiP2pAddress")

        val decoded = json.decodeFromString<LinkMessage>(encoded) as LinkMessage.TransferOffer
        assertEquals(listOf("LAN_TCP", "WIFI_DIRECT"), decoded.preferredRoutes)
    }

    @Test
    fun `vector - decision omits every null optional`() {
        val decision = LinkMessage.TransferDecision(
            transferId = "tx-1",
            accept = true,
            route = "LAN_TCP",
            tcpHost = "192.168.1.50",
            tcpPort = 47651,
            authToken = "0123456789abcdef",
        )
        val encoded = json.encodeToString<LinkMessage>(decision)
        requireKeys(encoded, "t", "transferId", "accept", "route", "tcpHost", "tcpPort", "authToken")
        forbidKeys(encoded, "reason", "resume", "fallbackHost", "p2pSsid", "p2pPassphrase")
    }

    @Test
    fun `vector - decision carrying a resume map`() {
        val text = """{"t":"decision","transferId":"tx-1","accept":true,"resume":{"0":[3,4,5]},"route":"LAN_TCP"}"""
        val decoded = json.decodeFromString<LinkMessage>(text) as LinkMessage.TransferDecision
        assertEquals(listOf(3, 4, 5), decoded.resume?.get(0))
    }

    @Test
    fun `vector - itemResult omits a null error when ok`() {
        val encoded = json.encodeToString<LinkMessage>(LinkMessage.ItemResult("tx-1", 0, true))
        forbidKeys(encoded, "error")
    }

    // ── Forward compatibility ──────────────────────────────────────────────────────────────

    @Test
    fun `forward compatibility - an unknown key inside a known message is ignored`() {
        val decoded = json.decodeFromString<LinkMessage>(
            """{"t":"ping","nonce":"abc","somethingFromTheFuture":42}""",
        )
        assertEquals("abc", (decoded as LinkMessage.Ping).nonce)
    }

    @Test
    fun `forward compatibility - an unknown key inside the envelope is ignored`() {
        val env = json.decodeFromString<LinkEnvelope>(
            """{"v":1,"msgId":"m-1","from":"$deviceId","seq":0,"ts":1,"payload":{"t":"ping","nonce":"a"},"futureField":"x"}""",
        )
        assertEquals("m-1", env.msgId)
    }

    @Test
    fun `forward compatibility - an unknown discriminator is refused, not guessed`() {
        // Unknown FIELDS are forward compatibility; an unknown MESSAGE TYPE is not something a v1
        // peer can act on, and guessing would be worse than refusing.
        val failed = runCatching {
            json.decodeFromString<LinkMessage>("""{"t":"somethingNew","x":1}""")
        }.isFailure
        assertTrue("an unknown discriminator must not decode", failed)
    }

    @Test
    fun `null policy - an explicit null is tolerated on decode even though it is not emitted`() {
        // explicitNulls = false means we never WRITE a null, but a lenient decode keeps us
        // interoperable with a peer that does.
        val decoded = json.decodeFromString<LinkPreambleAck>("""{"mode":"session","reason":null}""")
        assertNull(decoded.reason)
    }
}
