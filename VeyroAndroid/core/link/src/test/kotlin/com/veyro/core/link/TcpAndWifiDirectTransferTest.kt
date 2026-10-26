package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferRoute
import com.veyro.core.model.VerificationResult
import com.veyro.core.network.WifiDirectLinkInfo
import com.veyro.core.network.WifiDirectManager
import com.veyro.core.protocol.tcp.TcpFrame
import com.veyro.core.protocol.tcp.TcpFrameCodec
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
public class TcpAndWifiDirectTransferTest {

    @get:Rule
    public val tempDir: TemporaryFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = testDispatcher
        override val io = Dispatchers.IO
        override val main = testDispatcher
    }

    /**
     * Simulates asynchronous platform behavior where group credentials
     * become available after a delay (mirroring real Android P2P lifecycle).
     */
    private class FakeWifiDirectManager(
        override var isSupported: Boolean = true,
        override var isEnabled: Boolean = true,
        private val linkInfoProvider: (Boolean) -> WifiDirectLinkInfo? = {
            WifiDirectLinkInfo(
                groupOwnerAddress = "127.0.0.1",
                localAddress = "127.0.0.1",
                isGroupOwner = !it,
                networkName = "DIRECT-vy-TestDevice",
                passphrase = "test-passphrase-123",
            )
        },
    ) : WifiDirectManager {
        var prepareCount = 0
        var closeCount = 0
        val prepareCalls = mutableListOf<Boolean>() // track isInitiator values

        override suspend fun prepareP2pLink(isInitiator: Boolean): WifiDirectLinkInfo? {
            prepareCount++
            prepareCalls.add(isInitiator)
            return linkInfoProvider(isInitiator)
        }

        override suspend fun closeP2pLink() {
            closeCount++
        }
    }

    /**
     * Simulates a Wi-Fi Direct manager where group formation takes multiple
     * calls to succeed (like real Android where groupFormed transitions from false to true).
     */
    private class DelayedFormationWifiDirectManager(
        override var isSupported: Boolean = true,
        override var isEnabled: Boolean = true,
        private val callsBeforeReady: Int = 2,
    ) : WifiDirectManager {
        private val callCount = AtomicInteger(0)
        var prepareCount = 0
        var closeCount = 0

        override suspend fun prepareP2pLink(isInitiator: Boolean): WifiDirectLinkInfo? {
            prepareCount++
            val count = callCount.incrementAndGet()
            // Simulate group not formed on first calls, then succeeding
            return if (count >= callsBeforeReady) {
                WifiDirectLinkInfo(
                    groupOwnerAddress = "127.0.0.1",
                    localAddress = "127.0.0.1",
                    isGroupOwner = !isInitiator,
                    networkName = "DIRECT-vy-DelayedDevice",
                    passphrase = "delayed-passphrase",
                )
            } else {
                null // Simulate group not yet formed
            }
        }

        override suspend fun closeP2pLink() {
            closeCount++
        }
    }

    private fun createConnectedPair(
        initiatorId: String = "initiator-dev",
        receiverId: String = "receiver-dev",
    ): Pair<PeerConnection, PeerConnection> {
        val secret = ByteArray(32) { 77 }
        val sessionKey = FrameCrypto.sessionKeyFromBytes(secret)

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
            initialKey = sessionKey,
            initialEncrypt = true,
            localDeviceId = initiatorId,
            tag = "TestSender",
            dispatcher = testDispatcher,
            peerDeviceId = receiverId,
            enableHeartbeat = false,
            remoteHost = "127.0.0.1",
            localHost = "127.0.0.1",
        )

        val receiver = PeerConnection(
            ws = recvWs,
            initialKey = sessionKey,
            initialEncrypt = true,
            localDeviceId = receiverId,
            tag = "TestReceiver",
            dispatcher = testDispatcher,
            peerDeviceId = initiatorId,
            enableHeartbeat = false,
            remoteHost = "127.0.0.1",
            localHost = "127.0.0.1",
        )

        initiator.start()
        receiver.start()

        return Pair(initiator, receiver)
    }

    @Test
    fun `successful transfer over WiFi Direct route when manager available`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()

        val senderWifiDirect = FakeWifiDirectManager(isSupported = true, isEnabled = true)
        val receiverWifiDirect = FakeWifiDirectManager(isSupported = true, isEnabled = true)

        val senderRunner = TransferRunner(dispatchers, senderWifiDirect)
        val receiverRunner = TransferRunner(dispatchers, receiverWifiDirect)

        val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
        val srcFile = tempDir.newFile("p2p_test.bin").apply { writeBytes(payload) }
        val destDir = tempDir.newFolder("p2p_dest")

        val item = TransferItem(
            index = 0,
            relativePath = "p2p_test.bin",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val rxJob = launch {
            val res = receiverRunner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
            assertEquals(1, res.size)
            assertEquals(VerificationResult.VERIFIED, res[0].verification)
            assertEquals(TransferRoute.WIFI_DIRECT, res.route)
        }

        val sendOutcome = senderRunner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "WifiDirectSender",
            transferId = "tid-p2p-success",
            resolveFile = { srcFile },
        )

        rxJob.join()

        assertTrue("Send outcome must be Done", sendOutcome is TransferRunner.SendOutcome.Done)
        val done = sendOutcome as TransferRunner.SendOutcome.Done
        assertEquals(TransferRoute.WIFI_DIRECT, done.route)
        assertEquals(VerificationResult.VERIFIED, done.results[0].verification)

        val receivedFile = File(destDir, "p2p_test.bin")
        assertTrue("Destination file must exist", receivedFile.exists())
        assertEquals(srcFile.length(), receivedFile.length())
        assertArrayEquals(payload, receivedFile.readBytes())

        assertTrue("Wi-Fi Direct link must have been prepared by sender", senderWifiDirect.prepareCount >= 1)
        assertTrue("Wi-Fi Direct link must have been prepared by receiver", receiverWifiDirect.prepareCount >= 1)
        assertTrue("Wi-Fi Direct link must have been torn down on completion", receiverWifiDirect.closeCount >= 1)

        // Verify correct isInitiator values
        assertTrue("Receiver must prepare with isInitiator=false", receiverWifiDirect.prepareCalls.contains(false))
        assertTrue("Sender must prepare with isInitiator=true", senderWifiDirect.prepareCalls.contains(true))

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `automatic fallback to LAN TCP when WiFi Direct preparation fails`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()

        // Receiver fails P2P link setup — simulates groupFormed=false timeout
        val receiverWifiDirect = FakeWifiDirectManager(isSupported = true, isEnabled = true) { null }
        val senderRunner = TransferRunner(dispatchers)
        val receiverRunner = TransferRunner(dispatchers, receiverWifiDirect)

        val payload = "LAN Fallback Raw TCP Test Payload".toByteArray()
        val srcFile = tempDir.newFile("fallback.txt").apply { writeBytes(payload) }
        val destDir = tempDir.newFolder("fallback_dest")

        val item = TransferItem(
            index = 0,
            relativePath = "fallback.txt",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val rxJob = launch {
            val res = receiverRunner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
            assertEquals(1, res.size)
            assertEquals(VerificationResult.VERIFIED, res[0].verification)
            assertEquals(TransferRoute.LAN_TCP, res.route)
        }

        val sendOutcome = senderRunner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "FallbackSender",
            transferId = "tid-lan-fallback",
            resolveFile = { srcFile },
        )

        rxJob.join()

        assertTrue(sendOutcome is TransferRunner.SendOutcome.Done)
        val done = sendOutcome as TransferRunner.SendOutcome.Done
        assertEquals(TransferRoute.LAN_TCP, done.route)
        assertEquals(VerificationResult.VERIFIED, done.results[0].verification)

        val destFile = File(destDir, "fallback.txt")
        assertTrue(destFile.exists())
        assertEquals(srcFile.readText(), destFile.readText())

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `large 5 MB binary payload preserves exact sha256 over raw TCP`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val size = 5 * 1024 * 1024
        val data = ByteArray(size) { (it xor (it shr 8)).toByte() }
        val md = MessageDigest.getInstance("SHA-256")
        val expectedSha = md.digest(data).joinToString("") { "%02x".format(it) }

        val srcFile = tempDir.newFile("large_test.bin").apply { writeBytes(data) }
        val destDir = tempDir.newFolder("large_dest")

        val item = TransferItem(
            index = 0,
            relativePath = "large_test.bin",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val rxJob = launch {
            val res = runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
            assertEquals(1, res.size)
            assertEquals(VerificationResult.VERIFIED, res[0].verification)
        }

        val sendOutcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "LargeSender",
            transferId = "tid-large-5mb",
            resolveFile = { srcFile },
        )

        rxJob.join()

        assertTrue(sendOutcome is TransferRunner.SendOutcome.Done)
        val destFile = File(destDir, "large_test.bin")
        assertTrue(destFile.exists())
        assertEquals(size.toLong(), destFile.length())

        val actualSha = md.digest(destFile.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("Cryptographic hash must match byte-for-byte", expectedSha, actualSha)

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `TcpDataReceiver rejects unauthorized sender with invalid auth token`() = runTest(testDispatcher) {
        val receiver = TcpDataReceiver(dispatchers)
        val port = receiver.bind()
        val tid = "tid-auth-reject"
        val correctToken = "token-secret-123"

        val rxJob = launch(Dispatchers.IO) {
            receiver.acceptAndReceive(
                transferId = tid,
                expectedAuthToken = correctToken,
                expectedPeerDeviceId = "trusted-peer-1",
                offerItems = emptyList(),
                destinationDir = tempDir.newFolder("auth_reject_dest"),
                chunkSize = 256 * 1024,
                onProgress = { _, _, _ -> },
                onItemVerified = {},
            )
        }

        // Connect with wrong auth token
        val socket = Socket("127.0.0.1", port)
        val out = BufferedOutputStream(socket.getOutputStream())
        val input = BufferedInputStream(socket.getInputStream())

        TcpFrameCodec.writeFrame(
            out,
            TcpFrame.Auth(
                seq = 1L,
                transferId = tid,
                senderDeviceId = "trusted-peer-1",
                authToken = "WRONG-TOKEN-ABC",
                route = TransferRoute.LAN_TCP,
            ),
        )

        val ackFrame = TcpFrameCodec.readFrame(input)
        assertTrue("Receiver must send AuthAck", ackFrame is TcpFrame.AuthAck)
        val ack = ackFrame as TcpFrame.AuthAck
        assertFalse("AuthAck ok must be false for invalid token", ack.ok)
        assertEquals("UNAUTHORIZED", ack.reason)

        socket.close()
        rxJob.join()
        receiver.close()
    }

    @Test
    fun `sender fallback uses LAN address not P2P address when P2P TCP fails`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()

        // Receiver succeeds at P2P group creation but sender fails to connect TCP over P2P.
        // The receiver advertises tcpHost=P2P address, fallbackHost=LAN address.
        // When P2P TCP fails, the sender must use fallbackHost, not tcpHost.
        val receiverWifiDirect = FakeWifiDirectManager(isSupported = true, isEnabled = true) { isInitiator ->
            if (!isInitiator) {
                WifiDirectLinkInfo(
                    groupOwnerAddress = "127.0.0.1",
                    localAddress = "127.0.0.1",
                    isGroupOwner = true,
                    networkName = "DIRECT-vy-TestDevice",
                    passphrase = "test-passphrase",
                )
            } else {
                // Sender P2P link returns info but TCP connection to P2P address will fail
                // because in tests 192.168.49.1 is not routable
                WifiDirectLinkInfo(
                    groupOwnerAddress = "192.168.49.1", // Not reachable in test
                    localAddress = "192.168.49.155",
                    isGroupOwner = false,
                    networkName = "DIRECT-vy-TestDevice",
                )
            }
        }

        val senderRunner = TransferRunner(dispatchers, receiverWifiDirect)
        val receiverRunner = TransferRunner(dispatchers, receiverWifiDirect)

        val payload = "Fallback after P2P TCP failure".toByteArray()
        val srcFile = tempDir.newFile("p2p_fallback.txt").apply { writeBytes(payload) }
        val destDir = tempDir.newFolder("p2p_fallback_dest")

        val item = TransferItem(
            index = 0,
            relativePath = "p2p_fallback.txt",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val rxJob = launch {
            val res = receiverRunner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
            assertEquals(1, res.size)
            assertEquals(VerificationResult.VERIFIED, res[0].verification)
            // Falls back to LAN TCP because P2P TCP connection fails
            assertEquals(TransferRoute.LAN_TCP, res.route)
        }

        val sendOutcome = senderRunner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "P2PFallbackSender",
            transferId = "tid-p2p-tcp-fallback",
            resolveFile = { srcFile },
        )

        rxJob.join()

        assertTrue(sendOutcome is TransferRunner.SendOutcome.Done)
        val done = sendOutcome as TransferRunner.SendOutcome.Done
        assertEquals(TransferRoute.LAN_TCP, done.route)

        val destFile = File(destDir, "p2p_fallback.txt")
        assertTrue(destFile.exists())
        assertEquals(srcFile.readText(), destFile.readText())

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `WiFi Direct credentials are included in TransferDecision when P2P group succeeds`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()

        val receiverWifiDirect = FakeWifiDirectManager(isSupported = true, isEnabled = true) { isInitiator ->
            if (!isInitiator) {
                WifiDirectLinkInfo(
                    groupOwnerAddress = "127.0.0.1",
                    localAddress = "127.0.0.1",
                    isGroupOwner = true,
                    networkName = "DIRECT-vy-TestDevice",
                    passphrase = "bootstrap-passphrase",
                )
            } else {
                WifiDirectLinkInfo(
                    groupOwnerAddress = "127.0.0.1",
                    localAddress = "127.0.0.1",
                    isGroupOwner = false,
                    networkName = "DIRECT-vy-TestDevice",
                )
            }
        }
        val senderRunner = TransferRunner(dispatchers, receiverWifiDirect)
        val receiverRunner = TransferRunner(dispatchers, receiverWifiDirect)

        val payload = "credential-test".toByteArray()
        val srcFile = tempDir.newFile("cred_test.bin").apply { writeBytes(payload) }
        val destDir = tempDir.newFolder("cred_dest")

        val item = TransferItem(
            index = 0,
            relativePath = "cred_test.bin",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val rxJob = launch {
            val res = receiverRunner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
            assertEquals(1, res.size)
        }

        val sendOutcome = senderRunner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "CredentialTestSender",
            transferId = "tid-cred-test",
            resolveFile = { srcFile },
        )

        rxJob.join()

        assertTrue(sendOutcome is TransferRunner.SendOutcome.Done)
        val done = sendOutcome as TransferRunner.SendOutcome.Done
        assertEquals(TransferRoute.WIFI_DIRECT, done.route)
        assertEquals(VerificationResult.VERIFIED, done.results[0].verification)

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `P2P link cleanup is called when transfer completes`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()

        val senderWifiDirect = FakeWifiDirectManager(isSupported = true, isEnabled = true)
        val receiverWifiDirect = FakeWifiDirectManager(isSupported = true, isEnabled = true)

        val senderRunner = TransferRunner(dispatchers, senderWifiDirect)
        val receiverRunner = TransferRunner(dispatchers, receiverWifiDirect)

        val payload = "cleanup-test".toByteArray()
        val srcFile = tempDir.newFile("cleanup.bin").apply { writeBytes(payload) }
        val destDir = tempDir.newFolder("cleanup_dest")

        val item = TransferItem(
            index = 0,
            relativePath = "cleanup.bin",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val rxJob = launch {
            receiverRunner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
        }

        senderRunner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "CleanupSender",
            transferId = "tid-cleanup",
            resolveFile = { srcFile },
        )

        rxJob.join()

        // Both sender and receiver must clean up their P2P links
        assertTrue("Sender must close P2P link", senderWifiDirect.closeCount >= 1)
        assertTrue("Receiver must close P2P link", receiverWifiDirect.closeCount >= 1)

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `sender resolves fallback host correctly when P2P fails`() = runTest(testDispatcher) {
        // Unit test for the resolveSenderFallbackHost method
        val runner = TransferRunner(dispatchers)

        // Simulate a TransferDecision with P2P tcpHost and LAN fallbackHost
        val decision = LinkMessage.TransferDecision(
            transferId = "test",
            accept = true,
            route = TransferRoute.WIFI_DIRECT.name,
            tcpHost = "192.168.49.1",     // P2P address (not reachable on LAN)
            fallbackHost = "192.168.31.47", // LAN address
            tcpPort = 38169,
            authToken = "token",
        )

        // Use a real PeerConnection from createConnectedPair — remoteHost defaults to "127.0.0.1"
        val (session, _) = createConnectedPair()

        val result = runner.resolveSenderFallbackHost(decision, session)
        assertTrue("Fallback must resolve to Valid", result is TransferRunner.TargetHostResolution.Valid)
        val valid = result as TransferRunner.TargetHostResolution.Valid
        // Must use fallbackHost (192.168.31.47), not tcpHost (192.168.49.1 P2P address)
        assertEquals("Must use fallbackHost, not tcpHost", "192.168.31.47", valid.host)
        assertTrue("Source must indicate P2P fallback", valid.source.contains("fallback"))

        session.close()
    }
}

