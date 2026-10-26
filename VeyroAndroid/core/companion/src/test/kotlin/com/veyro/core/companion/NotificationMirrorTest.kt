package com.veyro.core.companion

import com.veyro.core.link.LinkMessage
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [NotificationMirror] gating, initial sync, streaming and request dispatch, all with fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationMirrorTest {

    private class FakeLink(caps: Capabilities) : CompanionLink {
        val caps = MutableStateFlow(caps)
        val inbound = MutableSharedFlow<LinkMessage>(extraBufferCapacity = 16)
        val sent = mutableListOf<LinkMessage>()
        override val peerCapabilities: Flow<Capabilities> get() = caps
        override val incoming: Flow<LinkMessage> get() = inbound
        override suspend fun send(message: LinkMessage) {
            sent += message
        }
        val notifs get() = sent.filterIsInstance<LinkMessage.Notification>()
    }

    private class FakeSource : NotificationSource {
        val availableFlow = MutableStateFlow(true)
        val eventFlow = MutableSharedFlow<NotificationEvent>(extraBufferCapacity = 16)
        val active = mutableListOf<LinkMessage.Notification>()
        override val available: Flow<Boolean> get() = availableFlow
        override val events: Flow<NotificationEvent> get() = eventFlow
        override suspend fun activeNotifications(): List<LinkMessage.Notification> = active.toList()
        override suspend fun isActive(key: String): Boolean = active.any { it.key == key }
    }

    private class FakeExecutor : NotificationActionExecutor {
        val calls = mutableListOf<String>()
        override suspend fun invokeAction(key: String, index: Int) {
            calls += "action:$key:$index"
        }
        override suspend fun reply(key: String, text: String) {
            calls += "reply:$key:$text"
        }
        override suspend fun dismiss(key: String) {
            calls += "dismiss:$key"
        }
    }

    private val mirrorCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.NOTIFICATION_MIRROR)
    private val plainCaps = Capabilities.of(Capability.FILE_TRANSFER)

    private fun notif(key: String) = LinkMessage.Notification(
        key = key,
        kind = LinkMessage.Notification.KIND_POSTED,
        pkg = "com.example.chat",
        title = "t",
    )

    private class Rig(
        val links: MutableStateFlow<CompanionLink?> = MutableStateFlow(null),
        val enabled: MutableStateFlow<Boolean> = MutableStateFlow(true),
        val source: FakeSource = FakeSource(),
        val executor: FakeExecutor = FakeExecutor(),
    )

    private fun TestScope.rig(): Rig {
        val rig = Rig()
        NotificationMirror(rig.links, rig.enabled, rig.source, rig.executor, backgroundScope).start()
        runCurrent()
        return rig
    }

    @Test
    fun `sends the active set with kind active on connect`() = runTest {
        val rig = rig()
        rig.source.active += listOf(notif("a"), notif("b"))
        val link = FakeLink(mirrorCaps)

        rig.links.value = link
        runCurrent()

        assertEquals(listOf("a", "b"), link.notifs.map { it.key })
        assertTrue(link.notifs.all { it.kind == LinkMessage.Notification.KIND_ACTIVE })
    }

    @Test
    fun `streams posted and removed after the initial sync`() = runTest {
        val rig = rig()
        val link = FakeLink(mirrorCaps)
        rig.links.value = link
        runCurrent()

        rig.source.eventFlow.emit(NotificationEvent.Posted(notif("c")))
        rig.source.eventFlow.emit(NotificationEvent.Removed("c"))
        runCurrent()

        assertEquals(
            listOf("c" to LinkMessage.Notification.KIND_POSTED, "c" to LinkMessage.Notification.KIND_REMOVED),
            link.notifs.map { it.key to it.kind },
        )
        // A removed frame carries only the key.
        assertEquals(LinkMessage.Notification("c", LinkMessage.Notification.KIND_REMOVED), link.notifs[1])
    }

    @Test
    fun `sends nothing when the peer lacks the capability bit`() = runTest {
        val rig = rig()
        rig.source.active += notif("a")
        val link = FakeLink(plainCaps)

        rig.links.value = link
        runCurrent()
        rig.source.eventFlow.emit(NotificationEvent.Posted(notif("b")))
        runCurrent()
        assertTrue(link.sent.isEmpty())

        // The bit arriving later (hello after connect) starts mirroring.
        link.caps.value = mirrorCaps
        runCurrent()
        assertEquals(listOf("a"), link.notifs.map { it.key })
    }

    @Test
    fun `sends nothing while disabled, and starts when enabled`() = runTest {
        val rig = rig()
        rig.enabled.value = false
        rig.source.active += notif("a")
        val link = FakeLink(mirrorCaps)

        rig.links.value = link
        runCurrent()
        rig.source.eventFlow.emit(NotificationEvent.Posted(notif("b")))
        runCurrent()
        assertTrue(link.sent.isEmpty())

        rig.enabled.value = true
        runCurrent()
        assertEquals(listOf("a"), link.notifs.map { it.key })

        rig.enabled.value = false
        runCurrent()
        rig.source.eventFlow.emit(NotificationEvent.Posted(notif("c")))
        runCurrent()
        assertEquals(listOf("a"), link.notifs.map { it.key })
    }

    @Test
    fun `ignores requests for unknown keys`() = runTest {
        val rig = rig()
        rig.source.active += notif("a")
        val link = FakeLink(mirrorCaps)
        rig.links.value = link
        runCurrent()

        link.inbound.emit(LinkMessage.NotificationActionRequest("ghost", 0))
        link.inbound.emit(LinkMessage.NotificationReplyRequest("ghost", "hi"))
        link.inbound.emit(LinkMessage.NotificationDismissRequest("ghost"))
        runCurrent()

        assertTrue(rig.executor.calls.isEmpty())
    }

    @Test
    fun `dispatches action, reply and dismiss to the executor`() = runTest {
        val rig = rig()
        rig.source.active += notif("a")
        val link = FakeLink(mirrorCaps)
        rig.links.value = link
        runCurrent()

        link.inbound.emit(LinkMessage.NotificationActionRequest("a", 2))
        link.inbound.emit(LinkMessage.NotificationReplyRequest("a", "Sure, 1pm"))
        link.inbound.emit(LinkMessage.NotificationDismissRequest("a"))
        runCurrent()

        assertEquals(listOf("action:a:2", "reply:a:Sure, 1pm", "dismiss:a"), rig.executor.calls)
    }

    @Test
    fun `rejects an over-long reply`() = runTest {
        val rig = rig()
        rig.source.active += notif("a")
        val link = FakeLink(mirrorCaps)
        rig.links.value = link
        runCurrent()

        link.inbound.emit(
            LinkMessage.NotificationReplyRequest("a", "x".repeat(NotificationMirror.MAX_REPLY_CHARS + 1)),
        )
        runCurrent()

        assertTrue(rig.executor.calls.isEmpty())
    }

    @Test
    fun `requests are not handled when the peer lacks the capability bit`() = runTest {
        val rig = rig()
        rig.source.active += notif("a")
        val link = FakeLink(plainCaps)
        rig.links.value = link
        runCurrent()

        link.inbound.emit(LinkMessage.NotificationDismissRequest("a"))
        runCurrent()

        assertTrue(rig.executor.calls.isEmpty())
    }
}
