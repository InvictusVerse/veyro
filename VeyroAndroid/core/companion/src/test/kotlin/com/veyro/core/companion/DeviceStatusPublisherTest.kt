package com.veyro.core.companion

import com.veyro.core.link.LinkMessage
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DeviceStatusPublisher] sending, dedupe, peer status, and devControl gating, with fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceStatusPublisherTest {

    private class FakeLink(caps: Capabilities) : CompanionLink {
        val caps = MutableStateFlow(caps)
        val inbound = MutableSharedFlow<LinkMessage>(extraBufferCapacity = 16)
        val sent = mutableListOf<LinkMessage>()
        override val peerCapabilities: Flow<Capabilities> get() = caps
        override val incoming: Flow<LinkMessage> get() = inbound
        override val peerDeviceId: String = "PC-ID"
        override suspend fun send(message: LinkMessage) {
            sent += message
        }
        val statuses get() = sent.filterIsInstance<LinkMessage.DeviceStatus>()
    }

    private class FakeSource : DeviceStatusSource {
        val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        var current = LinkMessage.DeviceStatus(battery = 82, charging = false, ringer = "normal", dnd = false)
        override val changes: Flow<Unit> get() = changeFlow
        override suspend fun current(): LinkMessage.DeviceStatus = current
    }

    private class FakeController : DeviceController {
        val calls = mutableListOf<String>()
        var policyAccess = true
        override suspend fun startRing(fromName: String?) {
            calls += "ring:$fromName"
        }
        override suspend fun stopRing() {
            calls += "stop"
        }
        override fun hasPolicyAccess(): Boolean = policyAccess
        override suspend fun setRinger(mode: String) {
            calls += "ringer:$mode"
        }
        override suspend fun setDnd(enabled: Boolean) {
            calls += "dnd:$enabled"
        }
    }

    private val statusCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.DEVICE_STATUS)

    private class Rig(
        val publisher: DeviceStatusPublisher,
        val links: MutableStateFlow<CompanionLink?>,
        val allowed: MutableStateFlow<Boolean>,
        val source: FakeSource,
        val controller: FakeController,
    )

    private fun TestScope.rig(allowed: Boolean = true): Rig {
        val links = MutableStateFlow<CompanionLink?>(null)
        val allow = MutableStateFlow(allowed)
        val source = FakeSource()
        val controller = FakeController()
        val publisher = DeviceStatusPublisher(
            links = links,
            controlAllowed = allow,
            source = source,
            controller = controller,
            peerName = { id -> if (id == "PC-ID") "Desk PC" else null },
            scope = backgroundScope,
        )
        publisher.start()
        runCurrent()
        return Rig(publisher, links, allow, source, controller)
    }

    private fun TestScope.connect(rig: Rig, caps: Capabilities = statusCaps): FakeLink {
        val link = FakeLink(caps)
        rig.links.value = link
        runCurrent()
        return link
    }

    @Test
    fun `sends status on connect, only with the capability bit`() = runTest {
        val rig = rig()
        val plain = connect(rig, Capabilities.of(Capability.FILE_TRANSFER))
        assertTrue(plain.sent.isEmpty())

        val link = connect(rig)
        assertEquals(listOf(rig.source.current), link.statuses)
    }

    @Test
    fun `battery is only resent on a whole-point or charging change`() = runTest {
        val rig = rig()
        val link = connect(rig)

        // Voltage/temperature ticks: the broadcast fires but nothing on the wire changes.
        repeat(3) { rig.source.changeFlow.emit(Unit) }
        runCurrent()
        assertEquals(1, link.statuses.size)

        rig.source.current = rig.source.current.copy(battery = 81)
        rig.source.changeFlow.emit(Unit)
        runCurrent()
        rig.source.current = rig.source.current.copy(charging = true)
        rig.source.changeFlow.emit(Unit)
        runCurrent()
        rig.source.changeFlow.emit(Unit)
        runCurrent()

        assertEquals(listOf(82, 81, 81), link.statuses.map { it.battery })
        assertEquals(listOf(false, false, true), link.statuses.map { it.charging })
    }

    @Test
    fun `radio changes are sent, unchanged radios are not resent`() = runTest {
        val rig = rig()
        rig.source.current = rig.source.current.copy(wifi = 3, cell = 2, carrier = "Carrier A", bluetooth = true, mobileData = false)
        val link = connect(rig)

        // Signal callbacks fire often; the same bars do not go out again.
        repeat(3) { rig.source.changeFlow.emit(Unit) }
        runCurrent()
        rig.source.current = rig.source.current.copy(wifi = -1, mobileData = true)
        rig.source.changeFlow.emit(Unit)
        runCurrent()
        rig.source.current = rig.source.current.copy(bluetooth = false)
        rig.source.changeFlow.emit(Unit)
        runCurrent()

        assertEquals(listOf(3, -1, -1), link.statuses.map { it.wifi })
        assertEquals(listOf(false, true, true), link.statuses.map { it.mobileData })
        assertEquals(listOf(true, true, false), link.statuses.map { it.bluetooth })
        assertEquals("Carrier A", link.statuses.first().carrier)
    }

    @Test
    fun `keeps the PC status until disconnect`() = runTest {
        val rig = rig()
        val link = connect(rig)
        link.inbound.emit(LinkMessage.DeviceStatus(battery = 55, charging = true))
        runCurrent()
        assertEquals(55, rig.publisher.peerStatus.value?.battery)

        rig.links.value = null
        runCurrent()
        assertNull(rig.publisher.peerStatus.value)
    }

    @Test
    fun `ring stops by itself after 30 seconds`() = runTest {
        val rig = rig()
        val link = connect(rig)

        link.inbound.emit(LinkMessage.DeviceControl(ring = true))
        runCurrent()
        assertEquals(listOf("ring:Desk PC"), rig.controller.calls)

        advanceTimeBy(DeviceStatusPublisher.RING_TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(listOf("ring:Desk PC"), rig.controller.calls)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("ring:Desk PC", "stop"), rig.controller.calls)
    }

    @Test
    fun `ring false stops at once and cancels the timeout`() = runTest {
        val rig = rig()
        val link = connect(rig)

        link.inbound.emit(LinkMessage.DeviceControl(ring = true))
        runCurrent()
        advanceTimeBy(5_000)
        link.inbound.emit(LinkMessage.DeviceControl(ring = false))
        runCurrent()
        advanceTimeBy(DeviceStatusPublisher.RING_TIMEOUT_MS * 2)
        runCurrent()

        assertEquals(listOf("ring:Desk PC", "stop"), rig.controller.calls)
    }

    @Test
    fun `devControl is ignored while the setting is off, but status is still resent`() = runTest {
        val rig = rig(allowed = false)
        val link = connect(rig)

        link.inbound.emit(LinkMessage.DeviceControl(ring = true, ringer = "silent", dnd = true))
        runCurrent()
        advanceTimeBy(DeviceStatusPublisher.RING_TIMEOUT_MS * 2)
        runCurrent()

        assertTrue(rig.controller.calls.isEmpty())
        assertEquals("connect + reply to devControl", 2, link.statuses.size)
    }

    @Test
    fun `ringer and dnd need policy access, ring does not`() = runTest {
        val rig = rig()
        rig.controller.policyAccess = false
        val link = connect(rig)

        link.inbound.emit(LinkMessage.DeviceControl(ring = true, ringer = "silent", dnd = true))
        runCurrent()
        assertEquals(listOf("ring:Desk PC"), rig.controller.calls)

        rig.controller.calls.clear()
        rig.controller.policyAccess = true
        link.inbound.emit(LinkMessage.DeviceControl(ringer = "vibrate", dnd = false))
        link.inbound.emit(LinkMessage.DeviceControl(ringer = "loud"))
        runCurrent()
        assertEquals(listOf("ringer:vibrate", "dnd:false"), rig.controller.calls)
    }

    @Test
    fun `the resulting status is published after applying`() = runTest {
        val rig = rig()
        val link = connect(rig)

        rig.source.current = rig.source.current.copy(ringer = "vibrate")
        link.inbound.emit(LinkMessage.DeviceControl(ringer = "vibrate"))
        runCurrent()

        assertEquals(listOf("normal", "vibrate"), link.statuses.map { it.ringer })
    }
}
