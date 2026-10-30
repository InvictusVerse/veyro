package com.veyro.core.link

import com.veyro.core.crypto.ConnectionKeySchedule
import com.veyro.core.protocol.tcp.TcpFrame
import com.veyro.core.protocol.tcp.TcpFrameCodec
import com.veyro.core.protocol.tcp.Vtc3
import com.veyro.core.protocol.tcp.Vtc3Reader
import com.veyro.core.protocol.tcp.Vtc3Writer
import com.veyro.core.model.TransferRoute
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Session frames against `protocol/testvectors/link/session-frames-v3.json` (ADR-058): the sealer
 * reproduces every wire text byte for byte, the opener accepts them in order, and every negative
 * (replay, skipped counter, reorder, reflection, flipped tag, wrong `f`, bad base64) is refused.
 */
class SessionFrameVectorTest {

    private val file = V3Vectors.load("link/session-frames-v3.json")
    private val input = file.obj("input")
    private val idI = input.str("idI")
    private val idR = input.str("idR")

    private fun key(dir: String) = input.bytes(if (dir == "i2r") "kI2rHex" else "kR2iHex")
    private fun dirByte(dir: String) = if (dir == "i2r") FrameCrypto.DIR_I2R else FrameCrypto.DIR_R2I

    @Test
    fun `sealer reproduces every frame and the opener reads it back`() {
        val sealers = mapOf(
            "i2r" to SessionSealer(key("i2r"), idI, FrameCrypto.DIR_I2R),
            "r2i" to SessionSealer(key("r2i"), idR, FrameCrypto.DIR_R2I),
        )
        val openers = mapOf(
            "i2r" to SessionOpener(key("i2r"), idI, FrameCrypto.DIR_I2R),
            "r2i" to SessionOpener(key("r2i"), idR, FrameCrypto.DIR_R2I),
        )
        for (f in file.arr("frames").map { it.jsonObject }) {
            val dir = f.str("direction")
            assertEquals(f.num("counter"), sealers.getValue(dir).nextCounter)
            assertEquals(f.str("aadHex"), V3Vectors.hex(FrameCrypto.aad(f.str("from"), dirByte(dir), f.num("counter"))))
            assertEquals(f.str("name"), f.str("wire"), sealers.getValue(dir).seal(f.str("envelope")))
            assertEquals(f.str("envelope"), openers.getValue(dir).open(f.str("wire")))
        }
    }

    @Test
    fun `every negative is refused at failsAt`() {
        for (n in file.arr("negatives").map { it.jsonObject }) {
            val dir = n.str("direction")
            val opener = SessionOpener(key(dir), n.str("peerId"), dirByte(dir))
            val wires = n.arr("wires").map { it.jsonPrimitive.content }
            val failsAt = n.num("failsAt").toInt()
            for ((i, wire) in wires.withIndex()) {
                if (i < failsAt) {
                    opener.open(wire)
                } else {
                    try {
                        opener.open(wire)
                        fail("${n.str("name")}: frame $i must be refused")
                    } catch (expected: SessionFrameException) {
                    }
                    break
                }
            }
        }
    }
}

/** The key exporter against `protocol/testvectors/link/exporter-v3.json` (04-PROTOCOL.md 5.4). */
class ExporterVectorTest {

    private val file = V3Vectors.load("link/exporter-v3.json")

    @Test
    fun `exporter outputs match`() {
        val secret = file.bytes("expSecretHex")
        for (v in file.arr("vectors").map { it.jsonObject }) {
            val out = ConnectionKeySchedule.export(secret, v.str("label"), v.bytes("contextHex"), v.num("length").toInt())
            assertEquals(v.str("label"), v.str("outputHex"), V3Vectors.hex(out))
        }
    }

    @Test
    fun `invalid label, context or length is refused`() {
        val secret = file.bytes("expSecretHex")
        for (v in file.arr("invalid").map { it.jsonObject }) {
            assertThrows(v.str("name"), IllegalArgumentException::class.java) {
                ConnectionKeySchedule.export(secret, v.str("label"), ByteArray(v.num("contextLength").toInt()), v.num("length").toInt())
            }
        }
    }
}

/** VTC3 records against `protocol/testvectors/frames/vtc3-frames.json` (04-PROTOCOL.md 7.3). */
class Vtc3VectorTest {

    private val file = V3Vectors.load("frames/vtc3-frames.json")
    private val input = file.obj("input")
    private val expected = file.obj("expected")
    private val tid = input.str("transferId")

    private fun keyFor(dir: String) = expected.bytes(if (dir == "s2r") "keyS2RHex" else "keyR2SHex")
    private fun streamFor(dir: String) = if (dir == "s2r") Vtc3.STREAM_SENDER_TO_RECEIVER else Vtc3.STREAM_RECEIVER_TO_SENDER

    @Test
    fun `stream-open records and per-attempt keys match`() {
        val saltA = input.bytes("saltAHex")
        val saltB = input.bytes("saltBHex")
        assertEquals(expected.str("contextHex"), V3Vectors.hex(Vtc3.exportContext(tid, saltA, saltB)))
        val okm = Vtc3.keyDeriver({ l, c, n -> ConnectionKeySchedule.export(input.bytes("expSecretHex"), l, c, n) }, tid)(saltA, saltB)
        assertEquals(expected.str("okmHex"), V3Vectors.hex(okm))
        assertEquals(expected.str("streamOpenSenderHex"), V3Vectors.hex(Vtc3.streamOpenRecord(tid, saltA)))
        assertEquals(expected.str("streamOpenReceiverHex"), V3Vectors.hex(Vtc3.streamOpenRecord(tid, saltB)))
        assertEquals(V3Vectors.hex(saltA), V3Vectors.hex(Vtc3.readStreamOpen(expected.bytes("streamOpenSenderHex").inputStream(), tid)))
    }

    @Test
    fun `the writer reproduces every record and the reader decodes it`() {
        for (dir in listOf("s2r", "r2s")) {
            val records = file.arr("records").map { it.jsonObject }.filter { it.str("direction") == dir }
            val out = ByteArrayOutputStream()
            val writer = Vtc3Writer(out, keyFor(dir), streamFor(dir), tid)
            for (r in records) {
                val before = out.size()
                writer.write(frameOf(r.obj("frame")))
                assertEquals(r.str("name"), r.str("recordHex"), V3Vectors.hex(out.toByteArray().copyOfRange(before, out.size())))
            }
            val reader = Vtc3Reader(out.toByteArray().inputStream(), keyFor(dir), streamFor(dir), tid)
            for (r in records) {
                val frame = reader.readOrNull()!!
                assertEquals(r.num("seq"), frame.seq)
                assertEquals(r.str("name"), frameOf(r.obj("frame"), r.num("seq")), frame)
            }
            assertNull("clean EOF at a record boundary", reader.readOrNull())
        }
    }

    @Test
    fun `every negative fails the stream at failsAt`() {
        for (n in file.arr("negatives").map { it.jsonObject }) {
            val dir = n.str("direction")
            val bytes = n.arr("recordsHex").map { V3Vectors.unhex(it.jsonPrimitive.content) }.reduce { a, b -> a + b }
            val reader = Vtc3Reader(bytes.inputStream(), keyFor(dir), streamFor(dir), tid)
            val failsAt = n.num("failsAt").toInt()
            repeat(failsAt) { reader.readOrNull() }
            try {
                reader.readOrNull()
                fail("${n.str("name")}: record $failsAt must fail")
            } catch (expected: IOException) {
            }
            assertThrows("${n.str("name")}: the reader stays failed", IOException::class.java) { reader.readOrNull() }
        }
    }

    private fun frameOf(f: JsonObject, seq: Long = 0L): TcpFrame = when (f.str("type")) {
        "Auth" -> TcpFrame.Auth(seq, tid, f.str("authToken"), f.str("senderDeviceId"), TransferRoute.valueOf(f.str("route")))
        "AuthAck" -> TcpFrame.AuthAck(seq, tid, f.getValue("ok").jsonPrimitive.boolean)
        "ItemBegin" -> TcpFrame.ItemBegin(seq, tid, f.getValue("itemIndex").jsonPrimitive.int, f.getValue("sizeBytes").jsonPrimitive.long, f.getValue("chunkCount").jsonPrimitive.int, f.getValue("chunkSize").jsonPrimitive.int)
        "Chunk" -> TcpFrame.Chunk(seq, tid, f.getValue("itemIndex").jsonPrimitive.int, f.getValue("chunkIndex").jsonPrimitive.int, f.bytes("dataHex"))
        "ItemEnd" -> TcpFrame.ItemEnd(seq, tid, f.getValue("itemIndex").jsonPrimitive.int, f.str("sha256Hex"))
        "ItemResult" -> TcpFrame.ItemResult(seq, tid, f.getValue("itemIndex").jsonPrimitive.int, f.getValue("ok").jsonPrimitive.boolean)
        "TransferEnd" -> TcpFrame.TransferEnd(seq, tid, f.getValue("completed").jsonPrimitive.boolean)
        "Cancel" -> TcpFrame.Cancel(seq, tid, f.str("reason"))
        else -> error("unknown frame type")
    }

    @Test
    fun `payload codec constants are the VTC3 ones`() {
        assertTrue(TcpFrameCodec.MAGIC.contentEquals("VTC3".toByteArray()))
        assertEquals(0x09.toByte(), TcpFrameCodec.TYPE_STREAM_OPEN)
    }
}
