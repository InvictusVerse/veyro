package com.veyro.core.companion

import com.veyro.core.link.PeerMessage
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CallBridge] state mapping, gating, action dispatch and call log, with fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallBridgeTest {

    private class FakeLink(caps: Capabilities) : CompanionLink {
        val caps = MutableStateFlow(caps)
        val inbound = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 16)
        val sent = mutableListOf<PeerMessage>()
        override val peerCapabilities: Flow<Capabilities> get() = caps
        override val incoming: Flow<PeerMessage> get() = inbound
        override suspend fun send(message: PeerMessage) {
            sent += message
        }
        val states get() = sent.filterIsInstance<PeerMessage.CallState>()
    }

    private class FakeSource : CallSource {
        val signalFlow = MutableSharedFlow<CallSignal>(extraBufferCapacity = 16)
        var collectors = 0
        val logLimits = mutableListOf<Int>()
        override val signals: Flow<CallSignal>
            get() = signalFlow.onStart { collectors++ }.onCompletion { collectors-- }
        override suspend fun contactName(number: String): String? = if (number == "+15550100") "Sam" else null
        override suspend fun callLog(limit: Int): List<PeerMessage.CallLogEntry> {
            logLimits += limit
            return listOf(PeerMessage.CallLogEntry(5, "+15550100", "Sam", 1, 42, "missed"))
        }
    }

    private class FakeController : CallController {
        val calls = mutableListOf<String>()
        override suspend fun answer() { calls += "answer" }
        override suspend fun end() { calls += "end" }
        override suspend fun silence() { calls += "silence" }
        override suspend fun dial(number: String) { calls += "dial:$number" }
        override fun restoreAfterCall() { calls += "restore" }
        val actions get() = calls.filter { it != "restore" }
    }

    private val callCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.CALLS)
    private val plainCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.MESSAGING)

    private class Rig(
        val links: MutableStateFlow<CompanionLink?>,
        val enabled: MutableStateFlow<Boolean>,
        val source: FakeSource,
        val controller: FakeController,
    )

    private fun TestScope.rig(enabled: Boolean = true): Rig {
        val links = MutableStateFlow<CompanionLink?>(null)
        val on = MutableStateFlow(enabled)
        val source = FakeSource()
        val controller = FakeController()
        var n = 0
        CallBridge(
            links.asLinkList(), on, source, controller, backgroundScope,
            clock = { testScheduler.currentTime },
            newCallId = { "call-${++n}" },
        ).start()
        runCurrent()
        return Rig(links, on, source, controller)
    }

    private fun TestScope.connect(rig: Rig, caps: Capabilities = callCaps): FakeLink {
        val link = FakeLink(caps)
        rig.links.value = link
        runCurrent()
        return link
    }

    private suspend fun TestScope.signal(rig: Rig, state: TelephonyCallState, number: String? = null) {
        rig.source.signalFlow.emit(CallSignal(state, number))
        runCurrent()
    }

    @Test
    fun `incoming call - ringing, answered, ended`() = runTest {
        val rig = rig()
        val link = connect(rig)
        signal(rig, TelephonyCallState.IDLE)
        signal(rig, TelephonyCallState.RINGING, "+15550100")
        signal(rig, TelephonyCallState.OFFHOOK)
        signal(rig, TelephonyCallState.IDLE)

        assertEquals(
            listOf(
                PeerMessage.CallState("call-1", "ringing", "in", "+15550100", "Sam"),
                PeerMessage.CallState("call-1", "active", "in", "+15550100", "Sam"),
                PeerMessage.CallState("call-1", "ended", "in", "+15550100", "Sam"),
            ),
            link.states,
        )
    }

    @Test
    fun `the number arriving in a second ringing report updates the call`() = runTest {
        val rig = rig()
        val link = connect(rig)
        signal(rig, TelephonyCallState.RINGING)
        signal(rig, TelephonyCallState.RINGING, "+15550100")
        signal(rig, TelephonyCallState.RINGING, "+15550100")
        assertEquals(
            listOf(
                PeerMessage.CallState("call-1", "ringing", "in"),
                PeerMessage.CallState("call-1", "ringing", "in", "+15550100", "Sam"),
            ),
            link.states,
        )
    }

    @Test
    fun `offhook without ringing is an outgoing call, a missed call just ends`() = runTest {
        val rig = rig()
        val link = connect(rig)
        signal(rig, TelephonyCallState.OFFHOOK)
        signal(rig, TelephonyCallState.OFFHOOK)
        signal(rig, TelephonyCallState.IDLE)
        signal(rig, TelephonyCallState.RINGING, "+15550199")
        signal(rig, TelephonyCallState.IDLE)
        signal(rig, TelephonyCallState.IDLE)

        assertEquals(
            listOf(
                PeerMessage.CallState("call-1", "active", "out"),
                PeerMessage.CallState("call-1", "ended", "out"),
                PeerMessage.CallState("call-2", "ringing", "in", "+15550199"),
                PeerMessage.CallState("call-2", "ended", "in", "+15550199"),
            ),
            link.states,
        )
    }

    @Test
    fun `a call dialed from the PC carries its number`() = runTest {
        val rig = rig()
        val link = connect(rig)
        link.inbound.emit(PeerMessage.CallAction("dial", number = "+1 555-0100"))
        runCurrent()
        assertEquals(listOf("dial:+15550100"), rig.controller.actions)
        signal(rig, TelephonyCallState.OFFHOOK)
        assertEquals(PeerMessage.CallState("call-1", "active", "out", "+15550100", "Sam"), link.states.single())
    }

    @Test
    fun `invalid dial numbers are ignored`() = runTest {
        val rig = rig()
        val link = connect(rig)
        for (n in listOf(null, "", "12", "555-CALL", "1".repeat(33), "++1555")) {
            link.inbound.emit(PeerMessage.CallAction("dial", number = n))
        }
        runCurrent()
        assertTrue(rig.controller.actions.isEmpty())
    }

    @Test
    fun `actions for another call id are ignored`() = runTest {
        val rig = rig()
        val link = connect(rig)
        link.inbound.emit(PeerMessage.CallAction("decline", callId = "call-1"))
        runCurrent()
        assertTrue("no current call", rig.controller.actions.isEmpty())

        signal(rig, TelephonyCallState.RINGING, "+15550100")
        link.inbound.emit(PeerMessage.CallAction("decline", callId = "stale"))
        link.inbound.emit(PeerMessage.CallAction("answer"))
        link.inbound.emit(PeerMessage.CallAction("explode", callId = "call-1"))
        runCurrent()
        assertTrue(rig.controller.actions.isEmpty())

        link.inbound.emit(PeerMessage.CallAction("silence", callId = "call-1"))
        link.inbound.emit(PeerMessage.CallAction("answer", callId = "call-1"))
        runCurrent()
        signal(rig, TelephonyCallState.OFFHOOK)
        // Answer and silence only apply while ringing; end works on an active call.
        link.inbound.emit(PeerMessage.CallAction("answer", callId = "call-1"))
        link.inbound.emit(PeerMessage.CallAction("end", callId = "call-1"))
        runCurrent()
        assertEquals(listOf("silence", "answer", "end"), rig.controller.actions)
    }

    @Test
    fun `nothing is tracked without the capability or the setting`() = runTest {
        val rig = rig(enabled = false)
        val link = connect(rig, plainCaps)
        assertEquals(0, rig.source.collectors)
        link.caps.value = callCaps
        runCurrent()
        assertEquals("setting off: no listener", 0, rig.source.collectors)

        link.inbound.emit(PeerMessage.CallAction("dial", number = "+15550100"))
        link.inbound.emit(PeerMessage.CallLogRequest())
        runCurrent()
        assertTrue(rig.controller.actions.isEmpty())
        assertEquals(listOf(PeerMessage.CallLog(emptyList())), link.sent)
        assertTrue(rig.source.logLimits.isEmpty())

        rig.enabled.value = true
        runCurrent()
        assertEquals(1, rig.source.collectors)
        signal(rig, TelephonyCallState.RINGING, "+15550100")
        assertEquals(1, link.states.size)

        // Switched off mid-call: the PC is told the call ended, and tracking stops.
        rig.enabled.value = false
        runCurrent()
        assertEquals("ended", link.states.last().state)
        assertEquals(0, rig.source.collectors)
        assertTrue("silence fallback undone", "restore" in rig.controller.calls)
    }

    @Test
    fun `call log limit is clamped`() = runTest {
        val rig = rig()
        val link = connect(rig)
        link.inbound.emit(PeerMessage.CallLogRequest(limit = 0))
        link.inbound.emit(PeerMessage.CallLogRequest(limit = 10_000))
        runCurrent()
        assertEquals(listOf(1, 500), rig.source.logLimits)
        assertEquals(2, link.sent.filterIsInstance<PeerMessage.CallLog>().size)
    }
}
