package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferRoute
import com.veyro.core.network.InterfaceKind
import com.veyro.core.network.LocalAddress
import com.veyro.core.network.LocalAddressProvider
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import com.veyro.core.link.TransferRunner.SendOutcome
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ConnectException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
public class TcpRouteNegotiationTest {

    @get:Rule
    public val tempDir: TemporaryFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = testDispatcher
        override val io = Dispatchers.IO
        override val main = testDispatcher
    }

    private class FakeLocalAddressProvider(
        private val addresses: List<LocalAddress>,
    ) : LocalAddressProvider {
        override suspend fun localAddresses(): List<LocalAddress> = addresses
    }

    private fun createConnectedPair(
        initiatorId: String = "initiator-dev",
        receiverId: String = "receiver-dev",
        initiatorRemoteHost: String? = "127.0.0.1",
        initiatorLocalHost: String? = "127.0.0.1",
        receiverRemoteHost: String? = "127.0.0.1",
        receiverLocalHost: String? = "127.0.0.1",
    ): Pair<PeerConnection, PeerConnection> {
        val secret = ByteArray(32) { 99 }
        val sessionKey = TestKeyPairing(secret)
        val initToRecv = Channel<Frame>(Channel.UNLIMITED)
        val recvToInit = Channel<Frame>(Channel.UNLIMITED)

        val initWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = recvToInit
            override val outgoing: SendChannel<Frame> = initToRecv
        }

        val recvWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = initToRecv
            override val outgoing: SendChannel<Frame> = recvToInit
        }

        val initiator = PeerConnection(
            ws = initWs,
            keys = sessionKey.forLocal(initiatorId, receiverId),
            // Virtual time in these tests would fire the 10 s first-frame timer (FR-C3) while real I/O runs.
            firstFrameTimeoutMs = Long.MAX_VALUE,
            localDeviceId = initiatorId,
            tag = "TestSender",
            dispatcher = testDispatcher,
            enableHeartbeat = false,
            remoteHost = initiatorRemoteHost,
            localHost = initiatorLocalHost,
        )

        val receiver = PeerConnection(
            ws = recvWs,
            keys = sessionKey.forLocal(receiverId, initiatorId),
            // Virtual time in these tests would fire the 10 s first-frame timer (FR-C3) while real I/O runs.
            firstFrameTimeoutMs = Long.MAX_VALUE,
            localDeviceId = receiverId,
            tag = "TestReceiver",
            dispatcher = testDispatcher,
            enableHeartbeat = false,
            remoteHost = receiverRemoteHost,
            localHost = receiverLocalHost,
        )

        initiator.start()
        receiver.start()
        initiator.admit()
        receiver.admit()

        return Pair(initiator, receiver)
    }

    // ── 1. TransferDecision Serialization ───────────────────────────────────────────────

    @Test
    fun `TransferDecision serialization preserves route, hosts, port, and auth token`() {
        val original = PeerMessage.TransferDecision(
            transferId = "transfer-123",
            accept = true,
            reason = null,
            resume = mapOf(0 to listOf(1, 2)),
            route = TransferRoute.LAN_TCP.name,
            tcpHost = "192.168.31.100",
            tcpPort = 45365,
            fallbackHost = "192.168.1.50",
            authToken = "auth-token-xyz-789",
        )

        val json = PeerMessageJson.codec.encodeToString(PeerMessage.serializer(), original)
        val decoded = PeerMessageJson.codec.decodeFromString(PeerMessage.serializer(), json) as PeerMessage.TransferDecision

        assertEquals("transfer-123", decoded.transferId)
        assertTrue(decoded.accept)
        assertEquals("LAN_TCP", decoded.route)
        assertEquals("192.168.31.100", decoded.tcpHost)
        assertEquals(45365, decoded.tcpPort)
        assertEquals("192.168.1.50", decoded.fallbackHost)
        assertEquals("auth-token-xyz-789", decoded.authToken)
        assertEquals(listOf(1, 2), decoded.resume?.get(0))
    }

    // ── 2. Rejecting Invalid or Wildcard Destination Hosts ─────────────────────────────

    @Test
    fun `resolveSenderTargetHost rejects wildcard 0_0_0_0 and invalid IPs`() {
        val runner = TransferRunner(dispatchers)
        val (senderSession, _) = createConnectedPair(initiatorRemoteHost = null)

        // Wildcard
        val wildcardDecision = PeerMessage.TransferDecision(
            transferId = "t1",
            accept = true,
            route = "LAN_TCP",
            tcpHost = "0.0.0.0",
            tcpPort = 12345,
            authToken = "tok",
        )
        val wildcardRes = runner.resolveSenderTargetHost(wildcardDecision, senderSession)
        assertTrue(wildcardRes is TransferRunner.TargetHostResolution.Invalid)

        // Malformed IP
        val malformedDecision = PeerMessage.TransferDecision(
            transferId = "t1",
            accept = true,
            route = "LAN_TCP",
            tcpHost = "999.999.999.999",
            tcpPort = 12345,
            authToken = "tok",
        )
        val malformedRes = runner.resolveSenderTargetHost(malformedDecision, senderSession)
        assertTrue(malformedRes is TransferRunner.TargetHostResolution.Invalid)

        // Blank IP
        val blankDecision = PeerMessage.TransferDecision(
            transferId = "t1",
            accept = true,
            route = "LAN_TCP",
            tcpHost = "   ",
            tcpPort = 12345,
            authToken = "tok",
        )
        val blankRes = runner.resolveSenderTargetHost(blankDecision, senderSession)
        assertTrue(blankRes is TransferRunner.TargetHostResolution.Invalid)
    }

    // ── 3. Correct LAN Endpoint Selection with Multiple Available Interfaces ────────────

    @Test
    fun `resolveReceiverLanAddress prefers subnet match with peer remote address`() = runTest(testDispatcher) {
        val addressProvider = FakeLocalAddressProvider(
            listOf(
                LocalAddress(
                    address = "10.0.0.5",
                    interfaceName = "eth0",
                    kind = InterfaceKind.ETHERNET,
                    prefixLength = 24,
                    broadcastAddress = "10.0.0.255",
                    isLinkLocal = false,
                ),
                LocalAddress(
                    address = "192.168.31.102",
                    interfaceName = "wlan0",
                    kind = InterfaceKind.WIFI,
                    prefixLength = 24,
                    broadcastAddress = "192.168.31.255",
                    isLinkLocal = false,
                ),
                LocalAddress(
                    address = "172.16.1.1",
                    interfaceName = "tun0",
                    kind = InterfaceKind.VPN,
                    prefixLength = 16,
                    broadcastAddress = null,
                    isLinkLocal = false,
                ),
            ),
        )

        val runner = TransferRunner(dispatchers, localAddressProvider = addressProvider)
        // Receiver knows peer remote address is 192.168.31.95 (Xiaomi sender)
        val (_, receiverSession) = createConnectedPair(
            receiverRemoteHost = "192.168.31.95",
            receiverLocalHost = null,
        )

        val resolved = runner.resolveReceiverLanAddress(receiverSession)
        assertEquals("192.168.31.102", resolved)
    }

    @Test
    fun `resolveReceiverLanAddress uses session localHost if reliably present`() = runTest(testDispatcher) {
        val addressProvider = FakeLocalAddressProvider(
            listOf(
                LocalAddress(
                    address = "10.0.0.5",
                    interfaceName = "eth0",
                    kind = InterfaceKind.ETHERNET,
                    prefixLength = 24,
                    broadcastAddress = "10.0.0.255",
                    isLinkLocal = false,
                ),
            ),
        )

        val runner = TransferRunner(dispatchers, localAddressProvider = addressProvider)
        val (_, receiverSession) = createConnectedPair(
            receiverRemoteHost = "192.168.31.95",
            receiverLocalHost = "192.168.31.100",
        )

        val resolved = runner.resolveReceiverLanAddress(receiverSession)
        assertEquals("192.168.31.100", resolved)
    }

    @Test
    fun `resolveReceiverLanAddress never returns sender remote IP or loopback on physical network`() = runTest(testDispatcher) {
        val addressProvider = FakeLocalAddressProvider(
            listOf(
                LocalAddress(
                    address = "192.168.31.105",
                    interfaceName = "wlan0",
                    kind = InterfaceKind.WIFI,
                    prefixLength = 24,
                    broadcastAddress = "192.168.31.255",
                    isLinkLocal = false,
                ),
            ),
        )

        val runner = TransferRunner(dispatchers, localAddressProvider = addressProvider)
        val (_, receiverSession) = createConnectedPair(
            receiverRemoteHost = "192.168.31.95",
            receiverLocalHost = null,
        )

        val resolved = runner.resolveReceiverLanAddress(receiverSession)
        assertEquals("192.168.31.105", resolved)
        assertFalse(resolved == "192.168.31.95") // Never sender remote IP
        assertFalse(resolved == "127.0.0.1")
        assertFalse(resolved == "0.0.0.0")
    }

    // ── 4. Immediate Understandable Failure on Missing Endpoint ────────────────────────

    @Test
    fun `sender fails immediately with descriptive error when TCP port is missing`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val payload = ByteArray(1024) { 1 }
        val srcFile = tempDir.newFile("missing_port.bin").apply { writeBytes(payload) }
        val item = TransferItem(
            index = 0,
            relativePath = "missing_port.bin",
            sizeBytes = payload.size.toLong(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
        )

        // Launch receiver that responds with accept=true but tcpPort=null
        val receiveJob = launch(dispatchers.io) {
            val offerEnvelope = receiverSession.receiveOffer()
            val offer = offerEnvelope.payload as PeerMessage.TransferOffer
            receiverSession.send(
                PeerMessage.TransferDecision(
                    transferId = offer.transferId,
                    accept = true,
                    route = "LAN_TCP",
                    tcpHost = "127.0.0.1",
                    tcpPort = null, // Missing port
                    authToken = "token",
                ),
            )
        }

        val outcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "TestSender",
            resolveFile = { srcFile },
        )

        assertTrue(outcome is SendOutcome.Failed)
        assertTrue((outcome as SendOutcome.Failed).reason.contains("TCP transfer endpoint"))
        receiveJob.join()
    }

    @Test
    fun `sender fails immediately with descriptive error when auth token is missing`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val payload = ByteArray(1024) { 1 }
        val srcFile = tempDir.newFile("missing_tok.bin").apply { writeBytes(payload) }
        val item = TransferItem(
            index = 0,
            relativePath = "missing_tok.bin",
            sizeBytes = payload.size.toLong(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
        )

        val receiveJob = launch(dispatchers.io) {
            val offerEnvelope = receiverSession.receiveOffer()
            val offer = offerEnvelope.payload as PeerMessage.TransferOffer
            receiverSession.send(
                PeerMessage.TransferDecision(
                    transferId = offer.transferId,
                    accept = true,
                    route = "LAN_TCP",
                    tcpHost = "127.0.0.1",
                    tcpPort = 54321,
                    authToken = null, // Missing auth token
                ),
            )
        }

        val outcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "TestSender",
            resolveFile = { srcFile },
        )

        assertTrue(outcome is SendOutcome.Failed)
        assertTrue((outcome as SendOutcome.Failed).reason.contains("authentication token"))
        receiveJob.join()
    }

    // ── 5. End-to-End Successful Connection and Transfer ───────────────────────────────

    @Test
    fun `successful raw TCP transfer over LAN route connects, authenticates, and transfers file`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val payload = ByteArray(256 * 1024) { (it xor 0x5A).toByte() }
        val srcFile = tempDir.newFile("e2e_lan.bin").apply { writeBytes(payload) }
        val destDir = tempDir.newFolder("e2e_lan_dest")
        val item = TransferItem(
            index = 0,
            relativePath = "e2e_lan.bin",
            sizeBytes = payload.size.toLong(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
        )

        val recvJob = launch(dispatchers.io) {
            runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
        }

        val sendOutcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "SenderDevice",
            resolveFile = { srcFile },
        )

        assertTrue("Expected SendOutcome.Done, got $sendOutcome", sendOutcome is SendOutcome.Done)
        recvJob.join()

        val receivedFile = File(destDir, "e2e_lan.bin")
        assertTrue(receivedFile.exists())
        assertEquals(payload.size.toLong(), receivedFile.length())
        org.junit.Assert.assertArrayEquals(payload, receivedFile.readBytes())
    }

    // ── 6. Connection Refusal / Failure Classification ─────────────────────────────────

    @Test
    fun `TcpDataSender fails cleanly with connection error when port has no listener`() = runTest(testDispatcher) {
        val sender = TcpDataSender(dispatchers, connectTimeoutMs = 1_000)

        var thrown: Throwable? = null
        try {
            // Dial unused port on loopback
            sender.connectAndAuthenticate(
                host = "127.0.0.1",
                port = 59999,
                transferId = "refuse-test",
                authToken = "some-token",
                senderDeviceId = "sender-id",
                route = TransferRoute.LAN_TCP,
                deriveKeys = TestLinkKeys.deriver("refuse-test"),
            )
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull(thrown)
        assertFalse(sender.isConnected)
    }
}
