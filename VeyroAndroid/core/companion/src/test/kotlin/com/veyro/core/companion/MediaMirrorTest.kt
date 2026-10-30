package com.veyro.core.companion

import com.veyro.core.link.PeerMessage
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

    private companion object {
        const val PC = "PC-ID"
    }

    private class FakeLink(caps: Capabilities, override val peerDeviceId: String = PC) : CompanionLink {
        val caps = MutableStateFlow(caps)
        val inbound = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 16)
        val sent = mutableListOf<PeerMessage>()
        override val peerCapabilities: Flow<Capabilities> get() = caps
        override val incoming: Flow<PeerMessage> get() = inbound
        override suspend fun send(message: PeerMessage) {
            sent += message
        }
        val states get() = sent.filterIsInstance<PeerMessage.MediaState>()
    }

    private class FakeSource : MediaSource {
        val availableFlow = MutableStateFlow(true)
        val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        var current = PeerMessage.MediaState(sessions = emptyList(), volume = 50, muted = false)
        override val available: Flow<Boolean> get() = availableFlow
        override val changes: Flow<Unit> get() = changeFlow
        override suspend fun snapshot(): PeerMessage.MediaState = current
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
        PeerMessage.MediaSessionInfo(id = id, title = title, playing = true, durationMs = 200_000, canSeek = canSeek)

    private fun state(vararg sessions: PeerMessage.MediaSessionInfo) =
        PeerMessage.MediaState(sessions = sessions.toList(), volume = 50, muted = false)

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
        val mirror = MediaMirror(links.asLinkList(), on, source, executor, backgroundScope)
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
        assertNull(rig.mirror.peerMedia.value[PC])
        assertFalse(rig.mirror.sendMediaCommand(PC, PeerMessage.MediaCommand(PeerMessage.MediaCommand.PLAY, "pc")))

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
        assertEquals(state(session("pc")), rig.mirror.peerMedia.value[PC])

        val command = PeerMessage.MediaCommand(PeerMessage.MediaCommand.TOGGLE, "pc")
        assertTrue(rig.mirror.sendMediaCommand(PC, command))
        assertEquals(listOf<PeerMessage>(command), link.sent)

        // Commands from the PC are ignored while not sharing.
        link.inbound.emit(PeerMessage.MediaCommand(PeerMessage.MediaCommand.VOLUME, value = 10))
        runCurrent()
        assertTrue(rig.executor.calls.isEmpty())

        rig.enabled.value = true
        runCurrent()
        assertEquals(listOf(state(session("a"))), link.states)

        // Switching off mid-session clears the PC's view of our sessions.
        rig.enabled.value = false
        runCurrent()
        assertEquals(PeerMessage.MediaState(sessions = emptyList()), link.states.last())
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
    fun `position moving as playback predicts is not resent, a seek or pause is`() = runTest {
        // Players such as YouTube report their position about once a second; each report used
        // to resend the whole snapshot with its artwork (about 17 KB a second on a Redmi K50i).
        val rig = rig()
        fun at(positionMs: Long, ts: Long, playing: Boolean = true) =
            state(session("a").copy(positionMs = positionMs, ts = ts, playing = playing, art = "QUJD"))
        rig.source.current = at(10_000, 1_000_000)
        val link = FakeLink(mediaCaps)
        rig.links.value = link
        runCurrent()
        assertEquals(1, link.states.size)

        // Ten one-second ticks of normal playback: nothing goes out.
        for (i in 1..10) {
            advanceTimeBy(1_000)
            rig.source.current = at(10_000 + i * 1_000L, 1_000_000 + i * 1_000L)
            rig.source.changeFlow.emit(Unit)
            runCurrent()
        }
        assertEquals("playback progress is extrapolated by the PC", 1, link.states.size)

        // A seek (position jumps against the clock) goes out.
        advanceTimeBy(1_000)
        rig.source.current = at(90_000, 1_011_000)
        rig.source.changeFlow.emit(Unit)
        runCurrent()
        assertEquals(2, link.states.size)
        assertEquals(90_000L, link.states.last().sessions.single().positionMs)

        // Pausing goes out.
        advanceTimeBy(1_000)
        rig.source.current = at(91_000, 1_012_000, playing = false)
        rig.source.changeFlow.emit(Unit)
        runCurrent()
        assertEquals(3, link.states.size)

        // While paused the position does not move; a report with a later ts is not a change.
        advanceTimeBy(1_000)
        rig.source.current = at(91_000, 1_013_000, playing = false)
        rig.source.changeFlow.emit(Unit)
        runCurrent()
        assertEquals(3, link.states.size)
    }

    @Test
    fun `significant change rule matches Windows`() {
        fun s(positionMs: Long?, ts: Long?, playing: Boolean = true) =
            state(session("a").copy(positionMs = positionMs, ts = ts, playing = playing))
        assertTrue(MediaMirror.isSignificantChange(null, s(0, 0)))
        assertFalse(MediaMirror.isSignificantChange(s(0, 0), s(5_000, 5_000)))
        assertFalse(MediaMirror.isSignificantChange(s(0, 0), s(6_400, 5_000)))
        assertTrue(MediaMirror.isSignificantChange(s(0, 0), s(6_600, 5_000)))
        assertTrue(MediaMirror.isSignificantChange(s(0, 0), s(null, null)))
        assertTrue(MediaMirror.isSignificantChange(s(0, 0, playing = true), s(0, 0, playing = false)))
        assertTrue(MediaMirror.isSignificantChange(state(session("a")), state(session("a"), session("b"))))
        assertTrue(MediaMirror.isSignificantChange(state(session("a")), state(session("a")).copy(volume = 10)))
    }

    @Test
    fun `commands for unknown sessions are ignored, known ones dispatched`() = runTest {
        val rig = rig()
        rig.source.current = state(session("a"), session("b", canSeek = false))
        val link = FakeLink(mediaCaps)
        rig.links.value = link
        runCurrent()

        link.inbound.emit(PeerMessage.MediaCommand(PeerMessage.MediaCommand.PLAY, "ghost"))
        link.inbound.emit(PeerMessage.MediaCommand(PeerMessage.MediaCommand.PAUSE, null))
        link.inbound.emit(PeerMessage.MediaCommand("explode", "a"))
        link.inbound.emit(PeerMessage.MediaCommand(PeerMessage.MediaCommand.SEEK, "b", 5_000))
        runCurrent()
        assertTrue(rig.executor.calls.isEmpty())

        link.inbound.emit(PeerMessage.MediaCommand(PeerMessage.MediaCommand.NEXT, "a"))
        link.inbound.emit(PeerMessage.MediaCommand(PeerMessage.MediaCommand.SEEK, "a", 999_999))
        link.inbound.emit(PeerMessage.MediaCommand(PeerMessage.MediaCommand.TOGGLE, "b"))
        runCurrent()
        assertEquals(listOf("next:a:null", "seek:a:200000", "toggle:b:null"), rig.executor.calls)
    }

    @Test
    fun `volume is clamped to 0-100 and mute follows the value`() = runTest {
        val rig = rig()
        val link = FakeLink(mediaCaps)
        rig.links.value = link
        runCurrent()

        link.inbound.emit(PeerMessage.MediaCommand(PeerMessage.MediaCommand.VOLUME, value = 150))
        link.inbound.emit(PeerMessage.MediaCommand(PeerMessage.MediaCommand.VOLUME, value = -5))
        link.inbound.emit(PeerMessage.MediaCommand(PeerMessage.MediaCommand.VOLUME, value = 25))
        link.inbound.emit(PeerMessage.MediaCommand(PeerMessage.MediaCommand.VOLUME))
        link.inbound.emit(PeerMessage.MediaCommand(PeerMessage.MediaCommand.MUTE, value = 1))
        link.inbound.emit(PeerMessage.MediaCommand(PeerMessage.MediaCommand.MUTE, value = 0))
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
        assertEquals(state(session("pc")), rig.mirror.peerMedia.value[PC])

        rig.links.value = null
        runCurrent()
        assertNull(rig.mirror.peerMedia.value[PC])
        assertFalse(rig.mirror.sendMediaCommand(PC, PeerMessage.MediaCommand(PeerMessage.MediaCommand.PLAY, "pc")))
    }
}
