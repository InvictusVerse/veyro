package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
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
        val ok = manager.connect("peer-1", "Peer Device", emptyList())
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
        val ok = manager.connect("peer-1", "Remote Device", endpoints)

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
            manager.connect("peer-1", "Remote Device", endpoints)
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
        val ok = manager.connect("peer-1", "Remote Device", endpoints)

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
        val ok = manager.connect("peer-1", "Remote Device", endpoints)

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
        val ok = manager.connect("peer-1", "Remote Device", endpoints)

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
        val ok = manager.connect("peer-1", "Remote Device", endpoints)

        assertFalse(ok)
        val event = manager.events.value
        assertTrue(event is ConnectionManager.ConnectionEvent.Failed)
        assertEquals("Remote Device did not answer", (event as ConnectionManager.ConnectionEvent.Failed).reason)
    }

    @Test
    fun `connect succeeds in session mode when peer is trusted`() = runTest(testDispatcher) {
        val fakeSession = FakeDefaultWebSocketSession()
        fakeSession.incomingChannel.send(Frame.Text("""{"mode":"session"}"""))

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

    private suspend fun dialIn(manager: ConnectionManager, ws: FakeDefaultWebSocketSession, id: String) {
        ws.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble(id, "Office PC", platform = "windows"))),
        )
        manager.handleInbound(ws)
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

        val ack = LinkJson.codec.decodeFromString(
            LinkPreambleAck.serializer(),
            (transferWs.outgoingChannel.receive() as Frame.Text).readText(),
        )
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

    private fun firstAck(ws: FakeDefaultWebSocketSession): LinkPreambleAck = LinkJson.codec.decodeFromString(
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
        homeWs.incomingChannel.send(Frame.Text("""{"mode":"session"}"""))
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
    fun `a paired PC opens its session while another device's pairing prompt is up`() = runTest(testDispatcher) {
        val manager = twoPcManager()
        val strangerWs = FakeDefaultWebSocketSession()
        val strangerJob = launch { dialIn(manager, strangerWs, "unknown-peer") }
        assertNotNull("the stranger's pairing prompt is showing", manager.pendingInbound.value)

        val officeWs = FakeDefaultWebSocketSession()
        val officeJob = launch { dialIn(manager, officeWs, officePc.deviceId.value) }
        assertEquals(LinkPreambleAck.MODE_SESSION, firstAck(officeWs).mode)
        assertNotNull(manager.connectionFor(officePc.deviceId.value))

        officeWs.incomingChannel.close()
        officeJob.join()
        manager.cancelInboundPairing()
        strangerJob.cancel()
    }

    @Test
    fun `inbound pairing request prompts for consent with placeholder pin and peer details`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient())
        val fakeSession = FakeDefaultWebSocketSession()
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble("peer-456", "Phone B")))
        )

        val job = launch { manager.handleInbound(fakeSession) }

        val pending = manager.pendingInbound.value
        assertNotNull(pending)
        assertEquals("peer-456", pending?.peerDeviceId)
        assertEquals("Phone B", pending?.peerDeviceName)
        assertEquals("...", pending?.pin)

        // Clean up
        manager.respondToInbound(false)
        job.join()
    }

    @Test
    fun `inbound pairing request approved by user mints PIN and sends pairing ack`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient())
        val fakeSession = FakeDefaultWebSocketSession()
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble("peer-456", "Phone B")))
        )

        val job = launch { manager.handleInbound(fakeSession) }

        // User approves
        manager.respondToInbound(true)

        // State transitions to real 6-digit PIN
        val pending = manager.pendingInbound.value
        assertNotNull(pending)
        assertEquals(6, pending?.pin?.length)
        assertTrue(pending?.pin?.all(Char::isDigit) == true)

        // Receiver must have sent LinkPreambleAck with mode="pairing"
        val outFrame = fakeSession.outgoingChannel.receive() as Frame.Text
        val ack = LinkJson.codec.decodeFromString(LinkPreambleAck.serializer(), outFrame.readText())
        assertEquals(LinkPreambleAck.MODE_PAIRING, ack.mode)

        // Cancel to finish job
        manager.cancelInboundPairing()
        job.join()
    }

    @Test
    fun `inbound pairing request rejected by user sends declined ack and clears pending state`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient())
        val fakeSession = FakeDefaultWebSocketSession()
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble("peer-456", "Phone B")))
        )

        val job = launch { manager.handleInbound(fakeSession) }

        assertNotNull(manager.pendingInbound.value)

        // User rejects
        manager.respondToInbound(false)
        job.join()

        // Pending state must be cleared immediately
        assertNull(manager.pendingInbound.value)

        // Receiver sent declined ack
        val outFrame = fakeSession.outgoingChannel.receive() as Frame.Text
        val ack = LinkJson.codec.decodeFromString(LinkPreambleAck.serializer(), outFrame.readText())
        assertEquals(LinkPreambleAck.MODE_REFUSED, ack.mode)
        assertEquals("declined", ack.reason)
    }

    @Test
    fun `remote peer disconnect while awaiting consent immediately clears pending state`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient())
        val fakeSession = FakeDefaultWebSocketSession()
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble("peer-456", "Phone B")))
        )

        val job = launch { manager.handleInbound(fakeSession) }
        assertNotNull(manager.pendingInbound.value)

        // Remote peer drops connection (e.g. initiator cancelled or timed out)
        fakeSession.incomingChannel.close()
        job.join()

        // Receiver must not hang in pending state
        assertNull(manager.pendingInbound.value)
    }

    @Test
    fun `same peer retrying pairing cancels stale request without busy lock`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient())
        val session1 = FakeDefaultWebSocketSession()
        session1.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble("peer-456", "Phone B")))
        )
        val job1 = launch { manager.handleInbound(session1) }
        assertEquals("...", manager.pendingInbound.value?.pin)

        // Peer retries with new session
        val session2 = FakeDefaultWebSocketSession()
        session2.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble("peer-456", "Phone B")))
        )
        val job2 = launch { manager.handleInbound(session2) }

        // First job must finish and second becomes active
        job1.join()
        assertNotNull(manager.pendingInbound.value)
        assertEquals("peer-456", manager.pendingInbound.value?.peerDeviceId)

        // Approve second session
        manager.respondToInbound(true)
        val outFrame = session2.outgoingChannel.receive() as Frame.Text
        val ack = LinkJson.codec.decodeFromString(LinkPreambleAck.serializer(), outFrame.readText())
        assertEquals(LinkPreambleAck.MODE_PAIRING, ack.mode)

        manager.cancelInboundPairing()
        job2.join()
    }

    @Test
    fun `different peer connecting while pairing is in progress receives busy ack`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient())
        val session1 = FakeDefaultWebSocketSession()
        session1.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble("peer-1", "Phone 1")))
        )
        val job1 = launch { manager.handleInbound(session1) }

        val session2 = FakeDefaultWebSocketSession()
        session2.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble("peer-2", "Phone 2")))
        )
        val job2 = launch { manager.handleInbound(session2) }
        job2.join()

        val outFrame2 = session2.outgoingChannel.receive() as Frame.Text
        val ack2 = LinkJson.codec.decodeFromString(LinkPreambleAck.serializer(), outFrame2.readText())
        assertEquals(LinkPreambleAck.MODE_BUSY, ack2.mode)
        assertEquals("pairing-in-progress", ack2.reason)

        manager.respondToInbound(false)
        job1.join()
    }

    @Test
    fun `cancelInboundPairing during PIN display closes session and clears state`() = runTest(testDispatcher) {
        val manager = testManager(ConnectionClient())
        val fakeSession = FakeDefaultWebSocketSession()
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble("peer-456", "Phone B")))
        )

        val job = launch { manager.handleInbound(fakeSession) }
        manager.respondToInbound(true)
        assertNotNull(manager.pendingInbound.value?.pin)

        // User hits cancel during PIN display
        manager.cancelInboundPairing()
        job.join()

        assertNull(manager.pendingInbound.value)
    }

    @Test
    fun `inbound pairing with valid identity and public key persists trusted peer and succeeds`() = runTest(testDispatcher) {
        var persistedPeer: TrustedPeer? = null
        val manager = testManager(
            client = ConnectionClient(),
            saveTrustedPeer = { persistedPeer = it },
        )

        val peerPk = ByteArray(32) { 2 }
        val peerId = DeviceId.fromPublicKey(peerPk).value
        val fakeSession = FakeDefaultWebSocketSession()

        // 1. Send preamble from initiator
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble(peerId, "Initiator Phone")))
        )
        val job = launch { manager.handleInbound(fakeSession) }

        // 2. User consents
        manager.respondToInbound(true)
        val ackFrame = fakeSession.outgoingChannel.receive() as Frame.Text
        val ack = LinkJson.codec.decodeFromString(LinkPreambleAck.serializer(), ackFrame.readText())
        assertEquals(LinkPreambleAck.MODE_PAIRING, ack.mode)

        val pin = manager.pendingInbound.value?.pin
        assertNotNull(pin)

        // 3. Initiator sends PairHello with matching public key
        val bootstrapKey = PairingRegistry().deriveInitiatorKey(pin!!, peerId, localId)
        val hello = LinkMessage.PairHello(
            deviceId = peerId,
            deviceName = "Initiator Phone",
            platform = "android",
            appVersion = "0.1.0",
            publicKeyB64 = Base64.getEncoder().encodeToString(peerPk),
        )
        val helloEnv = LinkEnvelope(
            msgId = UUID.randomUUID().toString(),
            from = peerId,
            seq = 1L,
            ts = System.currentTimeMillis(),
            payload = hello,
        )
        val sealedHello = FrameCrypto.seal(
            LinkJson.codec.encodeToString(LinkEnvelope.serializer(), helloEnv),
            bootstrapKey,
            encrypt = false,
            peerId,
        )
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkWireFrame.serializer(), sealedHello.toWire()))
        )

        // 4. Target sends sealed PairOk
        val pairOkFrame = fakeSession.outgoingChannel.receive() as Frame.Text
        val pairOkWire = LinkJson.codec.decodeFromString(LinkWireFrame.serializer(), pairOkFrame.readText())
        val openedOkJson = FrameCrypto.open(pairOkWire.toSealed()!!, bootstrapKey)
        assertNotNull(openedOkJson)
        val okEnv = LinkJson.codec.decodeFromString(LinkEnvelope.serializer(), openedOkJson!!)
        assertTrue(okEnv.payload is LinkMessage.PairOk)
        val pairOk = okEnv.payload as LinkMessage.PairOk
        assertEquals(localId, pairOk.deviceId)
        assertNotNull(pairOk.publicKeyB64)

        // 5. Verify persistence and success event
        assertNotNull(persistedPeer)
        assertEquals(peerId, persistedPeer!!.deviceId.value)
        assertTrue(persistedPeer!!.staticPublicKey.contentEquals(peerPk))
        assertTrue("Persisted peer must be self-consistent", persistedPeer!!.isSelfConsistent())

        val event = manager.events.value
        assertTrue(event is ConnectionManager.ConnectionEvent.Paired)
        assertEquals(peerId, (event as ConnectionManager.ConnectionEvent.Paired).peer.deviceId.value)

        manager.disconnect()
        job.join()
    }

    @Test
    fun `inbound pairing with mismatched identity is rejected without persisting`() = runTest(testDispatcher) {
        var persistedPeer: TrustedPeer? = null
        val manager = testManager(
            client = ConnectionClient(),
            saveTrustedPeer = { persistedPeer = it },
        )

        val peerPk = ByteArray(32) { 2 }
        val peerId = DeviceId.fromPublicKey(peerPk).value
        val mismatchedPk = ByteArray(32) { 9 } // does not match peerId
        val fakeSession = FakeDefaultWebSocketSession()

        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble(peerId, "Attacker Phone")))
        )
        val job = launch { manager.handleInbound(fakeSession) }

        manager.respondToInbound(true)
        fakeSession.outgoingChannel.receive() // Ack
        val pin = manager.pendingInbound.value?.pin
        assertNotNull(pin)

        val bootstrapKey = PairingRegistry().deriveInitiatorKey(pin!!, peerId, localId)
        val hello = LinkMessage.PairHello(
            deviceId = peerId,
            deviceName = "Attacker Phone",
            platform = "android",
            appVersion = "0.1.0",
            publicKeyB64 = Base64.getEncoder().encodeToString(mismatchedPk),
        )
        val helloEnv = LinkEnvelope(
            msgId = UUID.randomUUID().toString(),
            from = peerId,
            seq = 1L,
            ts = System.currentTimeMillis(),
            payload = hello,
        )
        val sealedHello = FrameCrypto.seal(
            LinkJson.codec.encodeToString(LinkEnvelope.serializer(), helloEnv),
            bootstrapKey,
            encrypt = false,
            peerId,
        )
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkWireFrame.serializer(), sealedHello.toWire()))
        )

        job.join()

        assertNull("Mismatched identity must never be persisted", persistedPeer)
        val event = manager.events.value
        assertTrue(event is ConnectionManager.ConnectionEvent.Failed)
        assertEquals("Peer identity does not match public key", (event as ConnectionManager.ConnectionEvent.Failed).reason)
    }

    @Test
    fun `inbound pairing where saveTrustedPeer throws closes session and does not report Paired`() = runTest(testDispatcher) {
        val manager = testManager(
            client = ConnectionClient(),
            saveTrustedPeer = { throw IllegalArgumentException("Disk write failed") },
        )

        val peerPk = ByteArray(32) { 2 }
        val peerId = DeviceId.fromPublicKey(peerPk).value
        val fakeSession = FakeDefaultWebSocketSession()

        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble(peerId, "Initiator Phone")))
        )
        val job = launch { manager.handleInbound(fakeSession) }

        manager.respondToInbound(true)
        fakeSession.outgoingChannel.receive()
        val pin = manager.pendingInbound.value?.pin
        assertNotNull(pin)

        val bootstrapKey = PairingRegistry().deriveInitiatorKey(pin!!, peerId, localId)
        val hello = LinkMessage.PairHello(
            deviceId = peerId,
            deviceName = "Initiator Phone",
            platform = "android",
            appVersion = "0.1.0",
            publicKeyB64 = Base64.getEncoder().encodeToString(peerPk),
        )
        val helloEnv = LinkEnvelope(
            msgId = UUID.randomUUID().toString(),
            from = peerId,
            seq = 1L,
            ts = System.currentTimeMillis(),
            payload = hello,
        )
        val sealedHello = FrameCrypto.seal(
            LinkJson.codec.encodeToString(LinkEnvelope.serializer(), helloEnv),
            bootstrapKey,
            encrypt = false,
            peerId,
        )
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkWireFrame.serializer(), sealedHello.toWire()))
        )

        job.join()

        val event = manager.events.value
        assertTrue("Must report Failed when persistence throws", event is ConnectionManager.ConnectionEvent.Failed)
        assertEquals("Could not save pairing on this device", (event as ConnectionManager.ConnectionEvent.Failed).reason)
        assertFalse("Must never emit Paired state if persistence failed", event is ConnectionManager.ConnectionEvent.Paired)
    }

    @Test
    fun `outbound pairing with valid identity and public key persists trusted peer and succeeds`() = runTest(testDispatcher) {
        var persistedPeer: TrustedPeer? = null
        val targetPk = ByteArray(32) { 3 }
        val targetId = DeviceId.fromPublicKey(targetPk).value
        val fakeSession = FakeDefaultWebSocketSession()
        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = fakeSession
        }
        val manager = testManager(
            client = client,
            saveTrustedPeer = { persistedPeer = it },
        )

        val pin = "654321"
        val bootstrapKey = FrameCrypto.deriveBootstrapKey(pin, localId, targetId)

        val connectJob = launch {
            manager.connect(targetId, "Target Phone", listOf(Endpoint("192.168.1.10", 3000)))
        }

        // Preamble sent from initiator
        val preambleFrame = fakeSession.outgoingChannel.receive() as Frame.Text
        val preamble = LinkJson.codec.decodeFromString(LinkPreamble.serializer(), preambleFrame.readText())
        assertEquals(localId, preamble.id)

        // Remote target replies with MODE_PAIRING ack
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreambleAck.serializer(), LinkPreambleAck(LinkPreambleAck.MODE_PAIRING)))
        )

        // Initiator user submits PIN
        manager.submitPin(pin)

        // Initiator sends PairHello
        val pairHelloFrame = fakeSession.outgoingChannel.receive() as Frame.Text
        val helloWire = LinkJson.codec.decodeFromString(LinkWireFrame.serializer(), pairHelloFrame.readText())
        val openedHelloJson = FrameCrypto.open(helloWire.toSealed()!!, bootstrapKey)
        assertNotNull(openedHelloJson)
        val helloEnv = LinkJson.codec.decodeFromString(LinkEnvelope.serializer(), openedHelloJson!!)
        val helloMsg = helloEnv.payload as LinkMessage.PairHello
        assertEquals(localId, helloMsg.deviceId)
        assertNotNull(helloMsg.publicKeyB64)

        // Target responds with sealed PairOk
        val secret = ByteArray(32) { 7 }
        val pairOk = LinkMessage.PairOk(
            deviceId = targetId,
            deviceName = "Target Phone",
            platform = "android",
            sessionSecretB64 = Base64.getEncoder().encodeToString(secret),
            publicKeyB64 = Base64.getEncoder().encodeToString(targetPk),
        )
        val okEnv = LinkEnvelope(
            msgId = UUID.randomUUID().toString(),
            from = targetId,
            seq = 1L,
            ts = System.currentTimeMillis(),
            payload = pairOk,
        )
        val sealedOk = FrameCrypto.seal(
            LinkJson.codec.encodeToString(LinkEnvelope.serializer(), okEnv),
            bootstrapKey,
            encrypt = true,
            targetId,
        )
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkWireFrame.serializer(), sealedOk.toWire()))
        )

        connectJob.join()

        assertNotNull(persistedPeer)
        assertEquals(targetId, persistedPeer!!.deviceId.value)
        assertTrue(persistedPeer!!.staticPublicKey.contentEquals(targetPk))
        assertTrue("Persisted peer must be self-consistent", persistedPeer!!.isSelfConsistent())

        val event = manager.events.value
        assertTrue(event is ConnectionManager.ConnectionEvent.Paired)
        assertEquals(targetId, (event as ConnectionManager.ConnectionEvent.Paired).peer.deviceId.value)

        manager.disconnect()
    }

    @Test
    fun `outbound pairing with mismatched target identity in PairOk fails without persisting`() = runTest(testDispatcher) {
        var persistedPeer: TrustedPeer? = null
        val targetPk = ByteArray(32) { 3 }
        val targetId = DeviceId.fromPublicKey(targetPk).value
        val mismatchedPk = ByteArray(32) { 8 } // does not match targetId
        val fakeSession = FakeDefaultWebSocketSession()
        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = fakeSession
        }
        val manager = testManager(
            client = client,
            saveTrustedPeer = { persistedPeer = it },
        )

        val pin = "654321"
        val bootstrapKey = FrameCrypto.deriveBootstrapKey(pin, localId, targetId)

        val connectJob = launch {
            manager.connect(targetId, "Target Phone", listOf(Endpoint("192.168.1.10", 3000)))
        }

        fakeSession.outgoingChannel.receive() // preamble
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreambleAck.serializer(), LinkPreambleAck(LinkPreambleAck.MODE_PAIRING)))
        )
        manager.submitPin(pin)
        fakeSession.outgoingChannel.receive() // PairHello

        // Target responds with mismatched publicKeyB64
        val secret = ByteArray(32) { 7 }
        val pairOk = LinkMessage.PairOk(
            deviceId = targetId,
            deviceName = "Target Phone",
            platform = "android",
            sessionSecretB64 = Base64.getEncoder().encodeToString(secret),
            publicKeyB64 = Base64.getEncoder().encodeToString(mismatchedPk),
        )
        val okEnv = LinkEnvelope(
            msgId = UUID.randomUUID().toString(),
            from = targetId,
            seq = 1L,
            ts = System.currentTimeMillis(),
            payload = pairOk,
        )
        val sealedOk = FrameCrypto.seal(
            LinkJson.codec.encodeToString(LinkEnvelope.serializer(), okEnv),
            bootstrapKey,
            encrypt = true,
            targetId,
        )
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkWireFrame.serializer(), sealedOk.toWire()))
        )

        connectJob.join()

        assertNull("Mismatched target identity must never be persisted", persistedPeer)
        val event = manager.events.value
        assertTrue(event is ConnectionManager.ConnectionEvent.Failed)
        assertEquals("Peer identity does not match public key", (event as ConnectionManager.ConnectionEvent.Failed).reason)
    }

    @Test
    fun `outbound pairing where saveTrustedPeer throws fails and does not report Paired`() = runTest(testDispatcher) {
        val targetPk = ByteArray(32) { 3 }
        val targetId = DeviceId.fromPublicKey(targetPk).value
        val fakeSession = FakeDefaultWebSocketSession()
        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = fakeSession
        }
        val manager = testManager(
            client = client,
            saveTrustedPeer = { throw IllegalStateException("Database locked") },
        )

        val pin = "654321"
        val bootstrapKey = FrameCrypto.deriveBootstrapKey(pin, localId, targetId)

        val connectJob = launch {
            manager.connect(targetId, "Target Phone", listOf(Endpoint("192.168.1.10", 3000)))
        }

        fakeSession.outgoingChannel.receive()
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreambleAck.serializer(), LinkPreambleAck(LinkPreambleAck.MODE_PAIRING)))
        )
        manager.submitPin(pin)
        fakeSession.outgoingChannel.receive()

        val secret = ByteArray(32) { 7 }
        val pairOk = LinkMessage.PairOk(
            deviceId = targetId,
            deviceName = "Target Phone",
            platform = "android",
            sessionSecretB64 = Base64.getEncoder().encodeToString(secret),
            publicKeyB64 = Base64.getEncoder().encodeToString(targetPk),
        )
        val okEnv = LinkEnvelope(
            msgId = UUID.randomUUID().toString(),
            from = targetId,
            seq = 1L,
            ts = System.currentTimeMillis(),
            payload = pairOk,
        )
        val sealedOk = FrameCrypto.seal(
            LinkJson.codec.encodeToString(LinkEnvelope.serializer(), okEnv),
            bootstrapKey,
            encrypt = true,
            targetId,
        )
        fakeSession.incomingChannel.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkWireFrame.serializer(), sealedOk.toWire()))
        )

        connectJob.join()

        val event = manager.events.value
        assertTrue("Must report Failed when persistence throws", event is ConnectionManager.ConnectionEvent.Failed)
        assertEquals("Could not save pairing on this device", (event as ConnectionManager.ConnectionEvent.Failed).reason)
        assertFalse("Must never emit Paired state if persistence failed", event is ConnectionManager.ConnectionEvent.Paired)
    }

    // ── QR code pairing (ADR-030, 04-PROTOCOL.md section 4.2) ──────────────────────────────

    private fun sealedPairOk(
        key: javax.crypto.SecretKey,
        deviceId: String,
        publicKey: ByteArray,
        name: String = "Office PC",
        /** The id the frame is bound to; the dialled id, since that is who the phone expects. */
        sender: String = deviceId,
    ): Frame.Text {
        val pairOk = LinkMessage.PairOk(
            deviceId = deviceId,
            deviceName = name,
            platform = "windows",
            sessionSecretB64 = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
            publicKeyB64 = Base64.getEncoder().encodeToString(publicKey),
        )
        val env = LinkEnvelope(
            msgId = UUID.randomUUID().toString(),
            from = sender,
            seq = 1L,
            ts = System.currentTimeMillis(),
            payload = pairOk,
        )
        val sealed = FrameCrypto.seal(
            LinkJson.codec.encodeToString(LinkEnvelope.serializer(), env),
            key,
            encrypt = true,
            sender,
        )
        return Frame.Text(LinkJson.codec.encodeToString(LinkWireFrame.serializer(), sealed.toWire()))
    }

    private val pairingAck: Frame.Text
        get() = Frame.Text(
            LinkJson.codec.encodeToString(LinkPreambleAck.serializer(), LinkPreambleAck(LinkPreambleAck.MODE_PAIRING)),
        )

    @Test
    fun `pairWithCode uses the scanned code without prompting and persists the scanned PC`() = runTest(testDispatcher) {
        var persistedPeer: TrustedPeer? = null
        val pcPk = ByteArray(32) { 4 }
        val pcId = DeviceId.fromPublicKey(pcPk).value
        val fakeSession = FakeDefaultWebSocketSession()
        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = fakeSession
        }
        val manager = testManager(client = client, saveTrustedPeer = { persistedPeer = it })
        val code = "246810"
        val key = FrameCrypto.deriveBootstrapKey(code, localId, pcId)

        val progress = mutableListOf<ConnectionManager.CodePairingProgress>()
        val job = launch {
            manager.pairWithCode(pcId, "Office PC", listOf(Endpoint("192.168.1.20", 5150)), code)
                .collect { progress += it }
        }

        fakeSession.outgoingChannel.receive() // preamble
        fakeSession.incomingChannel.send(pairingAck)
        assertNull("A scanned code must not prompt for a PIN", manager.pendingOutbound.value)

        // PairHello must be sealed under the key derived from the scanned code.
        val helloFrame = fakeSession.outgoingChannel.receive() as Frame.Text
        val helloWire = LinkJson.codec.decodeFromString(LinkWireFrame.serializer(), helloFrame.readText())
        assertNotNull(FrameCrypto.open(helloWire.toSealed()!!, key))

        fakeSession.incomingChannel.send(sealedPairOk(key, pcId, pcPk))
        job.join()

        assertEquals(ConnectionManager.CodePairingProgress.Connecting, progress[0])
        assertEquals(ConnectionManager.CodePairingProgress.Securing, progress[1])
        val last = progress.last()
        assertTrue("expected Paired, got $last", last is ConnectionManager.CodePairingProgress.Paired)
        assertEquals(pcId, persistedPeer?.deviceId?.value)
        assertEquals(3, progress.size)

        manager.disconnect()
    }

    @Test
    fun `pairWithCode aborts without persisting when a different identity answers`() = runTest(testDispatcher) {
        var persistedPeer: TrustedPeer? = null
        val pcId = DeviceId.fromPublicKey(ByteArray(32) { 4 }).value
        // Someone else at the scanned address who also learned the code: their PairOk is
        // self-consistent (id matches their own key) and sealed correctly, but it is not the PC.
        val impostorPk = ByteArray(32) { 6 }
        val impostorId = DeviceId.fromPublicKey(impostorPk).value
        val fakeSession = FakeDefaultWebSocketSession()
        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = fakeSession
        }
        val manager = testManager(client = client, saveTrustedPeer = { persistedPeer = it })
        val code = "135790"
        val key = FrameCrypto.deriveBootstrapKey(code, localId, pcId)

        val progress = mutableListOf<ConnectionManager.CodePairingProgress>()
        val job = launch {
            manager.pairWithCode(pcId, "Office PC", listOf(Endpoint("192.168.1.20", 5150)), code)
                .collect { progress += it }
        }
        fakeSession.outgoingChannel.receive() // preamble
        fakeSession.incomingChannel.send(pairingAck)
        fakeSession.outgoingChannel.receive() // PairHello
        fakeSession.incomingChannel.send(sealedPairOk(key, impostorId, impostorPk, sender = pcId))
        job.join()

        assertNull("An identity mismatch must never be persisted", persistedPeer)
        assertNull(manager.activeConnection.value)
        val last = progress.last()
        assertTrue("expected Failed, got $last", last is ConnectionManager.CodePairingProgress.Failed)
        assertEquals(
            ConnectionManager.CodePairingFailure.IDENTITY_MISMATCH,
            (last as ConnectionManager.CodePairingProgress.Failed).failure,
        )
        assertFalse(manager.events.value is ConnectionManager.ConnectionEvent.Paired)
    }

    @Test
    fun `plain connect still accepts a self-consistent identity that differs from the dialled id`() = runTest(testDispatcher) {
        // The scanned-id check applies only when an expected id is supplied; the typed-PIN path
        // is unchanged by ADR-030.
        var persistedPeer: TrustedPeer? = null
        val dialledId = DeviceId.fromPublicKey(ByteArray(32) { 4 }).value
        val otherPk = ByteArray(32) { 6 }
        val otherId = DeviceId.fromPublicKey(otherPk).value
        val fakeSession = FakeDefaultWebSocketSession()
        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession = fakeSession
        }
        val manager = testManager(client = client, saveTrustedPeer = { persistedPeer = it })
        val pin = "111222"
        val key = FrameCrypto.deriveBootstrapKey(pin, localId, dialledId)

        val job = launch { manager.connect(dialledId, "PC", listOf(Endpoint("192.168.1.20", 5150))) }
        fakeSession.outgoingChannel.receive()
        fakeSession.incomingChannel.send(pairingAck)
        manager.submitPin(pin)
        fakeSession.outgoingChannel.receive()
        fakeSession.incomingChannel.send(sealedPairOk(key, otherId, otherPk, sender = dialledId))
        job.join()

        assertEquals(otherId, persistedPeer?.deviceId?.value)
        manager.disconnect()
    }

    @Test
    fun `pairWithCode reports unreachable when no endpoint answers`() = runTest(testDispatcher) {
        val failingClient = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                throw ConnectException("Connection refused")
            }
        }
        val manager = testManager(failingClient)
        val pcId = DeviceId.fromPublicKey(ByteArray(32) { 4 }).value
        val progress = manager.pairWithCode(
            pcId,
            "Office PC",
            listOf(Endpoint("192.168.1.20", 5150), Endpoint("10.0.0.5", 5150)),
            "123456",
        ).toList()

        assertEquals(ConnectionManager.CodePairingProgress.Connecting, progress.first())
        val last = progress.last() as ConnectionManager.CodePairingProgress.Failed
        assertEquals(ConnectionManager.CodePairingFailure.UNREACHABLE, last.failure)
        assertEquals(2, progress.size)
    }

    @Test
    fun `pairWithCode rejects a malformed code before dialling`() = runTest(testDispatcher) {
        var dialled = false
        val client = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                dialled = true
                throw ConnectException("unexpected")
            }
        }
        val manager = testManager(client)
        val pcId = DeviceId.fromPublicKey(ByteArray(32) { 4 }).value
        val last = manager.pairWithCode(pcId, "PC", listOf(Endpoint("192.168.1.20", 5150)), "12a456")
            .toList().last() as ConnectionManager.CodePairingProgress.Failed
        assertEquals(ConnectionManager.CodePairingFailure.CODE_REJECTED, last.failure)
        assertFalse(dialled)
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
