package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.crypto.X25519
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.model.TrustedPeer
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.util.Base64
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = testDispatcher
        override val io = testDispatcher
        override val main = testDispatcher
    }

    private val localPk = ByteArray(32) { 1 }
    private val localId = DeviceId.fromPublicKey(localPk).value
    private val peer1 = DeviceId.fromPublicKey(ByteArray(32) { 3 }).value

    private fun testManager(
        client: ConnectionClient,
        trusted: TrustedPeer? = null,
        saveTrustedPeer: suspend (TrustedPeer) -> Unit = {},
        localPublicKey: ByteArray = localPk,
        localDeviceId: String = localId,
    ): ConnectionManager = ConnectionManager(
        dispatchers = dispatchers,
        scope = CoroutineScope(testDispatcher),
        localDeviceId = { localDeviceId },
        localDeviceName = { "Local Device" },
        localPublicKey = { localPublicKey },
        trustedPeer = { trusted },
        saveTrustedPeer = saveTrustedPeer,
        client = client,
    )

    @Test
    fun `connect returns false and sets event when endpoints are empty`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient())
        val ok = manager.connect(peer1, "Peer Device", emptyList())
        assertFalse(ok)
        val event = manager.events.value
        assertTrue(event is ConnectionManager.ConnectionEvent.Failed)
        assertEquals("No address for Peer Device", (event as ConnectionManager.ConnectionEvent.Failed).reason)
    }

    @Test
    fun `connect fails when client throws ConnectException on all endpoints`() = runTest(testDispatcher) {
        val failingClient = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                throw ConnectException("Connection refused")
            }
        }
        val manager = testManager(failingClient)
        val endpoints = listOf(Endpoint("192.168.31.47", 36737))
        val ok = manager.connect(peer1, "Remote Device", endpoints)

        assertFalse(ok)
        val event = manager.events.value
        assertTrue(event is ConnectionManager.ConnectionEvent.Failed)
        assertEquals("Could not reach Remote Device", (event as ConnectionManager.ConnectionEvent.Failed).reason)
    }

    @Test
    fun `dialAny does not swallow coroutine cancellation`() = runTest(testDispatcher) {
        val cancellingClient = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                throw CancellationException("Operation cancelled")
            }
        }
        val manager = testManager(cancellingClient)
        val endpoints = listOf(Endpoint("192.168.31.47", 36737))

        var thrown = false
        try {
            manager.connect(peer1, "Remote Device", endpoints)
        } catch (e: CancellationException) {
            thrown = true
        }
        assertTrue("CancellationException must be rethrown and not swallowed", thrown)
    }

    @Test
    fun `connect fails when remote peer disconnects prematurely before ack`() = runTest(testDispatcher) {
        val fakeSession = FakeDefaultWebSocketSession()
        fakeSession.incomingChannel.close() // Remote peer closes immediately

        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = fakeSession
        }
        val manager = testManager(client)
        val endpoints = listOf(Endpoint("192.168.31.47", 36737))
        val ok = manager.connect(peer1, "Remote Device", endpoints)

        assertFalse(ok)
        val event = manager.events.value
        assertTrue(event is ConnectionManager.ConnectionEvent.Failed)
        assertEquals("Remote Device did not answer", (event as ConnectionManager.ConnectionEvent.Failed).reason)
    }

    @Test
    fun `connect fails when remote peer rejects with busy mode`() = runTest(testDispatcher) {
        val fakeSession = FakeDefaultWebSocketSession()
        fakeSession.incomingChannel.send(Frame.Text("""{"mode":"busy","reason":"Device is busy in another transfer"}"""))

        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = fakeSession
        }
        val manager = testManager(client)
        val endpoints = listOf(Endpoint("192.168.31.47", 36737))
        val ok = manager.connect(peer1, "Remote Device", endpoints)

        assertFalse(ok)
        val event = manager.events.value
        assertTrue(event is ConnectionManager.ConnectionEvent.Failed)
        assertEquals("Device is busy in another transfer", (event as ConnectionManager.ConnectionEvent.Failed).reason)
    }

    @Test
    fun `connect fails when remote peer sends malformed ack`() = runTest(testDispatcher) {
        val fakeSession = FakeDefaultWebSocketSession()
        fakeSession.incomingChannel.send(Frame.Text("NOT_VALID_JSON"))

        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = fakeSession
        }
        val manager = testManager(client)
        val endpoints = listOf(Endpoint("192.168.31.47", 36737))
        val ok = manager.connect(peer1, "Remote Device", endpoints)

        assertFalse(ok)
        val event = manager.events.value
        assertTrue(event is ConnectionManager.ConnectionEvent.Failed)
        assertEquals("Remote Device did not answer", (event as ConnectionManager.ConnectionEvent.Failed).reason)
    }

    @Test
    fun `connect fails when remote peer sends oversized ack`() = runTest(testDispatcher) {
        val fakeSession = FakeDefaultWebSocketSession()
        // Frame larger than MAX_PREAMBLE_BYTES (64 KB)
        val bigPayload = "A".repeat(70_000)
        fakeSession.incomingChannel.send(Frame.Text(bigPayload))

        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = fakeSession
        }
        val manager = testManager(client)
        val endpoints = listOf(Endpoint("192.168.31.47", 36737))
        val ok = manager.connect(peer1, "Remote Device", endpoints)

        assertFalse(ok)
        val event = manager.events.value
        assertTrue(event is ConnectionManager.ConnectionEvent.Failed)
        assertEquals("Remote Device did not answer", (event as ConnectionManager.ConnectionEvent.Failed).reason)
    }

    @Test
    fun `connect succeeds in session mode when peer is trusted`() = runTest(testDispatcher) {
        val fakeSession = FakeDefaultWebSocketSession()
        val peer = V3TestPeer("00000000000000000000000000")
        launch { fakeSession.incomingChannel.send(peer.answerSession(fakeSession.outgoingChannel.receive(), ByteArray(32))) }

        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = fakeSession
        }
        val trusted = TrustedPeer(
            deviceId = com.veyro.core.model.DeviceId.parseOrNull("00000000000000000000000000")
                ?: error("valid id"),
            staticPublicKey = ByteArray(32),
            pairingSecret = ByteArray(32),
            name = "Remote Device",
            platform = com.veyro.core.model.Platform.ANDROID,
            pairedAtEpochMillis = 1000L,
        )
        val manager = testManager(client, trusted = trusted)
        val endpoints = listOf(Endpoint("192.168.31.47", 36737))
        val ok = manager.connect("00000000000000000000000000", "Remote Device", endpoints)

        assertTrue(ok)
    }

    // ── A second session from the same peer (ADR-035) ─────────────────────────────────────
    //
    // These replace the old "superseded-by-inbound" rule, under which a PC dialling a separate
    // connection for a transfer closed the phone's companion session. A trusted peer's second
    // session now runs alongside the first; only an untrusted one still replaces it.

    private val officePc: TrustedPeer = run {
        val pk = ByteArray(32) { 5 }
        TrustedPeer(
            deviceId = DeviceId.fromPublicKey(pk),
            staticPublicKey = pk,
            pairingSecret = ByteArray(32) { 9 },
            name = "Office PC",
            platform = com.veyro.core.model.Platform.WINDOWS,
            pairedAtEpochMillis = 1L,
        )
    }

    /** The first ack each fake dialler read, so tests can check it after the handshake consumed it. */
    private val acks = java.util.concurrent.ConcurrentHashMap<FakeDefaultWebSocketSession, LinkPreambleAck>()

    /**
     * A v3 dialler on [ws] (ADR-058): preamble with fresh cn/epk, then, on a session ack, its first
     * frame (hello) sealed under this connection's keys, built from [secret].
     */
    private suspend fun dialIn(
        manager: ConnectionManager,
        ws: FakeDefaultWebSocketSession,
        id: String,
        secret: ByteArray? = listOf(officePc, homePc).firstOrNull { it.deviceId.value == id }?.pairingSecret,
    ) {
        val peer = V3TestPeer(id)
        ws.incomingChannel.send(peer.preamble())
        kotlinx.coroutines.coroutineScope {
            launch {
                val ack = peer.readAck(ws.outgoingChannel.receive(), localId, secret)
                acks[ws] = ack
                if (ack.mode == LinkPreambleAck.MODE_SESSION && secret != null) {
                    ws.incomingChannel.send(peer.seal(PeerMessage.Hello(id, "Office PC", "windows")))
                }
            }
            manager.handleInbound(ws)
        }
    }

    @Test
    fun `second inbound session from a connected trusted peer keeps the companion session alive`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient(), trusted = officePc)
        val driven = mutableListOf<PeerConnection>()
        manager.sessionDriver = { connection, _ -> driven += connection }

        val companionWs = FakeDefaultWebSocketSession()
        val companionJob = launch { dialIn(manager, companionWs, officePc.deviceId.value) }
        val companion = manager.activeConnection.value
        assertNotNull(companion)

        val transferWs = FakeDefaultWebSocketSession()
        val transferJob = launch { dialIn(manager, transferWs, officePc.deviceId.value) }

        val ack = firstAck(transferWs)
        assertEquals("the transfer connection is admitted as a session", LinkPreambleAck.MODE_SESSION, ack.mode)
        assertTrue("the companion session must not be closed by the second one", companion!!.isConnected)
        assertSame("companion features stay on the first session", companion, manager.activeConnection.value)
        assertEquals("each session gets exactly one receive driver", 2, driven.size)
        assertTrue(driven[1] !== companion)

        // The transfer connection ends: the companion session is untouched and nothing reports a disconnect.
        transferWs.incomingChannel.close()
        transferJob.join()
        assertTrue(companion.isConnected)
        assertSame(companion, manager.activeConnection.value)
        assertTrue(manager.events.value is ConnectionManager.ConnectionEvent.Connected)

        companionWs.incomingChannel.close()
        companionJob.join()
        assertNull(manager.activeConnection.value)
        assertEquals(ConnectionManager.ConnectionEvent.Disconnected, manager.events.value)
    }

    @Test
    fun `a device the user disconnected is refused when it dials in, and admitted once connected again`() = runTest(testDispatcher) {
        var disconnected = true
        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { localId },
            localDeviceName = { "Local Device" },
            localPublicKey = { localPk },
            trustedPeer = { officePc },
            saveTrustedPeer = {},
            client = ConnectionClient(),
            disconnectedByUser = { disconnected },
        )
        fun ackOf(ws: FakeDefaultWebSocketSession) = firstAck(ws)

        // ADR-053: the PC dialling back after a Disconnect gets "refused", so it stops retrying.
        val refused = FakeDefaultWebSocketSession()
        dialIn(manager, refused, officePc.deviceId.value)
        val ack = ackOf(refused)
        assertEquals(LinkPreambleAck.MODE_REFUSED, ack.mode)
        assertEquals("disconnected-by-user", ack.reason)
        assertTrue("no session was opened", manager.connections.value.isEmpty())
        assertNull(manager.activeConnection.value)

        disconnected = false
        val admitted = FakeDefaultWebSocketSession()
        val job = launch { dialIn(manager, admitted, officePc.deviceId.value) }
        assertEquals(LinkPreambleAck.MODE_SESSION, ackOf(admitted).mode)
        assertNotNull(manager.activeConnection.value)
        admitted.incomingChannel.close()
        job.join()
    }

    @Test
    fun `when the first session drops a live second session from the same peer is promoted`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient(), trusted = officePc)
        val firstWs = FakeDefaultWebSocketSession()
        val firstJob = launch { dialIn(manager, firstWs, officePc.deviceId.value) }
        val first = manager.activeConnection.value
        val secondWs = FakeDefaultWebSocketSession()
        val secondJob = launch { dialIn(manager, secondWs, officePc.deviceId.value) }
        assertSame(first, manager.activeConnection.value)

        // A PC that restarted redials; its old session then dies. The redial must carry on.
        firstWs.incomingChannel.close()
        firstJob.join()
        val promoted = manager.activeConnection.value
        assertNotNull(promoted)
        assertTrue(promoted !== first)
        assertTrue(promoted!!.isConnected)
        assertTrue("no disconnect while the peer is still connected", manager.events.value !is ConnectionManager.ConnectionEvent.Disconnected)

        secondWs.incomingChannel.close()
        secondJob.join()
        assertNull(manager.activeConnection.value)
    }

    @Test
    fun `an untrusted peer reconnecting still replaces its old session`() = runTest(testDispatcher) {
        var trusted: TrustedPeer? = officePc
        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { localId },
            localDeviceName = { "Local Device" },
            localPublicKey = { localPk },
            trustedPeer = { trusted },
            saveTrustedPeer = {},
            client = ConnectionClient(),
        )
        val firstWs = FakeDefaultWebSocketSession()
        val firstJob = launch { dialIn(manager, firstWs, officePc.deviceId.value) }
        val first = manager.activeConnection.value
        assertNotNull(first)

        trusted = null // revoked while connected
        val secondWs = FakeDefaultWebSocketSession()
        val secondJob = launch { dialIn(manager, secondWs, officePc.deviceId.value) }
        firstJob.join()
        assertFalse("a revoked peer's old session has no standing", first!!.isConnected)

        manager.respondToInbound(false)
        secondJob.join()
    }

    // ── Several PCs at once (multiple PCs) ─────────────────────────────────────────────────
    //
    // One live session per paired peer. A second PC is no longer answered busy/already-connected,
    // and one PC leaving never takes the other's session with it.

    private val homePc: TrustedPeer = run {
        val pk = ByteArray(32) { 6 }
        TrustedPeer(
            deviceId = DeviceId.fromPublicKey(pk),
            staticPublicKey = pk,
            pairingSecret = ByteArray(32) { 8 },
            name = "Home PC",
            platform = com.veyro.core.model.Platform.WINDOWS,
            pairedAtEpochMillis = 2L,
        )
    }

    private fun twoPcManager(client: ConnectionClient = ConnectionClient()): ConnectionManager = ConnectionManager(
        dispatchers = dispatchers,
        scope = CoroutineScope(testDispatcher),
        localDeviceId = { localId },
        localDeviceName = { "Local Device" },
        localPublicKey = { localPk },
        trustedPeer = { id -> listOf(officePc, homePc).firstOrNull { it.deviceId.value == id } },
        saveTrustedPeer = {},
        client = client,
    )

    private fun firstAck(ws: FakeDefaultWebSocketSession): LinkPreambleAck = acks[ws] ?: PeerMessageJson.codec.decodeFromString(
        LinkPreambleAck.serializer(),
        (ws.outgoingChannel.tryReceive().getOrNull() as Frame.Text).readText(),
    )

    @Test
    fun `two PCs are connected at once, and closing one leaves the other`() = runTest(testDispatcher) {
        val manager = twoPcManager()
        val driven = mutableListOf<PeerConnection>()
        manager.sessionDriver = { connection, _ -> driven += connection }
        val officeId = officePc.deviceId.value
        val homeId = homePc.deviceId.value

        val officeWs = FakeDefaultWebSocketSession()
        val officeJob = launch { dialIn(manager, officeWs, officeId) }
        val homeWs = FakeDefaultWebSocketSession()
        val homeJob = launch { dialIn(manager, homeWs, homeId) }

        assertEquals(LinkPreambleAck.MODE_SESSION, firstAck(officeWs).mode)
        assertEquals("a second PC is admitted, not answered busy", LinkPreambleAck.MODE_SESSION, firstAck(homeWs).mode)
        assertEquals(listOf(officeId, homeId), manager.connections.value.keys.toList())
        val office = manager.connections.value.getValue(officeId)
        val home = manager.connections.value.getValue(homeId)
        assertTrue(office.isConnected && home.isConnected)
        assertEquals("each session gets its own receive driver", 2, driven.size)
        assertSame("the most recent PC is selected by default", home, manager.activeConnection.value)

        manager.selectPeer(officeId)
        assertSame("the selection follows Home's switcher", office, manager.activeConnection.value)
        assertSame(home, manager.connectionFor(homeId))

        officeWs.incomingChannel.close()
        officeJob.join()
        assertEquals("only the closed PC leaves", listOf(homeId), manager.connections.value.keys.toList())
        assertTrue(home.isConnected)
        assertSame("the selection falls back to the PC still connected", home, manager.activeConnection.value)
        assertNull(manager.connectionFor(officeId))

        homeWs.incomingChannel.close()
        homeJob.join()
        assertTrue(manager.connections.value.isEmpty())
        assertNull(manager.activeConnection.value)
    }

    @Test
    fun `dialling a second PC while another is connected opens a second session`() = runTest(testDispatcher) {
        val homeWs = FakeDefaultWebSocketSession()
        val homeSide = V3TestPeer(homePc.deviceId.value)
        launch {
            homeWs.incomingChannel.send(homeSide.answerSession(homeWs.outgoingChannel.receive(), homePc.pairingSecret))
            homeWs.incomingChannel.send(homeSide.seal(PeerMessage.HelloAck(homePc.deviceId.value, "Home PC", "windows")))
        }
        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = homeWs
        }
        val manager = twoPcManager(client)
        val officeWs = FakeDefaultWebSocketSession()
        val officeJob = launch { dialIn(manager, officeWs, officePc.deviceId.value) }

        val ok = manager.connect(homePc.deviceId.value, "Home PC", listOf(Endpoint("192.168.1.30", 5150)))

        assertTrue("no 'Already connected to a peer' any more", ok)
        assertEquals(setOf(officePc.deviceId.value, homePc.deviceId.value), manager.connections.value.keys)
        assertTrue(manager.connections.value.getValue(officePc.deviceId.value).isConnected)

        // Disconnecting one PC closes only its session.
        manager.disconnect(homePc.deviceId.value)
        homeWs.incomingChannel.close()
        assertEquals(setOf(officePc.deviceId.value), manager.connections.value.keys)

        officeWs.incomingChannel.close()
        officeJob.join()
    }

    @Test
    fun `a paired PC opens its session while another device's pairing is in progress`() = runTest(testDispatcher) {
        val manager = twoPcManager()
        val strangerWs = FakeDefaultWebSocketSession()
        val strangerPk = X25519.publicKeyOf(X25519.generatePrivateKey())
        val stranger = V3TestPeer(DeviceId.fromPublicKey(strangerPk).value)
        strangerWs.incomingChannel.send(stranger.preamble("Phone B", "android", LinkPreamble.PAIR_CONFIRM, strangerPk))
        val strangerJob = launch { manager.handleInbound(strangerWs) }
        assertEquals("the stranger's confirm pairing is under way", LinkPreambleAck.MODE_PAIRING_CONFIRM, firstAck(strangerWs).mode)

        val officeWs = FakeDefaultWebSocketSession()
        val officeJob = launch { dialIn(manager, officeWs, officePc.deviceId.value) }
        assertEquals(LinkPreambleAck.MODE_SESSION, firstAck(officeWs).mode)
        assertNotNull(manager.connectionFor(officePc.deviceId.value))

        officeWs.incomingChannel.close()
        officeJob.join()
        manager.cancelInboundPairing()
        strangerJob.cancel()
    }

    // ── Link protocol v3 (ADR-058) ────────────────────────────────────────────────────────

    @Test
    fun `a dialled session is reported only after the peer's first frame opens (FR-C)`() = runTest(testDispatcher) {
        val homeWs = FakeDefaultWebSocketSession()
        val peer = V3TestPeer(homePc.deviceId.value)
        val responder = launch { homeWs.incomingChannel.send(peer.answerSession(homeWs.outgoingChannel.receive(), homePc.pairingSecret)) }
        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = homeWs
        }
        val manager = twoPcManager(client)
        assertTrue(manager.connect(homePc.deviceId.value, "Home PC", listOf(Endpoint("192.168.1.30", 5150))))
        responder.join()
        assertNull("nothing is claimed before the PC's first frame", manager.connectionFor(homePc.deviceId.value))
        // The phone's own hello is sealed under this connection's keys and opens on the PC side.
        assertTrue(peer.open(homeWs.outgoingChannel.receive()).payload is PeerMessage.Hello)
        homeWs.incomingChannel.send(peer.seal(PeerMessage.HelloAck(homePc.deviceId.value, "Home PC", "windows")))
        assertNotNull(manager.connectionFor(homePc.deviceId.value))
        assertTrue(manager.events.value is ConnectionManager.ConnectionEvent.Connected)
        homeWs.incomingChannel.close()
    }

    @Test
    fun `an inbound session whose first frame fails is never claimed and does not replace the live one`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient(), trusted = officePc)
        val liveWs = FakeDefaultWebSocketSession()
        val liveJob = launch { dialIn(manager, liveWs, officePc.deviceId.value) }
        val live = manager.activeConnection.value
        assertNotNull(live)

        // A replayed preamble: the attacker cannot build this connection's keys (wrong secret).
        val attackerWs = FakeDefaultWebSocketSession()
        val attackJob = launch { dialIn(manager, attackerWs, officePc.deviceId.value, secret = ByteArray(32) { 1 }) }
        attackJob.join()
        assertSame("the live session is untouched", live, manager.activeConnection.value)
        assertTrue(live!!.isConnected)
        assertEquals(1, manager.connections.value.size)
        liveWs.incomingChannel.close()
        liveJob.join()
    }

    @Test
    fun `a dial whose ack lacks cn and epk fails without a session`() = runTest(testDispatcher) {
        val fakeSession = FakeDefaultWebSocketSession()
        fakeSession.incomingChannel.send(Frame.Text("""{"mode":"session"}"""))
        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = fakeSession
        }
        val manager = testManager(client, trusted = officePc)
        assertFalse(manager.connect(officePc.deviceId.value, "Office PC", listOf(Endpoint("192.168.1.20", 5150))))
        assertNull(manager.activeConnection.value)
    }

    @Test
    fun `an unknown device without a pairing mode is refused not-paired`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient())
        val ws = FakeDefaultWebSocketSession()
        val stranger = DeviceId.fromPublicKey(ByteArray(32) { 77 }).value
        ws.incomingChannel.send(V3TestPeer(stranger).preamble("Phone B", "android"))
        manager.handleInbound(ws)
        val ack = firstAck(ws)
        assertEquals(LinkPreambleAck.MODE_REFUSED, ack.mode)
        assertEquals("not-paired", ack.reason)
        assertNull("no consent prompt any more", manager.pendingInbound.value)
    }

    @Test
    fun `a v2 preamble is refused unsupported-protocol-version`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient(), trusted = officePc)
        val ws = FakeDefaultWebSocketSession()
        ws.incomingChannel.send(V3TestPeer(officePc.deviceId.value).preamble(protocolVersion = 2))
        manager.handleInbound(ws)
        assertEquals("unsupported-protocol-version", firstAck(ws).reason)
        assertNull(manager.activeConnection.value)
    }

    @Test
    fun `an unparsable preamble id is refused invalid-identity, our own id closes`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient())
        val bad = FakeDefaultWebSocketSession()
        bad.incomingChannel.send(V3TestPeer("not a device id").preamble())
        manager.handleInbound(bad)
        assertEquals("invalid-identity", firstAck(bad).reason)

        val self = FakeDefaultWebSocketSession()
        self.incomingChannel.send(V3TestPeer(localId).preamble())
        manager.handleInbound(self)
        assertNull("our own id gets no ack, only a close", self.outgoingChannel.tryReceive().getOrNull()?.takeIf { it is Frame.Text })
    }
}
@OptIn(io.ktor.utils.io.InternalAPI::class)
private class FakeDefaultWebSocketSession(
    override val coroutineContext: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Job(),
) : DefaultWebSocketSession {
    val incomingChannel = kotlinx.coroutines.channels.Channel<Frame>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val outgoingChannel = kotlinx.coroutines.channels.Channel<Frame>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val closeDeferred = kotlinx.coroutines.CompletableDeferred<CloseReason?>()

    override val incoming: kotlinx.coroutines.channels.ReceiveChannel<Frame> get() = incomingChannel
    override val outgoing: kotlinx.coroutines.channels.SendChannel<Frame> get() = outgoingChannel
    override val closeReason: kotlinx.coroutines.Deferred<CloseReason?> get() = closeDeferred

    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override var pingIntervalMillis: Long = 0
    override var timeoutMillis: Long = 0
    override val extensions: List<io.ktor.websocket.WebSocketExtension<*>> = emptyList()

    override suspend fun flush() {}
    @Suppress("DEPRECATION")
    override fun start(negotiatedExtensions: List<io.ktor.websocket.WebSocketExtension<*>>) {}
    override fun terminate() {
        incomingChannel.close()
        outgoingChannel.close()
        closeDeferred.complete(CloseReason(CloseReason.Codes.NORMAL, "terminated"))
    }
}
