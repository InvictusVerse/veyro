package com.veyro.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WireMessagesTest {

    @Test
    fun `preamble and preamble-ack roundtrip cleanly`() {
        val preamble = LinkPreamble(id = "dev-1234", name = "My Pixel")
        val json = PeerMessageJson.codec.encodeToString(LinkPreamble.serializer(), preamble)
        val decoded = PeerMessageJson.codec.decodeFromString(LinkPreamble.serializer(), json)
        assertEquals(preamble, decoded)

        val ack = LinkPreambleAck(mode = LinkPreambleAck.MODE_SESSION, reason = null)
        val ackJson = PeerMessageJson.codec.encodeToString(LinkPreambleAck.serializer(), ack)
        val decodedAck = PeerMessageJson.codec.decodeFromString(LinkPreambleAck.serializer(), ackJson)
        assertEquals(ack, decodedAck)
    }

    @Test
    fun `pair hello and pair ok roundtrip inside envelope`() {
        val testPkB64 = Base64.getEncoder().encodeToString(ByteArray(32) { 42 })
        val hello = PeerMessage.PairHello(
            deviceId = "dev-initiator",
            deviceName = "Initiator Device",
            platform = "android",
            appVersion = "1.0",
            publicKeyB64 = testPkB64,
        )
        val envelope = LinkEnvelope(
            msgId = "msg-1",
            from = "dev-initiator",
            seq = 1L,
            ts = 1700000000L,
            payload = hello,
        )
        val json = PeerMessageJson.codec.encodeToString(LinkEnvelope.serializer(), envelope)
        val decoded = PeerMessageJson.codec.decodeFromString(LinkEnvelope.serializer(), json)
        assertEquals(envelope, decoded)
        assertTrue(decoded.payload is PeerMessage.PairHello)
        assertEquals(testPkB64, (decoded.payload as PeerMessage.PairHello).publicKeyB64)

        val pairOk = PeerMessage.PairOk(
            deviceId = "dev-target",
            deviceName = "Target Device",
            platform = "windows",
            sessionSecretB64 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
            publicKeyB64 = testPkB64,
        )
        val okEnvelope = envelope.copy(msgId = "msg-2", from = "dev-target", seq = 2L, payload = pairOk)
        val okJson = PeerMessageJson.codec.encodeToString(LinkEnvelope.serializer(), okEnvelope)
        val decodedOk = PeerMessageJson.codec.decodeFromString(LinkEnvelope.serializer(), okJson)
        assertEquals(okEnvelope, decodedOk)
        assertTrue(decodedOk.payload is PeerMessage.PairOk)
        assertEquals(testPkB64, (decodedOk.payload as PeerMessage.PairOk).publicKeyB64)
    }

    @Test
    fun `transfer offer and decision roundtrip`() {
        val items = listOf(
            PeerMessage.Item(index = 0, relPath = "photos/cat.jpg", size = 1048576L, mtime = 1700000000L, isDir = false),
            PeerMessage.Item(index = 1, relPath = "docs/report.pdf", size = 524288L, mtime = 1700000050L, isDir = false),
        )
        val offer = PeerMessage.TransferOffer(
            transferId = "tx-99",
            items = items,
            totalBytes = 1572864L,
            senderName = "Sender Device",
        )
        val env = LinkEnvelope(
            msgId = "m3",
            from = "sender",
            seq = 3L,
            ts = 1700000100L,
            payload = offer,
        )
        val json = PeerMessageJson.codec.encodeToString(LinkEnvelope.serializer(), env)
        val decoded = PeerMessageJson.codec.decodeFromString(LinkEnvelope.serializer(), json)
        assertEquals(env, decoded)

        val decision = PeerMessage.TransferDecision(
            transferId = "tx-99",
            accept = true,
            resume = mapOf(0 to listOf(1, 2)),
        )
        val decEnv = env.copy(msgId = "m4", payload = decision)
        val decJson = PeerMessageJson.codec.encodeToString(LinkEnvelope.serializer(), decEnv)
        val decodedDec = PeerMessageJson.codec.decodeFromString(LinkEnvelope.serializer(), decJson)
        assertEquals(decEnv, decodedDec)
    }

    @Test
    fun `chunk and item-result roundtrip`() {
        val rawData = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val chunk = PeerMessage.Chunk(
            transferId = "tx-99",
            index = 0,
            chunkIndex = 5,
            dataB64 = Base64.getEncoder().encodeToString(rawData),
        )
        val env = LinkEnvelope(
            msgId = "m5",
            from = "sender",
            seq = 5L,
            ts = 1700000200L,
            payload = chunk,
        )
        val json = PeerMessageJson.codec.encodeToString(LinkEnvelope.serializer(), env)
        val decoded = PeerMessageJson.codec.decodeFromString(LinkEnvelope.serializer(), json)
        assertEquals(env, decoded)
        val decodedChunk = decoded.payload as PeerMessage.Chunk
        assertEquals(chunk.dataB64, decodedChunk.dataB64)

        val itemRes = PeerMessage.ItemResult(transferId = "tx-99", index = 0, ok = true)
        val resEnv = env.copy(msgId = "m6", payload = itemRes)
        val resJson = PeerMessageJson.codec.encodeToString(LinkEnvelope.serializer(), resEnv)
        assertEquals(resEnv, PeerMessageJson.codec.decodeFromString(LinkEnvelope.serializer(), resJson))
    }

    @Test
    fun `ping pong and cancel roundtrip`() {
        val ping = PeerMessage.Ping("nonce-12345")
        val pong = PeerMessage.Pong("nonce-12345")
        val cancel = PeerMessage.Cancel("tx-99", "User closed window")

        listOf(ping, pong, cancel).forEachIndexed { idx, msg ->
            val env = LinkEnvelope(
                msgId = "m$idx",
                from = "sender",
                seq = idx.toLong(),
                ts = 1700000000L,
                payload = msg,
            )
            val json = PeerMessageJson.codec.encodeToString(LinkEnvelope.serializer(), env)
            val decoded = PeerMessageJson.codec.decodeFromString(LinkEnvelope.serializer(), json)
            assertEquals(env, decoded)
        }
    }

    @Test
    fun `sealed frame conversions to and from wire format work correctly`() {
        val nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val body = "hello world payload".toByteArray(Charsets.UTF_8)
        val mac = ByteArray(32) { 0x42 }

        val sealedEncrypted = SealedFrame(
            nonce = nonce,
            body = body,
            mac = mac,
            encrypted = true,
            fromDeviceId = "dev-alice",
        )
        val wireEncrypted = sealedEncrypted.toWire()
        assertNotNull(wireEncrypted.ciphertext)
        assertEquals(null, wireEncrypted.plaintext)
        val recoveredEncrypted = wireEncrypted.toSealed()
        assertEquals(sealedEncrypted, recoveredEncrypted)

        val sealedPlain = SealedFrame(
            nonce = nonce,
            body = body,
            mac = mac,
            encrypted = false,
            fromDeviceId = "dev-bob",
        )
        val wirePlain = sealedPlain.toWire()
        assertEquals("hello world payload", wirePlain.plaintext)
        assertEquals(null, wirePlain.ciphertext)
        val recoveredPlain = wirePlain.toSealed()
        assertEquals(sealedPlain, recoveredPlain)
    }
}
