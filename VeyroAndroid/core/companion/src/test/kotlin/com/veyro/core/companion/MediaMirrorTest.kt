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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MediaMirror] gating, snapshot on connect, rate limiting and command dispatch, with fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaMirrorTest {

    private class FakeLink(caps: Capabilities) : CompanionLink {
        val caps = MutableStateFlow(caps)
        val inbound = MutableSharedFlow<LinkMessage>(extraBufferCapacity = 16)
        val sent = mutableListOf<LinkMessage>()
        override val peerCapabilities: Flow<Capabilities> get() = caps
        override val incoming: Flow<LinkMessage> get() = inbound
        override suspend fun send(message: LinkMessage) {
            sent += message
        }
        val states get() = sent.filterIsInstance<LinkMessage.MediaState>()
    }

    private class FakeSource : MediaSource {
        val availableFlow = MutableStateFlow(true)
        val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        var current = LinkMessage.MediaState(sessions = emptyList(), volume = 50, muted = false)
        override val available: Flow<Boolean> get() = availableFlow
        override val changes: Flow<Unit> get() = changeFlow
        override suspend fun snapshot(): LinkMessage.MediaState = current
    }

    private class FakeExecutor : MediaCommandExecutor {
        val calls = mutableListOf<String>()
        override suspend fun transport(id: String, action: String, positionMs: Long?) {
            calls += "$action:$id:$positionMs"
        }
        override suspend fun setVolume(percent: Int) {
            calls += "volume:$percent"
        }
        override suspend fun setMuted(muted: Boolean) {
            calls += "mute:$muted"
        }
    }

    private val mediaCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.MEDIA_CONTROL)
    private val plainCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.NOTIFICATION_MIRROR)

    private fun session(id: String, title: String = "t", canSeek: Boolean = true) =
        LinkMessage.MediaSessionInfo(id = id, title = title, playing = true, durationMs = 200_000, canSeek = canSeek)

    private fun state(vararg sessions: LinkMessage.MediaSessionInfo) =
        LinkMessage.MediaState(sessions = sessions.toList(), volume = 50, muted = false)

    private class Rig(
        val mirror: MediaMirror,
        val links: MutableStateFlow<CompanionLink?>,
        val enabled: MutableStateFlow<Boolean>,
        val source: FakeSource,
        val executor: FakeExecutor,
    )

    private fun TestScope.rig(enabled: Boolean = true): Rig {
        val links = MutableStateFlow<CompanionLink?>(null)
        val on = MutableStateFlow(enabled)
        val source = FakeSource()
        val executor = FakeExecutor()
        val mirror = MediaMirror(links, on, source, executor, backgroundScope)
        mirror.start()
        runCurrent()
        return Rig(mirror, links, on, source, executor)
    }

    @Test
    fun `sends a full snapshot on connect`() = runTest {
        val rig = rig()
        rig.source.current = state(session("a"))
        val link = FakeLink(mediaCaps)

        rig.links.value = link
        runCurrent()

        assertEquals(listOf(state(session("a"))), link.states)
    }

    @Test
    fun `nothing is sent or received without the capability bit`() = runTest {
        val rig = rig()
        rig.source.current = state(session("a"))
        val link = FakeLink(plainCaps)
        rig.links.value = link
        runCurrent()

        link.inbound.emit(state(session("pc")))
        rig.source.changeFlow.emit(Unit)
        runCurrent()
        assertTrue(link.sent.isEmpty())
        assertNull(rig.mirror.peerMedia.value)
        assertFalse(rig.mirror.sendMediaCommand(LinkMessage.MediaCommand(LinkMessage.MediaCommand.PLAY, "pc")))

        // The bit arriving later (hello after connect) starts it.
        link.caps.value = mediaCaps
        runCurrent()
        assertEquals(listOf(state(session("a"))), link.states)
    }

    @Test
    fun `phone media is not shared until opted in, but PC media is still received`() = runTest {
        val rig = rig(enabled = false)
        rig.source.current = state(session("a"))
        val link = FakeLink(mediaCaps)
        rig.links.value = link
        runCurrent()

        assertTrue(link.sent.isEmpty())
        link.inbound.emit(state(session("pc")))
        runCurrent()
        assertEquals(state(session("pc")), rig.mirror.peerMedia.value)

        val command = LinkMessage.MediaCommand(LinkMessage.MediaCommand.TOGGLE, "pc")
        assertTrue(rig.mirror.sendMediaCommand(command))
        assertEquals(listOf<LinkMessage>(command), link.sent)

        // Commands from the PC are ignored while not sharing.
        link.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.VOLUME, value = 10))
        runCurrent()
        assertTrue(rig.executor.calls.isEmpty())

        rig.enabled.value = true
        runCurrent()
        assertEquals(listOf(state(session("a"))), link.states)

        // Switching off mid-session clears the PC's view of our sessions.
        rig.enabled.value = false
        runCurrent()
        assertEquals(LinkMessage.MediaState(sessions = emptyList()), link.states.last())
    }

    @Test
    fun `nothing is shared while media sessions are unreadable`() = runTest {
        val rig = rig()
        rig.source.availableFlow.value = false
        val link = FakeLink(mediaCaps)
        rig.links.value = link
        runCurrent()
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun `changes inside the rate-limit window coalesce into one send`() = runTest {
        val rig = rig()
        val link = FakeLink(mediaCaps)
        rig.links.value = link
        runCurrent()
        assertEquals(1, link.states.size)

        for (i in 1..3) {
            advanceTimeBy(100)
            rig.source.current = state(session("a", title = "t$i"))
            rig.source.changeFlow.emit(Unit)
            runCurrent()
        }
        assertEquals("no send inside the window", 1, link.states.size)

        advanceTimeBy(MediaMirror.MIN_INTERVAL_MS)
        runCurrent()
        assertEquals(2, link.states.size)
        assertEquals("t3", link.states.last().sessions.single().title)

        // A change after a quiet period goes out at once.
        advanceTimeBy(2_000)
        rig.source.current = state(session("a", title = "t4"))
        rig.source.changeFlow.emit(Unit)
        runCurrent()
        assertEquals(3, link.states.size)

        // An unchanged snapshot is not resent.
        advanceTimeBy(2_000)
        rig.source.changeFlow.emit(Unit)
        runCurrent()
        assertEquals(3, link.states.size)
    }

    @Test
    fun `commands for unknown sessions are ignored, known ones dispatched`() = runTest {
        val rig = rig()
        rig.source.current = state(session("a"), session("b", canSeek = false))
        val link = FakeLink(mediaCaps)
        rig.links.value = link
        runCurrent()

        link.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.PLAY, "ghost"))
        link.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.PAUSE, null))
        link.inbound.emit(LinkMessage.MediaCommand("explode", "a"))
        link.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.SEEK, "b", 5_000))
        runCurrent()
        assertTrue(rig.executor.calls.isEmpty())

        link.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.NEXT, "a"))
        link.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.SEEK, "a", 999_999))
        link.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.TOGGLE, "b"))
        runCurrent()
        assertEquals(listOf("next:a:null", "seek:a:200000", "toggle:b:null"), rig.executor.calls)
    }

    @Test
    fun `volume is clamped to 0-100 and mute follows the value`() = runTest {
        val rig = rig()
        val link = FakeLink(mediaCaps)
        rig.links.value = link
        runCurrent()

        link.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.VOLUME, value = 150))
        link.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.VOLUME, value = -5))
        link.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.VOLUME, value = 25))
        link.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.VOLUME))
        link.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.MUTE, value = 1))
        link.inbound.emit(LinkMessage.MediaCommand(LinkMessage.MediaCommand.MUTE, value = 0))
        runCurrent()

        assertEquals(listOf("volume:100", "volume:0", "volume:25", "mute:true", "mute:false"), rig.executor.calls)
    }

    @Test
    fun `PC media clears on disconnect`() = runTest {
        val rig = rig()
        val link = FakeLink(mediaCaps)
        rig.links.value = link
        runCurrent()
        link.inbound.emit(state(session("pc")))
        runCurrent()
        assertEquals(state(session("pc")), rig.mirror.peerMedia.value)

        rig.links.value = null
        runCurrent()
        assertNull(rig.mirror.peerMedia.value)
        assertFalse(rig.mirror.sendMediaCommand(LinkMessage.MediaCommand(LinkMessage.MediaCommand.PLAY, "pc")))
    }
}
