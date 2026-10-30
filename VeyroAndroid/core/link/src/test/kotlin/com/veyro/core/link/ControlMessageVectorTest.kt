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

    private val json = PeerMessageJson.codec

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
        forbidKeys(encoded, "pair", "pk", "cn", "epk")

        val v3 = V3Vectors.load("link/connection-schedule-v3.json").arr("vectors")[0].let { it as JsonObject }.obj("input")
        val decoded = json.decodeFromString<LinkPreamble>(v3.str("preamble"))
        assertEquals("2C9VV72DFSF1A2SC0TN6QBAPEC", decoded.id)
        assertEquals("android", decoded.platform)
        assertEquals(3, decoded.protocolVersion)
        assertEquals(3, LINK_PROTOCOL_VERSION)
        assertEquals(32, FrameCrypto.decodeKey32(decoded.cn)?.size)
        assertEquals(32, FrameCrypto.decodeKey32(decoded.epk)?.size)
        requireKeys(json.encodeToString(decoded), "id", "name", "platform", "protocolVersion", "cn", "epk")
        val ack = json.decodeFromString<LinkPreambleAck>(v3.str("ack"))
        assertEquals(LinkPreambleAck.MODE_SESSION, ack.mode)
        requireKeys(json.encodeToString(ack), "mode", "cn", "epk")
    }

    @Test
    fun `vector - confirm pairing preamble, ack and nonce`() {
        val pkA = "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo="
        val confirm = V3Vectors.load("link/pairing-schedule-v3.json").obj("confirm").obj("input")
        val preamble = json.decodeFromString<LinkPreamble>(confirm.str("preamble"))
        assertEquals(LinkPreamble.PAIR_CONFIRM, preamble.pair)
        assertEquals(pkA, preamble.pk)
        requireKeys(json.encodeToString(preamble), "id", "name", "platform", "protocolVersion", "pair", "pk", "cn", "epk")

        val ack = json.decodeFromString<LinkPreambleAck>(confirm.str("ack"))
        assertEquals(LinkPreambleAck.MODE_PAIRING_CONFIRM, ack.mode)
        assertEquals("SDrgTP6a5PIY+i7qEvbocaY0lV825gmpRytWpVLLpMM=", ack.commit)
        val reEncoded = json.encodeToString(ack)
        requireKeys(reEncoded, "mode", "pk", "commit", "cn", "epk")
        forbidKeys(reEncoded, "reason")

        val nonce = json.decodeFromString<LinkPairNonce>(
            """{"t":"pairNonce","n":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="}""",
        )
        assertEquals(LinkPairNonce.TYPE, nonce.t)
        requireKeys(json.encodeToString(LinkPairNonce(n = nonce.n)), "t", "n")
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
    fun `vector - the five modes`() {
        assertEquals("session", LinkPreambleAck.MODE_SESSION)
        assertEquals("pairing", LinkPreambleAck.MODE_PAIRING)
        assertEquals("pairing-confirm", LinkPreambleAck.MODE_PAIRING_CONFIRM)
        assertEquals("busy", LinkPreambleAck.MODE_BUSY)
        assertEquals("refused", LinkPreambleAck.MODE_REFUSED)
    }

    // ── Wire frame ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `vector - v3 frame carries exactly f and c`() {
        val frame = LinkWireFrame(from = deviceId, ciphertext = "Y2lwaGVydGV4dA==")
        val encoded = json.encodeToString(frame)
        requireKeys(encoded, "f", "c")
        forbidKeys(encoded, "n", "p", "m")
        assertEquals(frame, json.decodeFromString<LinkWireFrame>(encoded))
    }

    @Test
    fun `vector - pairHello carries qrProof only for QR pairing`() {
        val qr = PeerMessage.PairHello(deviceId, "n", "android", "1", publicKeyB64 = "a", qrProof = "b")
        assertTrue("qrProof" in keysOf(json.encodeToString<PeerMessage>(qr)))
        assertFalse("qrProof" in keysOf(json.encodeToString<PeerMessage>(qr.copy(qrProof = null))))
    }

    // ── Envelope ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `vector - envelope wrapping a ping`() {
        val text = """{"v":3,"msgId":"m-1","from":"$deviceId","seq":7,"ts":1760000000000,""" +
            """"payload":{"t":"ping","nonce":"abc"}}"""

        val env = json.decodeFromString<LinkEnvelope>(text)
        assertEquals(3, env.v)
        assertEquals(7L, env.seq)
        assertEquals(1760000000000L, env.ts)

        val payload = env.payload
        assertTrue(payload is PeerMessage.Ping)
        assertEquals("abc", (payload as PeerMessage.Ping).nonce)

        requireKeys(json.encodeToString(env), "v", "msgId", "from", "seq", "ts", "payload")
    }

    // ── Message discriminators ─────────────────────────────────────────────────────────────

    private fun discriminatorOf(message: PeerMessage): String {
        val obj = json.parseToJsonElement(json.encodeToString(message)) as JsonObject
        return obj["t"].toString().trim('"')
    }

    @Test
    fun `vector - every discriminator value`() {
        assertEquals("ping", discriminatorOf(PeerMessage.Ping("a")))
        assertEquals("pong", discriminatorOf(PeerMessage.Pong("a")))
        assertEquals("hello", discriminatorOf(PeerMessage.Hello(deviceId, "n", "windows")))
        assertEquals("helloAck", discriminatorOf(PeerMessage.HelloAck(deviceId, "n", "windows")))
        assertEquals("pairHello", discriminatorOf(PeerMessage.PairHello(deviceId, "n", "windows", "1.0.0")))
        assertEquals("pairOk", discriminatorOf(PeerMessage.PairOk(deviceId, "n", "windows", "c2s=")))
        assertEquals("pairFail", discriminatorOf(PeerMessage.PairFail("no")))
        assertEquals("offer", discriminatorOf(PeerMessage.TransferOffer("t", emptyList(), 0, "n")))
        assertEquals("decision", discriminatorOf(PeerMessage.TransferDecision("t", true)))
        assertEquals("progressNotice", discriminatorOf(PeerMessage.TransferProgressNotice("t", 0, 0)))
        assertEquals("itemBegin", discriminatorOf(PeerMessage.ItemBegin("t", 0, 0, 0, 0)))
        assertEquals("itemEnd", discriminatorOf(PeerMessage.ItemEnd("t", 0, "h")))
        assertEquals("itemResult", discriminatorOf(PeerMessage.ItemResult("t", 0, true)))
        assertEquals("transferEnd", discriminatorOf(PeerMessage.TransferEnd("t", true)))
        assertEquals("chunk", discriminatorOf(PeerMessage.Chunk("t", 0, 0, "aGk=")))
        assertEquals("cancel", discriminatorOf(PeerMessage.Cancel("t", "why")))
    }

    @Test
    fun `vector - hello emits its defaults`() {
        val encoded = json.encodeToString<PeerMessage>(
            PeerMessage.Hello(deviceId, "Test PC", "windows"),
        )
        requireKeys(encoded, "t", "deviceId", "deviceName", "platform", "capabilities", "maxChunk")
    }

    @Test
    fun `vector - offer omits a null wifi address and preserves route order`() {
        val offer = PeerMessage.TransferOffer(
            transferId = "tx-1",
            items = listOf(PeerMessage.Item(0, "photo.jpg", 1024, 1760000000000L)),
            totalBytes = 1024,
            senderName = "Test PC",
            preferredRoutes = listOf("LAN_TCP", "WIFI_DIRECT"),
        )
        val encoded = json.encodeToString<PeerMessage>(offer)
        requireKeys(encoded, "t", "transferId", "items", "totalBytes", "senderName", "preferredRoutes")
        forbidKeys(encoded, "senderWifiP2pAddress")

        val decoded = json.decodeFromString<PeerMessage>(encoded) as PeerMessage.TransferOffer
        assertEquals(listOf("LAN_TCP", "WIFI_DIRECT"), decoded.preferredRoutes)
    }

    @Test
    fun `vector - decision omits every null optional`() {
        val decision = PeerMessage.TransferDecision(
            transferId = "tx-1",
            accept = true,
            route = "LAN_TCP",
            tcpHost = "192.168.1.50",
            tcpPort = 47651,
            authToken = "0123456789abcdef",
        )
        val encoded = json.encodeToString<PeerMessage>(decision)
        requireKeys(encoded, "t", "transferId", "accept", "route", "tcpHost", "tcpPort", "authToken")
        forbidKeys(encoded, "reason", "resume", "fallbackHost", "p2pSsid", "p2pPassphrase")
    }

    @Test
    fun `vector - decision carrying a resume map`() {
        val text = """{"t":"decision","transferId":"tx-1","accept":true,"resume":{"0":[3,4,5]},"route":"LAN_TCP"}"""
        val decoded = json.decodeFromString<PeerMessage>(text) as PeerMessage.TransferDecision
        assertEquals(listOf(3, 4, 5), decoded.resume?.get(0))
    }

    @Test
    fun `vector - itemResult omits a null error when ok`() {
        val encoded = json.encodeToString<PeerMessage>(PeerMessage.ItemResult("tx-1", 0, true))
        forbidKeys(encoded, "error")
    }

    // ── Forward compatibility ──────────────────────────────────────────────────────────────

    @Test
    fun `forward compatibility - an unknown key inside a known message is ignored`() {
        val decoded = json.decodeFromString<PeerMessage>(
            """{"t":"ping","nonce":"abc","somethingFromTheFuture":42}""",
        )
        assertEquals("abc", (decoded as PeerMessage.Ping).nonce)
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
            json.decodeFromString<PeerMessage>("""{"t":"somethingNew","x":1}""")
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
