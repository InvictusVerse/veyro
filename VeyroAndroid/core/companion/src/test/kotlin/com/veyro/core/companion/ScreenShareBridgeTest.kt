package com.veyro.core.companion

import com.veyro.core.link.PeerMessage
import com.veyro.core.link.StreamAskAudio
import com.veyro.core.link.StreamCaps
import com.veyro.core.link.StreamConfig
import com.veyro.core.link.StreamKeyframe
import com.veyro.core.link.StreamStart
import com.veyro.core.link.StreamState
import com.veyro.core.link.StreamStop
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** [ScreenShareBridge]: caps, consent, one session at a time, the PC's controls and their rate limits (ADR-066). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenShareBridgeTest {

    private class FakeLink(caps: Capabilities) : CompanionLink {
        val caps = MutableStateFlow(caps)
        val inbound = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 16)
        val sent = mutableListOf<PeerMessage>()
        override val peerCapabilities: Flow<Capabilities> get() = caps
        override val incoming: Flow<PeerMessage> get() = inbound
        override val peerDeviceId: String get() = "PC"
        override suspend fun send(message: PeerMessage) {
            sent += message
        }
        inline fun <reified T : PeerMessage> of(): List<T> = sent.filterIsInstance<T>()
    }

    private class FakeSession : ScreenSession {
        override val state = MutableStateFlow(ScreenSessionState())
        val configs = mutableListOf<StreamConfig>()
        var keyframes = 0
        var asks = 0
        val stops = mutableListOf<String>()
        override fun configure(config: StreamConfig) {
            configs += config
        }
        override fun requestKeyframe() {
            keyframes++
        }
        override fun askAudio() {
            asks++
        }
        override fun stop(reason: String) {
            stops += reason
            if (state.value.stopped == null) state.value = state.value.copy(stopped = reason)
        }
    }

    private class FakeGrant(val session: FakeSession, val failure: Exception? = null) : ScreenGrant {
        var released = 0
        override suspend fun start(link: CompanionLink, start: StreamStart): ScreenSession {
            failure?.let { throw it }
            return session
        }
        override fun release() {
            released++
        }
    }

    private class FakeHost : ScreenShareHost {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        var answer = CompletableDeferred<ScreenGrant?>()
        val requests = mutableListOf<Pair<String, Boolean>>()
        var withdrawn = 0
        override suspend fun caps(allowed: Boolean, cameraAllowed: Boolean): StreamCaps =
            StreamCaps(allowed = allowed, audio = allowed, cameraAllowed = cameraAllowed)
        override val capsChanges: Flow<Unit> get() = changes
        val cameraRequests = mutableListOf<String>()
        override suspend fun requestCameraConsent(sid: String, pcName: String): ScreenGrant? {
            cameraRequests += sid
            return answer.await()
        }
        override suspend fun requestConsent(sid: String, pcName: String, audio: Boolean): ScreenGrant? {
            requests += sid to audio
            try {
                return answer.await()
            } catch (c: CancellationException) {
                withdrawn++
                throw c
            }
        }
    }

    private class Rig(
        val links: MutableStateFlow<List<CompanionLink>>,
        val allowed: MutableStateFlow<Boolean>,
        val host: FakeHost,
        val camera: MutableStateFlow<Boolean>,
    )

    private val viewCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.SCREEN_VIEW)

    private fun TestScope.rig(allowed: Boolean = true, camera: Boolean = true): Rig {
        val links = MutableStateFlow<List<CompanionLink>>(emptyList())
        val on = MutableStateFlow(allowed)
        val cameraOn = MutableStateFlow(camera)
        val host = FakeHost()
        ScreenShareBridge(links, on, host, { "Office PC" }, backgroundScope, clock = { testScheduler.currentTime }, cameraAllowed = cameraOn).start()
        runCurrent()
        return Rig(links, on, host, cameraOn)
    }

    private fun cameraStart(sid: String = SID) = start(sid, audio = false).copy(source = "camera", camera = "0", w = 1280, h = 720, maxLong = 1280, fps = 30)

    @Test
    fun `camera requests ask for the camera, follow their own switch, and keep the screen apart`() = runTest {
        val rig = rig(camera = false)
        val pc = connect(rig)
        receive(pc, cameraStart())
        assertEquals(listOf(StreamStop(SID, "disabled")), pc.of<StreamStop>())
        assertTrue(rig.host.cameraRequests.isEmpty())

        rig.camera.value = true
        runCurrent()
        assertEquals("caps follow the camera switch", listOf(false, true), pc.of<StreamCaps>().map { it.cameraAllowed == true })
        val session = FakeSession()
        receive(pc, cameraStart(SID2))
        assertEquals(listOf(SID2), rig.host.cameraRequests)
        assertTrue("never the screen consent", rig.host.requests.isEmpty())
        rig.host.answer.complete(FakeGrant(session))
        runCurrent()
        session.state.value = ScreenSessionState(streaming = true, camera = "0")
        runCurrent()
        assertEquals("0", pc.of<StreamState>().last().camera)

        rig.allowed.value = false
        runCurrent()
        assertTrue("turning screen sharing off leaves the camera running", session.stops.isEmpty())
        rig.camera.value = false
        runCurrent()
        assertEquals("disabled", session.stops.first())
    }

    @Test
    fun `a camera start needs a camera id and a size, and never sound`() = runTest {
        val rig = rig()
        val pc = connect(rig)
        receive(pc, cameraStart().copy(camera = null))
        receive(pc, cameraStart().copy(w = null, h = null))
        receive(pc, cameraStart().copy(audio = true))
        receive(pc, cameraStart().copy(camera = "../0"))
        receive(pc, start().copy(camera = "0"))
        assertTrue(rig.host.cameraRequests.isEmpty() && rig.host.requests.isEmpty())
    }

    private fun TestScope.connect(rig: Rig, caps: Capabilities = viewCaps): FakeLink {
        val link = FakeLink(caps)
        rig.links.value = rig.links.value + link
        runCurrent()
        return link
    }

    private suspend fun TestScope.receive(link: FakeLink, message: PeerMessage) {
        link.inbound.emit(message)
        runCurrent()
    }

    private fun start(sid: String = SID, audio: Boolean = true) = StreamStart(
        sid = sid, source = "screen", codec = "h264", hw = true, maxLong = 1920, fps = 60,
        bitrate = 8_000_000, audio = audio, host = "192.168.31.191", port = 5151,
    )

    /** Starts a session and lets the user allow it; returns the running session. */
    private suspend fun TestScope.streaming(rig: Rig, link: FakeLink, audio: Boolean = true): FakeSession {
        val session = FakeSession()
        receive(link, start(audio = audio))
        rig.host.answer.complete(FakeGrant(session))
        runCurrent()
        session.state.value = ScreenSessionState(streaming = true, codec = "h264", w = 832, h = 1920, fps = 60, bitrate = 6_000_000, hw = true)
        runCurrent()
        return session
    }

    @Test
    fun `caps go only to a PC that can view, and follow the switch`() = runTest {
        val rig = rig()
        val other = connect(rig, Capabilities.of(Capability.FILE_TRANSFER))
        val pc = connect(rig)
        assertTrue(other.sent.isEmpty())
        assertEquals(listOf(true), pc.of<StreamCaps>().map { it.allowed })
        rig.allowed.value = false
        runCurrent()
        assertEquals(listOf(true, false), pc.of<StreamCaps>().map { it.allowed })
        rig.host.changes.emit(Unit)
        runCurrent()
        assertEquals(3, pc.of<StreamCaps>().size)
    }

    @Test
    fun `allowed, the session is asked for, started and streamed`() = runTest {
        val rig = rig()
        val pc = connect(rig)
        val session = streaming(rig, pc)
        assertEquals(listOf(SID to true), rig.host.requests)
        val states = pc.of<StreamState>()
        assertEquals(listOf("asking", "starting", "streaming"), states.map { it.state })
        val live = states.last()
        assertEquals(listOf("h264", 832, 1920, 60, 6_000_000, true), listOf(live.codec, live.w, live.h, live.fps, live.bitrate, live.hw))
        assertEquals(false, live.audio)

        receive(pc, StreamConfig(SID, bitrate = 4_000_000))
        receive(pc, StreamConfig("BBBBBBBBBBBBBBBBBBBBBA", bitrate = 1_000_000))
        assertEquals(listOf(4_000_000), session.configs.map { it.bitrate })

        session.state.value = session.state.value.copy(paused = true)
        runCurrent()
        assertEquals("paused", pc.of<StreamState>().last().state)
    }

    @Test
    fun `declined and unanswered requests end with their reason`() = runTest {
        val rig = rig()
        val pc = connect(rig)
        receive(pc, start())
        rig.host.answer.complete(null)
        runCurrent()
        assertEquals(listOf(StreamStop(SID, "declined")), pc.of<StreamStop>())

        rig.host.answer = CompletableDeferred()
        receive(pc, start(SID2))
        advanceTimeBy(ScreenShareBridge.CONSENT_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(StreamStop(SID2, "timeout"), pc.of<StreamStop>().last())
        assertEquals(1, rig.host.withdrawn)
    }

    @Test
    fun `one session at a time across PCs, and nothing with the switch off`() = runTest {
        val rig = rig()
        val first = connect(rig)
        val second = connect(rig)
        receive(first, start())
        receive(second, start(SID2))
        assertEquals(listOf(StreamStop(SID2, "busy")), second.of<StreamStop>())

        val off = rig(allowed = false)
        val pc = connect(off)
        receive(pc, start())
        assertEquals(listOf(StreamStop(SID, "disabled")), pc.of<StreamStop>())
        assertTrue(off.host.requests.isEmpty())
    }

    @Test
    fun `a broken streamStart is dropped`() = runTest {
        val rig = rig()
        val pc = connect(rig)
        receive(pc, start().copy(sid = "short"))
        receive(pc, start().copy(host = "8.8.8.8"))
        assertTrue(pc.of<StreamState>().isEmpty() && pc.of<StreamStop>().isEmpty())
        assertTrue(rig.host.requests.isEmpty())
    }

    @Test
    fun `the PC's stop ends the session without an echo, the phone's stop is reported`() = runTest {
        val rig = rig()
        val pc = connect(rig)
        val session = streaming(rig, pc)
        receive(pc, StreamStop(SID, "user"))
        assertEquals(listOf("user"), session.stops.distinct())
        assertTrue(pc.of<StreamStop>().isEmpty())

        rig.host.answer = CompletableDeferred()
        val next = FakeSession()
        receive(pc, start(SID2))
        rig.host.answer.complete(FakeGrant(next))
        runCurrent()
        next.state.value = ScreenSessionState(streaming = true)
        runCurrent()
        next.stop("projection-ended")
        runCurrent()
        assertEquals(listOf(StreamStop(SID2, "projection-ended")), pc.of<StreamStop>())
    }

    @Test
    fun `the switch turned off or the PC leaving ends a running session`() = runTest {
        val rig = rig()
        val pc = connect(rig)
        val session = streaming(rig, pc)
        rig.allowed.value = false
        runCurrent()
        assertEquals("disabled", session.stops.first())
        assertEquals(StreamStop(SID, "disabled"), pc.of<StreamStop>().last())

        rig.allowed.value = true
        rig.host.answer = CompletableDeferred()
        runCurrent()
        val next = streaming(rig, pc)
        val sentBefore = pc.sent.size
        rig.links.value = emptyList()
        runCurrent()
        assertEquals("session-closed", next.stops.first())
        assertEquals(sentBefore, pc.sent.size)
    }

    @Test
    fun `keyframes and Ask again for the sound are rate-limited`() = runTest {
        val rig = rig()
        val pc = connect(rig)
        val session = streaming(rig, pc)
        receive(pc, StreamKeyframe(SID))
        receive(pc, StreamKeyframe(SID))
        assertEquals(1, session.keyframes)
        advanceTimeBy(ScreenShareBridge.KEYFRAME_INTERVAL_MS)
        receive(pc, StreamKeyframe(SID))
        assertEquals(2, session.keyframes)

        receive(pc, StreamAskAudio(SID))
        receive(pc, StreamAskAudio(SID))
        assertEquals(1, session.asks)
        advanceTimeBy(3_000)
        receive(pc, StreamAskAudio(SID))
        assertEquals(2, session.asks)

        session.state.value = session.state.value.copy(audio = true)
        runCurrent()
        advanceTimeBy(3_000)
        receive(pc, StreamAskAudio(SID))
        assertEquals("already playing: nothing to ask", 2, session.asks)
    }

    @Test
    fun `a socket that cannot be opened ends with network`() = runTest {
        val rig = rig()
        val pc = connect(rig)
        receive(pc, start())
        rig.host.answer.complete(FakeGrant(FakeSession(), IOException("unreachable")))
        runCurrent()
        assertEquals(listOf(StreamStop(SID, "network")), pc.of<StreamStop>())
    }

    private companion object {
        const val SID = "EBESExQVFhcYGRobHB0eHw"
        const val SID2 = "AAECAwQFBgcICQoLDA0ODw"
    }
}
