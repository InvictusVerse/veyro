package com.veyro.core.companion

import com.veyro.core.link.PeerMessage
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SmsBridge] gating, clamping, validation, send aggregation and new-message push, with fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class SmsBridgeTest {

    private class FakeLink(caps: Capabilities) : CompanionLink {
        val caps = MutableStateFlow(caps)
        val inbound = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 16)
        val sent = mutableListOf<PeerMessage>()
        override val peerCapabilities: Flow<Capabilities> get() = caps
        override val incoming: Flow<PeerMessage> get() = inbound
        override suspend fun send(message: PeerMessage) {
            sent += message
        }
        inline fun <reified T : PeerMessage> of(): List<T> = sent.filterIsInstance<T>()
    }

    private class FakeSource : SmsSource {
        val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        val store = mutableListOf<PeerMessage.SmsMessage>()
        var sims = listOf(PeerMessage.SimInfo(1, "Carrier A", 0))
        val threadLimits = mutableListOf<Int>()
        val messageLimits = mutableListOf<Int>()
        var afterCalls = 0
        override val changes: Flow<Unit> get() = changeFlow
        override suspend fun sims(): List<PeerMessage.SimInfo> = sims
        override suspend fun threads(limit: Int): List<PeerMessage.SmsThread> {
            threadLimits += limit
            return List(minOf(limit, 3)) { thread(it.toLong())!! }
        }
        override suspend fun thread(threadId: Long): PeerMessage.SmsThread? =
            PeerMessage.SmsThread(threadId = threadId, addresses = listOf("+15550100"), ts = 0)
        override suspend fun messages(threadId: Long, beforeTs: Long?, limit: Int): List<PeerMessage.SmsMessage> {
            messageLimits += limit
            return store.filter { it.threadId == threadId && (beforeTs == null || it.ts < beforeTs) }
                .sortedByDescending { it.ts }
                .take(limit)
        }
        override suspend fun maxId(): Long = store.maxOfOrNull { it.id } ?: 0L
        override suspend fun messagesAfter(id: Long): List<PeerMessage.SmsMessage> {
            afterCalls++
            return store.filter { it.id > id }.sortedBy { it.id }
        }
    }

    private class FakeSender : SmsSender {
        var permitted = true
        var parts = 1
        val calls = mutableListOf<String>()
        val results = mutableListOf<Channel<String>>()
        override fun canSend(): Boolean = permitted
        override suspend fun send(address: String, body: String, sim: Int?): SmsDispatch {
            calls += "$address:$sim"
            val channel = Channel<String>(Channel.UNLIMITED)
            results += channel
            return SmsDispatch(parts, channel.consumeAsFlow())
        }
    }

    private val smsCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.MESSAGING)
    private val plainCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.MEDIA_CONTROL)

    private fun msg(id: Long, ts: Long, thread: Long = 12) = PeerMessage.SmsMessage(
        id = id, threadId = thread, address = "+15550100", body = "b$id", ts = ts, out = false,
    )

    private class Rig(
        val links: MutableStateFlow<CompanionLink?>,
        val enabled: MutableStateFlow<Boolean>,
        val source: FakeSource,
        val sender: FakeSender,
    )

    private fun TestScope.rig(enabled: Boolean = true): Rig {
        val links = MutableStateFlow<CompanionLink?>(null)
        val on = MutableStateFlow(enabled)
        val source = FakeSource()
        val sender = FakeSender()
        SmsBridge(links.asLinkList(), on, source, sender, backgroundScope).start()
        runCurrent()
        return Rig(links, on, source, sender)
    }

    private fun TestScope.connect(rig: Rig, caps: Capabilities = smsCaps): FakeLink {
        val link = FakeLink(caps)
        rig.links.value = link
        runCurrent()
        return link
    }

    private fun send(vararg addresses: String, body: String = "hi", id: String = "c-1") =
        PeerMessage.SmsSend(clientId = id, addresses = addresses.toList(), body = body)

    @Test
    fun `sends the SIM list on connect when enabled`() = runTest {
        val rig = rig()
        val link = connect(rig)
        assertEquals(listOf(PeerMessage.SimList(rig.source.sims)), link.of<PeerMessage.SimList>())
    }

    @Test
    fun `nothing is served without the capability bit`() = runTest {
        val rig = rig()
        val link = connect(rig, plainCaps)
        link.inbound.emit(PeerMessage.SmsThreadsRequest())
        link.inbound.emit(send("+15550100"))
        runCurrent()
        assertTrue(link.sent.isEmpty())
        assertTrue(rig.source.threadLimits.isEmpty())

        link.caps.value = smsCaps
        runCurrent()
        assertEquals(1, link.of<PeerMessage.SimList>().size)
    }

    @Test
    fun `switched off - reads are empty, sends are disabled, nothing is read`() = runTest {
        val rig = rig(enabled = false)
        rig.source.store += msg(1, 100)
        val link = connect(rig)
        assertTrue("no simList while off", link.of<PeerMessage.SimList>().isEmpty())

        link.inbound.emit(PeerMessage.SmsThreadsRequest())
        link.inbound.emit(PeerMessage.SmsMessagesRequest(threadId = 12))
        link.inbound.emit(send("+15550100"))
        runCurrent()

        assertEquals(listOf(PeerMessage.SmsThreads(emptyList())), link.of<PeerMessage.SmsThreads>())
        assertEquals(listOf(PeerMessage.SmsMessages(12, emptyList(), more = false)), link.of<PeerMessage.SmsMessages>())
        assertEquals(listOf(PeerMessage.SmsSendResult("c-1", ok = false, error = "disabled")), link.of<PeerMessage.SmsSendResult>())
        assertTrue(rig.source.threadLimits.isEmpty())
        assertTrue(rig.source.messageLimits.isEmpty())
        assertTrue(rig.sender.calls.isEmpty())

        // Switched on mid-session: simList goes out then.
        rig.enabled.value = true
        runCurrent()
        assertEquals(1, link.of<PeerMessage.SimList>().size)
    }

    @Test
    fun `thread and message limits are clamped`() = runTest {
        val rig = rig()
        val link = connect(rig)
        link.inbound.emit(PeerMessage.SmsThreadsRequest(limit = 0))
        link.inbound.emit(PeerMessage.SmsThreadsRequest(limit = 5000))
        link.inbound.emit(PeerMessage.SmsMessagesRequest(threadId = 12, limit = -3))
        link.inbound.emit(PeerMessage.SmsMessagesRequest(threadId = 12, limit = 999))
        runCurrent()
        assertEquals(listOf(1, 200), rig.source.threadLimits)
        // One extra row is asked for to know whether an older page exists.
        assertEquals(listOf(2, 201), rig.source.messageLimits)
        assertEquals(1, link.of<PeerMessage.SmsThreads>().first().threads.size)
    }

    @Test
    fun `message pages are newest first, strictly older than beforeTs, with a more flag`() = runTest {
        val rig = rig()
        for (i in 1L..5L) rig.source.store += msg(i, i * 100)
        rig.source.store += msg(99, 50, thread = 7)
        val link = connect(rig)

        link.inbound.emit(PeerMessage.SmsMessagesRequest(threadId = 12, limit = 2))
        link.inbound.emit(PeerMessage.SmsMessagesRequest(threadId = 12, beforeTs = 300, limit = 2))
        link.inbound.emit(PeerMessage.SmsMessagesRequest(threadId = 12, beforeTs = 200, limit = 2))
        runCurrent()

        val pages = link.of<PeerMessage.SmsMessages>()
        assertEquals(listOf(5L, 4L), pages[0].messages.map { it.id })
        assertTrue(pages[0].more)
        assertEquals(listOf(2L, 1L), pages[1].messages.map { it.id })
        assertFalse(pages[1].more)
        assertEquals(listOf(1L), pages[2].messages.map { it.id })
        assertFalse(pages[2].more)
    }

    @Test
    fun `address and body validation table`() {
        val ok = "x"
        val cases = listOf(
            listOf("+1 (555) 010-0100") to ok,
            listOf("555.0100") to ok,
            listOf("[555] 0100") to ok,
            listOf("*#06#") to ok,
            listOf("123") to ok,
            listOf("12") to null,
            listOf("1".repeat(32)) to ok,
            listOf("1".repeat(33)) to null,
            listOf("+") to null,
            listOf("++15550100") to null,
            listOf("555-CALL") to null,
            listOf("") to null,
            emptyList<String>() to null,
            List(10) { "55501$it" } to ok,
            List(11) { "55501$it" } to null,
        )
        for ((addresses, expected) in cases) {
            val result = SmsBridge.validate(addresses, "hi")
            if (expected == null) assertNull("$addresses should be invalid", result) else assertTrue("$addresses should be valid", result != null)
        }
        assertEquals(listOf("+15550100100"), SmsBridge.validate(listOf("+1 (555) 010-0100"), "hi"))
        assertNull(SmsBridge.validate(listOf("5550100"), ""))
        assertTrue(SmsBridge.validate(listOf("5550100"), "a".repeat(2000)) != null)
        assertNull(SmsBridge.validate(listOf("5550100"), "a".repeat(2001)))
    }

    @Test
    fun `invalid and permission refusals`() = runTest {
        val rig = rig()
        val link = connect(rig)
        link.inbound.emit(send("12", id = "bad"))
        runCurrent()
        rig.sender.permitted = false
        link.inbound.emit(send("+15550100", id = "noperm"))
        runCurrent()
        assertEquals(
            listOf(
                PeerMessage.SmsSendResult("bad", ok = false, error = "invalid"),
                PeerMessage.SmsSendResult("noperm", ok = false, error = "permission"),
            ),
            link.of<PeerMessage.SmsSendResult>(),
        )
        assertTrue(rig.sender.calls.isEmpty())
    }

    @Test
    fun `multipart result is sent once, after every part, ok only if all parts ok`() = runTest {
        val rig = rig()
        rig.sender.parts = 3
        val link = connect(rig)

        link.inbound.emit(send("+15550100", id = "all-ok"))
        runCurrent()
        val first = rig.sender.results.single()
        first.send("ok")
        first.send("ok")
        runCurrent()
        assertTrue("no result before the last part", link.of<PeerMessage.SmsSendResult>().isEmpty())
        first.send("ok")
        runCurrent()
        assertEquals(listOf(PeerMessage.SmsSendResult("all-ok", ok = true)), link.of<PeerMessage.SmsSendResult>())

        link.inbound.emit(send("+15550100", id = "one-bad"))
        runCurrent()
        val second = rig.sender.results[1]
        second.send("ok")
        second.send("radio")
        second.send("ok")
        runCurrent()
        assertEquals(PeerMessage.SmsSendResult("one-bad", ok = false, error = "radio"), link.of<PeerMessage.SmsSendResult>().last())
        assertEquals(2, link.of<PeerMessage.SmsSendResult>().size)
    }

    @Test
    fun `a group send is one message per address and one result`() = runTest {
        val rig = rig()
        val link = connect(rig)
        link.inbound.emit(PeerMessage.SmsSend("g", listOf("555-0100", "555-0101"), "hi", sim = 2))
        runCurrent()
        rig.sender.results[0].send("ok")
        runCurrent()
        rig.sender.results[1].send("no-service")
        runCurrent()
        assertEquals(listOf("5550100:2", "5550101:2"), rig.sender.calls)
        assertEquals(listOf(PeerMessage.SmsSendResult("g", ok = false, error = "no-service")), link.of<PeerMessage.SmsSendResult>())
    }

    @Test
    fun `a send whose parts never all report times out as failed`() = runTest {
        val rig = rig()
        rig.sender.parts = 2
        val link = connect(rig)
        link.inbound.emit(send("+15550100", id = "slow"))
        runCurrent()
        rig.sender.results.single().send("ok")
        advanceTimeBy(SmsBridge.SEND_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue(link.of<PeerMessage.SmsSendResult>().isEmpty())

        // Reads are not blocked by a pending send.
        link.inbound.emit(PeerMessage.SmsThreadsRequest())
        runCurrent()
        assertEquals(1, link.of<PeerMessage.SmsThreads>().size)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(PeerMessage.SmsSendResult("slow", ok = false, error = "failed")), link.of<PeerMessage.SmsSendResult>())
    }

    @Test
    fun `only messages above the session-start high-water mark are pushed, debounced`() = runTest {
        val rig = rig()
        rig.source.store += listOf(msg(1, 100), msg(2, 200), msg(3, 300))
        val link = connect(rig)

        rig.source.store += msg(4, 400)
        rig.source.changeFlow.emit(Unit)
        runCurrent()
        advanceTimeBy(100)
        rig.source.store += msg(5, 500)
        rig.source.changeFlow.emit(Unit)
        runCurrent()
        assertTrue("debounced", link.of<PeerMessage.SmsNew>().isEmpty())

        advanceTimeBy(SmsBridge.DEBOUNCE_MS)
        runCurrent()
        assertEquals(listOf(4L, 5L), link.of<PeerMessage.SmsNew>().map { it.message.id })
        assertEquals(12L, link.of<PeerMessage.SmsNew>().first().thread?.threadId)
        assertEquals(1, rig.source.afterCalls)

        // A change with nothing new pushes nothing; the mark has moved past 5.
        rig.source.changeFlow.emit(Unit)
        advanceTimeBy(SmsBridge.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(2, link.of<PeerMessage.SmsNew>().size)

        rig.source.store += msg(6, 600)
        rig.source.changeFlow.emit(Unit)
        advanceTimeBy(SmsBridge.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(listOf(4L, 5L, 6L), link.of<PeerMessage.SmsNew>().map { it.message.id })
    }

    @Test
    fun `no push while switched off`() = runTest {
        val rig = rig(enabled = false)
        val link = connect(rig)
        rig.source.store += msg(1, 100)
        rig.source.changeFlow.emit(Unit)
        advanceTimeBy(SmsBridge.DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(link.of<PeerMessage.SmsNew>().isEmpty())
        assertEquals(0, rig.source.afterCalls)
    }
}
