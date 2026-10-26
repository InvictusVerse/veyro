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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
