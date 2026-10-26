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
 * Companion SMS and call messages, round-tripped against `protocol/testvectors/link-sms-calls.json`
 * (04-PROTOCOL.md section 6.3, ADR-027). Shared with the Windows suite so the two cannot drift.
 */
class SmsCallMessageVectorTest {

    private val json = LinkJson.codec

    private fun vectors(): List<Pair<String, String>> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var file: File? = null
        while (dir != null && file == null) {
            file = File(dir, "protocol/testvectors/link-sms-calls.json").takeIf { it.isFile }
            dir = dir.parentFile
        }
        checkNotNull(file) { "protocol/testvectors/link-sms-calls.json not found above ${System.getProperty("user.dir")}" }
        val root = json.parseToJsonElement(file.readText()).jsonObject
        return root.getValue("vectors").jsonArray.map {
            it.jsonObject.getValue("type").jsonPrimitive.content to it.jsonObject.getValue("json").jsonPrimitive.content
        }
    }

    private val expectedTypes = mapOf(
        "smsThreadsReq" to LinkMessage.SmsThreadsRequest::class,
        "smsThreads" to LinkMessage.SmsThreads::class,
        "smsMessagesReq" to LinkMessage.SmsMessagesRequest::class,
        "smsMessages" to LinkMessage.SmsMessages::class,
        "smsNew" to LinkMessage.SmsNew::class,
        "smsSend" to LinkMessage.SmsSend::class,
        "smsSendResult" to LinkMessage.SmsSendResult::class,
        "simList" to LinkMessage.SimList::class,
        "callState" to LinkMessage.CallState::class,
        "callAction" to LinkMessage.CallAction::class,
        "callLogReq" to LinkMessage.CallLogRequest::class,
        "callLog" to LinkMessage.CallLog::class,
    )

    @Test
    fun `every vector decodes to its type and re-encodes identically`() {
        val all = vectors()
        assertEquals(13, all.size)

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

        assertEquals(LinkMessage.SmsThreadsRequest(limit = 50), decoded[0])
        val thread = (decoded[1] as LinkMessage.SmsThreads).threads.single()
        assertEquals(12L, thread.threadId)
        assertEquals(listOf<String?>("Sam"), thread.names)
        assertEquals(1, thread.unread)
        assertEquals(LinkMessage.SmsMessagesRequest(threadId = 12, beforeTs = 1793011200000, limit = 50), decoded[2])

        val page = decoded[3] as LinkMessage.SmsMessages
        assertTrue(page.more)
        val incoming = page.messages.single()
        assertEquals(false, incoming.out)
        assertEquals(false, incoming.read)
        assertEquals(1, incoming.sim)

        val new = decoded[4] as LinkMessage.SmsNew
        assertEquals("sent", new.message.status)
        assertEquals(null, new.thread)

        assertEquals(LinkMessage.SmsSend("c-1", listOf("+15550100"), "Sure, 1pm", sim = 1), decoded[5])
        assertEquals(LinkMessage.SmsSendResult("c-1", ok = false, error = "no-service"), decoded[6])
        assertEquals(LinkMessage.SimList(listOf(LinkMessage.SimInfo(1, "Carrier A", 0))), decoded[7])
        assertEquals(LinkMessage.CallState("k-77", "ringing", "in", "+15550100", "Sam"), decoded[8])
        assertEquals(LinkMessage.CallAction("decline", callId = "k-77"), decoded[9])
        assertEquals(LinkMessage.CallAction("dial", number = "+15550100"), decoded[10])
        assertEquals(LinkMessage.CallLogRequest(limit = 100), decoded[11])
        assertEquals(
            LinkMessage.CallLog(listOf(LinkMessage.CallLogEntry(5, "+15550100", "Sam", 1793011000000, 42, "missed"))),
            decoded[12],
        )
    }
}
