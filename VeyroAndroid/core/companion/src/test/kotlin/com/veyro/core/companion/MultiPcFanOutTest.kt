package com.veyro.core.companion

import com.veyro.core.link.LinkMessage
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multiple PCs: every companion feature runs once per connected PC. Phone-side events reach
 * every capable PC, state from a PC is kept under its id, a request is answered on the session
 * it came from only, and one PC leaving does not stop the others.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiPcFanOutTest {

    private class FakeLink(override val peerDeviceId: String, caps: Capabilities) : CompanionLink {
        val caps = MutableStateFlow(caps)
        val inbound = MutableSharedFlow<LinkMessage>(extraBufferCapacity = 16)
        val sent = mutableListOf<LinkMessage>()
        override val peerCapabilities: Flow<Capabilities> get() = caps
        override val incoming: Flow<LinkMessage> get() = inbound
        override suspend fun send(message: LinkMessage) {
            sent += message
        }
        inline fun <reified T : LinkMessage> of(): List<T> = sent.filterIsInstance<T>()
    }

    private val all = Capabilities.of(
        Capability.FILE_TRANSFER,
        Capability.NOTIFICATION_MIRROR,
        Capability.MEDIA_CONTROL,
        Capability.DEVICE_STATUS,
        Capability.MESSAGING,
    )

    // ── Notifications ───────────────────────────────────────────────────────────────────────

    private class NotifSource : NotificationSource {
        val events_ = MutableSharedFlow<NotificationEvent>(extraBufferCapacity = 16)
        val active = mutableListOf<LinkMessage.Notification>()
        override val available: Flow<Boolean> = MutableStateFlow(true)
        override val events: Flow<NotificationEvent> get() = events_
        override suspend fun activeNotifications(): List<LinkMessage.Notification> = active.toList()
        override suspend fun isActive(key: String): Boolean = active.any { it.key == key }
    }

    private class NotifExecutor : NotificationActionExecutor {
        val calls = mutableListOf<String>()
        override suspend fun invokeAction(key: String, index: Int) { calls += "action:$key" }
        override suspend fun reply(key: String, text: String) { calls += "reply:$key" }
        override suspend fun dismiss(key: String) { calls += "dismiss:$key" }
    }

    private fun notif(key: String) = LinkMessage.Notification(key = key, kind = LinkMessage.Notification.KIND_POSTED, pkg = "p", title = "t")

    @Test
    fun `notifications go to both PCs and closing one leaves the other mirrored`() = runTest {
        val links = MutableStateFlow<List<CompanionLink>>(emptyList())
        val source = NotifSource().apply { active += notif("a") }
        val executor = NotifExecutor()
        NotificationMirror(links, MutableStateFlow(true), source, executor, backgroundScope).start()
        runCurrent()

        val office = FakeLink("OFFICE", all)
        val laptop = FakeLink("LAPTOP", all)
        links.value = listOf(office, laptop)
        runCurrent()
        assertEquals(listOf("a"), office.of<LinkMessage.Notification>().map { it.key })
        assertEquals(listOf("a"), laptop.of<LinkMessage.Notification>().map { it.key })

        source.events_.emit(NotificationEvent.Posted(notif("b")))
        runCurrent()
        assertEquals("b", office.of<LinkMessage.Notification>().last().key)
        assertEquals("b", laptop.of<LinkMessage.Notification>().last().key)

        // A request from one PC acts once, not once per PC.
        office.inbound.emit(LinkMessage.NotificationDismissRequest("a"))
        runCurrent()
        assertEquals(listOf("dismiss:a"), executor.calls)

        links.value = listOf(laptop)
        runCurrent()
        source.events_.emit(NotificationEvent.Posted(notif("c")))
        runCurrent()
        assertEquals("the closed PC gets nothing more", "b", office.of<LinkMessage.Notification>().last().key)
        assertEquals("c", laptop.of<LinkMessage.Notification>().last().key)
    }

    // ── Device status ───────────────────────────────────────────────────────────────────────

    private class StatusSource : DeviceStatusSource {
        val changes_ = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        var current = LinkMessage.DeviceStatus(battery = 50)
        override val changes: Flow<Unit> get() = changes_
        override suspend fun current(): LinkMessage.DeviceStatus = current
    }

    private class Controller : DeviceController {
        val rings = mutableListOf<String?>()
        override suspend fun startRing(fromName: String?) { rings += fromName }
        override suspend fun stopRing() {}
        override fun hasPolicyAccess(): Boolean = false
        override suspend fun setRinger(mode: String) {}
        override suspend fun setDnd(enabled: Boolean) {}
    }

    @Test
    fun `device status fans out, PC status is kept per PC, and devControl is answered on its own PC`() = runTest {
        val links = MutableStateFlow<List<CompanionLink>>(emptyList())
        val source = StatusSource()
        val controller = Controller()
        val publisher = DeviceStatusPublisher(
            links = links,
            controlAllowed = MutableStateFlow(true),
            source = source,
            controller = controller,
            peerName = { it },
            scope = backgroundScope,
        )
        publisher.start()
        runCurrent()

        val office = FakeLink("OFFICE", all)
        val laptop = FakeLink("LAPTOP", all)
        links.value = listOf(office, laptop)
        runCurrent()
        assertEquals(1, office.of<LinkMessage.DeviceStatus>().size)
        assertEquals(1, laptop.of<LinkMessage.DeviceStatus>().size)

        source.current = LinkMessage.DeviceStatus(battery = 49)
        source.changes_.emit(Unit)
        runCurrent()
        assertEquals(49, office.of<LinkMessage.DeviceStatus>().last().battery)
        assertEquals(49, laptop.of<LinkMessage.DeviceStatus>().last().battery)

        office.inbound.emit(LinkMessage.DeviceStatus(battery = 90))
        laptop.inbound.emit(LinkMessage.DeviceStatus(battery = 20))
        runCurrent()
        assertEquals(90, publisher.peerStatus.value["OFFICE"]?.battery)
        assertEquals(20, publisher.peerStatus.value["LAPTOP"]?.battery)

        val officeBefore = office.sent.size
        val laptopBefore = laptop.sent.size
        laptop.inbound.emit(LinkMessage.DeviceControl(ring = true))
        runCurrent()
        assertEquals("rang once, for the PC that asked", listOf<String?>("LAPTOP"), controller.rings)
        assertEquals("the reply goes to the asking PC", laptopBefore + 1, laptop.sent.size)
        assertEquals("the other PC is not answered", officeBefore, office.sent.size)

        links.value = listOf(office)
        runCurrent()
        assertNull("a PC that left has no status", publisher.peerStatus.value["LAPTOP"])
        assertEquals(90, publisher.peerStatus.value["OFFICE"]?.battery)
    }

    // ── Media ───────────────────────────────────────────────────────────────────────────────

    private class Media : MediaSource, MediaCommandExecutor {
        val changes_ = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val calls = mutableListOf<String>()
        override val available: Flow<Boolean> = MutableStateFlow(true)
        override val changes: Flow<Unit> get() = changes_
        override suspend fun snapshot(): LinkMessage.MediaState = LinkMessage.MediaState(sessions = emptyList(), volume = 40)
        override suspend fun transport(id: String, action: String, positionMs: Long?) { calls += action }
        override suspend fun setVolume(percent: Int) { calls += "volume:$percent" }
        override suspend fun setMuted(muted: Boolean) { calls += "mute:$muted" }
    }

    @Test
    fun `phone media goes to both PCs, PC media is per PC, and a command goes to the chosen PC only`() = runTest {
        val links = MutableStateFlow<List<CompanionLink>>(emptyList())
        val media = Media()
        val mirror = MediaMirror(links, MutableStateFlow(true), media, media, backgroundScope)
        mirror.start()
        runCurrent()

        val office = FakeLink("OFFICE", all)
        val laptop = FakeLink("LAPTOP", all)
        links.value = listOf(office, laptop)
        runCurrent()
        assertEquals(1, office.of<LinkMessage.MediaState>().size)
        assertEquals(1, laptop.of<LinkMessage.MediaState>().size)

        val officeMedia = LinkMessage.MediaState(sessions = emptyList(), volume = 70)
        office.inbound.emit(officeMedia)
        runCurrent()
        assertEquals(officeMedia, mirror.peerMedia.value["OFFICE"])
        assertNull(mirror.peerMedia.value["LAPTOP"])

        val command = LinkMessage.MediaCommand(LinkMessage.MediaCommand.VOLUME, value = 10)
        assertTrue(mirror.sendMediaCommand("LAPTOP", command))
        runCurrent()
        assertEquals(command, laptop.sent.last())
        assertFalse(office.sent.contains(command))
        assertFalse("an unknown PC is refused", mirror.sendMediaCommand("NOPE", command))

        // A PC's command acts on the phone once.
        office.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.VOLUME, value = 30))
        runCurrent()
        assertEquals(listOf("volume:30"), media.calls)
    }

    // ── SMS ─────────────────────────────────────────────────────────────────────────────────

    private class Sms : SmsSource, SmsSender {
        override val changes: Flow<Unit> = MutableSharedFlow()
        override suspend fun sims(): List<LinkMessage.SimInfo> = emptyList()
        override suspend fun threads(limit: Int): List<LinkMessage.SmsThread> =
            listOf(LinkMessage.SmsThread(threadId = 1, addresses = listOf("+15550100"), ts = 1))
        override suspend fun thread(threadId: Long): LinkMessage.SmsThread? = null
        override suspend fun messages(threadId: Long, beforeTs: Long?, limit: Int): List<LinkMessage.SmsMessage> = emptyList()
        override suspend fun maxId(): Long = 0
        override suspend fun messagesAfter(id: Long): List<LinkMessage.SmsMessage> = emptyList()
        override fun canSend(): Boolean = false
        override suspend fun send(address: String, body: String, sim: Int?): SmsDispatch = SmsDispatch(0, emptyFlow())
    }

    @Test
    fun `an SMS request is answered only on the PC that asked`() = runTest {
        val links = MutableStateFlow<List<CompanionLink>>(emptyList())
        val sms = Sms()
        SmsBridge(links, MutableStateFlow(true), sms, sms, backgroundScope).start()
        runCurrent()

        val office = FakeLink("OFFICE", all)
        val laptop = FakeLink("LAPTOP", all)
        links.value = listOf(office, laptop)
        runCurrent()
        assertEquals("both PCs get the SIM list", 1, office.of<LinkMessage.SimList>().size)
        assertEquals(1, laptop.of<LinkMessage.SimList>().size)

        office.inbound.emit(LinkMessage.SmsThreadsRequest(limit = 10))
        runCurrent()
        assertEquals(1, office.of<LinkMessage.SmsThreads>().size)
        assertTrue(laptop.of<LinkMessage.SmsThreads>().isEmpty())
    }
}
