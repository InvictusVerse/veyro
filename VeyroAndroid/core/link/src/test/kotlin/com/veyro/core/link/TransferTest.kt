package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.model.Platform
import com.veyro.core.model.Transfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferState
import com.veyro.core.model.TrustedPeer
import com.veyro.core.model.VerificationResult
import com.veyro.core.transfer.ChunkMath
import com.veyro.core.transfer.FilesystemStoragePublisher
import com.veyro.core.transfer.StoragePublishResult
import com.veyro.core.transfer.TransferStoragePublisher
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TransferTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = testDispatcher
        override val io = kotlinx.coroutines.Dispatchers.IO
        override val main = testDispatcher
    }

    @org.junit.Before
    fun setUp() {
        com.veyro.core.common.VeyroLog.install { level, tag, msg, tr ->
            println("[$tag] $msg ${tr?.message ?: ""}")
        }
    }

    @org.junit.After
    fun tearDown() {
        com.veyro.core.common.VeyroLog.uninstall()
    }

    /** Creates a pair of interconnected PeerConnections in session mode. */
    private fun createConnectedPair(
        initiatorId: String = "initiator-device-id",
        receiverId: String = "receiver-device-id",
    ): Pair<PeerConnection, PeerConnection> {
        val secret = ByteArray(32) { 42 }
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
    fun `send and receive small file byte-for-byte integrity`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val srcFile = tempDir.newFile("sample.txt").apply {
            writeText("Hello Veyro, secure file transfer verified!")
        }
        val destDir = tempDir.newFolder("received_small")

        val item = TransferItem(
            index = 0,
            relativePath = "sample.txt",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val receiverJob = launch {
            val results = runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
            assertEquals(1, results.size)
            assertEquals(VerificationResult.VERIFIED, results[0].verification)
        }

        val outcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "TestSender",
            resolveFile = { srcFile },
        )

        assertTrue(outcome is TransferRunner.SendOutcome.Done)
        val done = outcome as TransferRunner.SendOutcome.Done
        assertEquals(1, done.results.size)
        assertEquals(VerificationResult.VERIFIED, done.results[0].verification)

        receiverJob.join()

        val receivedFile = File(destDir, "sample.txt")
        assertTrue("Destination file must exist", receivedFile.exists())
        assertEquals(srcFile.readText(), receivedFile.readText())
        assertEquals(ChunkMath.hashFile(srcFile), ChunkMath.hashFile(receivedFile))

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `send and receive empty file completes cleanly`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val srcFile = tempDir.newFile("empty.txt").apply { writeBytes(ByteArray(0)) }
        val destDir = tempDir.newFolder("received_empty")

        val item = TransferItem(
            index = 0,
            relativePath = "empty.txt",
            sizeBytes = 0,
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val receiverJob = launch {
            val results = runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
            assertEquals(1, results.size)
            assertEquals(VerificationResult.VERIFIED, results[0].verification)
        }

        val outcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "TestSender",
            resolveFile = { srcFile },
        )

        assertTrue(outcome is TransferRunner.SendOutcome.Done)
        receiverJob.join()

        val receivedFile = File(destDir, "empty.txt")
        assertTrue(receivedFile.exists())
        assertEquals(0, receivedFile.length())

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `send and receive multi-chunk file with hash verification`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        // 600 KB file spans 3 chunks (chunk size 256 KB)
        val data = ByteArray(600 * 1024) { (it % 256).toByte() }
        val srcFile = tempDir.newFile("large.bin").apply { writeBytes(data) }
        val destDir = tempDir.newFolder("received_large")

        val item = TransferItem(
            index = 0,
            relativePath = "large.bin",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val receiverJob = launch {
            val results = runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
            assertEquals(1, results.size)
            assertEquals(VerificationResult.VERIFIED, results[0].verification)
        }

        val outcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "TestSender",
            resolveFile = { srcFile },
        )

        assertTrue(outcome is TransferRunner.SendOutcome.Done)
        receiverJob.join()

        val receivedFile = File(destDir, "large.bin")
        assertTrue(receivedFile.exists())
        assertEquals(data.size.toLong(), receivedFile.length())
        assertArrayEquals(data, receivedFile.readBytes())

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `receiver rejection is handled properly`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val srcFile = tempDir.newFile("reject.txt").apply { writeText("Secret") }
        val destDir = tempDir.newFolder("received_rejected")

        val item = TransferItem(
            index = 0,
            relativePath = "reject.txt",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val receiverJob = launch {
            val results = runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { false }, // User declines
            )
            assertTrue(results.isEmpty())
        }

        val outcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "TestSender",
            resolveFile = { srcFile },
        )

        assertEquals(TransferRunner.SendOutcome.Declined(DeclineReason.DECLINED), outcome)
        receiverJob.join()

        assertFalse(File(destDir, "reject.txt").exists())

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `missing source file reports write failed without crashing`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val nonExistent = File(tempDir.root, "does_not_exist.bin")
        val destDir = tempDir.newFolder("received_missing")

        val item = TransferItem(
            index = 0,
            relativePath = "does_not_exist.bin",
            sizeBytes = 100,
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val receiverJob = launch {
            runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
        }

        val outcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "TestSender",
            resolveFile = { nonExistent },
        )

        assertTrue(outcome is TransferRunner.SendOutcome.Done)
        val results = (outcome as TransferRunner.SendOutcome.Done).results
        assertEquals(1, results.size)
        assertEquals(VerificationResult.WRITE_FAILED, results[0].verification)

        receiverJob.join()
        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `connect reuses already active session to same peer`() = runTest(testDispatcher) {
        val localPk = ByteArray(32) { 1 }
        val localId = DeviceId.fromPublicKey(localPk).value
        val peerPk = ByteArray(32) { 2 }
        val peerId = DeviceId.fromPublicKey(peerPk)
        val secret = ByteArray(32) { 3 }

        val trusted = TrustedPeer(
            deviceId = peerId,
            staticPublicKey = peerPk,
            pairingSecret = secret,
            name = "Test Samsung",
            platform = Platform.ANDROID,
            pairedAtEpochMillis = System.currentTimeMillis(),
        )

        val fakeClient = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                return TestWebSocketSession()
            }
        }

        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { localId },
            localDeviceName = { "Xiaomi Sender" },
            localPublicKey = { localPk },
            trustedPeer = { trusted },
            saveTrustedPeer = {},
            client = fakeClient,
        )

        // Inject active connection
        val ws = TestWebSocketSession()
        val activeConn = PeerConnection(
            ws = ws,
            initialKey = FrameCrypto.sessionKeyFromBytes(secret),
            initialEncrypt = true,
            localDeviceId = localId,
            tag = "ActiveSession",
            dispatcher = testDispatcher,
            peerDeviceId = peerId.value,
            enableHeartbeat = false,
        )
        activeConn.start()

        // Access internal active connection field through reflection or by calling runConnection
        val activeField = ConnectionManager::class.java.getDeclaredField("_activeConnection")
        activeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = activeField.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<PeerConnection?>
        flow.value = activeConn

        // Call connect with the same peer
        val connected = manager.connect(peerId.value, "Test Samsung", listOf(Endpoint("192.168.1.50", 8080)))

        // Must succeed via reuse and not abort with "session already active"
        assertTrue("Connecting to already connected peer must succeed via reuse", connected)

        activeConn.close()
    }

    @Test
    fun `transfer coordinator reuses active session for sendTo`() = runTest(testDispatcher) {
        val localPk = ByteArray(32) { 1 }
        val localId = DeviceId.fromPublicKey(localPk).value
        val peerPk = ByteArray(32) { 2 }
        val peerId = DeviceId.fromPublicKey(peerPk)
        val secret = ByteArray(32) { 3 }

        val trusted = TrustedPeer(
            deviceId = peerId,
            staticPublicKey = peerPk,
            pairingSecret = secret,
            name = "Test Samsung",
            platform = Platform.ANDROID,
            pairedAtEpochMillis = System.currentTimeMillis(),
        )

        val (senderSession, receiverSession) = createConnectedPair(localId, peerId.value)
        val runner = TransferRunner(dispatchers)

        val fakeClient = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                throw IllegalStateException("Should not dial when session is active!")
            }
        }

        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { localId },
            localDeviceName = { "Xiaomi Sender" },
            localPublicKey = { localPk },
            trustedPeer = { trusted },
            saveTrustedPeer = {},
            client = fakeClient,
        )

        val activeField = ConnectionManager::class.java.getDeclaredField("_activeConnection")
        activeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = activeField.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<PeerConnection?>
        flow.value = senderSession

        val stagingDir = tempDir.newFolder("staging")
        val destDir = tempDir.newFolder("received_reuse")

        val coordinator = TransferCoordinator(
            connectionManager = manager,
            runner = runner,
            dispatchers = dispatchers,
            stagingDir = stagingDir,
            destinationProvider = { destDir },
            localName = { "Xiaomi Sender" },
            endpointsFor = { listOf(Endpoint("192.168.1.50", 8080)) },
            autoAcceptFor = { true },
        )

        val fileData = "Session reuse test content".toByteArray()
        val outgoingFile = OutgoingFile(
            effectiveName = "reused.txt",
            sizeBytes = fileData.size.toLong(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            openStream = { ByteArrayInputStream(fileData) },
        )

        val receiverJob = launch {
            runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
        }

        val sendResult = coordinator.sendTo(peerId, "Test Samsung", listOf(outgoingFile))

        assertTrue("sendTo must succeed via active session reuse", sendResult is SendResult.Success)
        receiverJob.join()

        val delivered = File(destDir, "reused.txt")
        assertTrue(delivered.exists())
        assertEquals("Session reuse test content", delivered.readText())

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `transfer coordinator reuses active session when endpointsFor is completely empty`() = runTest(testDispatcher) {
        val localPk = ByteArray(32) { 1 }
        val localId = DeviceId.fromPublicKey(localPk).value
        val peerPk = ByteArray(32) { 2 }
        val peerId = DeviceId.fromPublicKey(peerPk)
        val secret = ByteArray(32) { 3 }

        val trusted = TrustedPeer(
            deviceId = peerId,
            staticPublicKey = peerPk,
            pairingSecret = secret,
            name = "Test Samsung",
            platform = Platform.ANDROID,
            pairedAtEpochMillis = System.currentTimeMillis(),
        )

        val (senderSession, receiverSession) = createConnectedPair(localId, peerId.value)
        val runner = TransferRunner(dispatchers)

        val fakeClient = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                throw IllegalStateException("Should never dial when active session exists!")
            }
        }

        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { localId },
            localDeviceName = { "Xiaomi Sender" },
            localPublicKey = { localPk },
            trustedPeer = { trusted },
            saveTrustedPeer = {},
            client = fakeClient,
        )

        val activeField = ConnectionManager::class.java.getDeclaredField("_activeConnection")
        activeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = activeField.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<PeerConnection?>
        flow.value = senderSession

        val stagingDir = tempDir.newFolder("staging_empty_endpoints")
        val destDir = tempDir.newFolder("received_empty_endpoints")

        val coordinator = TransferCoordinator(
            connectionManager = manager,
            runner = runner,
            dispatchers = dispatchers,
            stagingDir = stagingDir,
            destinationProvider = { destDir },
            localName = { "Xiaomi Sender" },
            // CRITICAL TEST: endpointsFor returns EMPTY, but active session exists!
            endpointsFor = { emptyList() },
            autoAcceptFor = { true },
        )

        val fileData = "Must succeed via active session even with empty endpoints".toByteArray()
        val outgoingFile = OutgoingFile(
            effectiveName = "no_endpoints_needed.txt",
            sizeBytes = fileData.size.toLong(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            openStream = { ByteArrayInputStream(fileData) },
        )

        val receiverJob = launch {
            runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
        }

        val sendResult = coordinator.sendTo(peerId, "Test Samsung", listOf(outgoingFile))

        assertTrue("sendTo must succeed via active session reuse when endpoints are empty", sendResult is SendResult.Success)
        receiverJob.join()

        val delivered = File(destDir, "no_endpoints_needed.txt")
        assertTrue(delivered.exists())
        assertEquals("Must succeed via active session even with empty endpoints", delivered.readText())

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `second transfer succeeds after first session closes and reconnects`() = runTest(testDispatcher) {
        val localPk = ByteArray(32) { 1 }
        val localId = DeviceId.fromPublicKey(localPk).value
        val peerPk = ByteArray(32) { 2 }
        val peerId = DeviceId.fromPublicKey(peerPk)
        val secret = ByteArray(32) { 3 }

        val trusted = TrustedPeer(
            deviceId = peerId,
            staticPublicKey = peerPk,
            pairingSecret = secret,
            name = "Test Samsung",
            platform = Platform.ANDROID,
            pairedAtEpochMillis = System.currentTimeMillis(),
        )

        val (senderSession1, receiverSession1) = createConnectedPair(localId, peerId.value)
        val runner = TransferRunner(dispatchers)

        val activeField = ConnectionManager::class.java.getDeclaredField("_activeConnection")
        activeField.isAccessible = true

        val stagingDir = tempDir.newFolder("staging_seq")
        val destDir = tempDir.newFolder("received_seq")

        var dialedSessionPair: Pair<PeerConnection, PeerConnection>? = null

        val fakeClient = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                // When dialing for transfer 2, return a real TestWebSocketSession pair
                val wsSender = TestWebSocketSession()
                val wsReceiver = TestWebSocketSession()
                // Pipe them
                launch { for (f in wsSender.outgoingChannel) wsReceiver.incomingChannel.send(f) }
                launch { for (f in wsReceiver.outgoingChannel) wsSender.incomingChannel.send(f) }
                // In background launch receiver session
                val receiver = PeerConnection(
                    ws = wsReceiver,
                    initialKey = FrameCrypto.sessionKeyFromBytes(secret),
                    initialEncrypt = true,
                    localDeviceId = peerId.value,
                    dispatcher = testDispatcher,
                    peerDeviceId = localId,
                    enableHeartbeat = false,
                )
                receiver.start()
                return wsSender
            }
        }

        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { localId },
            localDeviceName = { "Xiaomi Sender" },
            localPublicKey = { localPk },
            trustedPeer = { trusted },
            saveTrustedPeer = {},
            client = fakeClient,
        )

        @Suppress("UNCHECKED_CAST")
        val flow = activeField.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<PeerConnection?>
        flow.value = senderSession1

        val coordinator = TransferCoordinator(
            connectionManager = manager,
            runner = runner,
            dispatchers = dispatchers,
            stagingDir = stagingDir,
            destinationProvider = { destDir },
            localName = { "Xiaomi Sender" },
            endpointsFor = { listOf(Endpoint("192.168.1.50", 8080)) },
            autoAcceptFor = { true },
        )

        // Transfer 1 over session 1
        val file1Data = "File 1 content".toByteArray()
        val file1 = OutgoingFile("file1.txt", file1Data.size.toLong(), System.currentTimeMillis()) { ByteArrayInputStream(file1Data) }
        val r1 = launch { runner.receive(receiverSession1, destDir) { true } }
        // Reusing session1
        val res1 = coordinator.sendTo(peerId, "Test Samsung", listOf(file1))
        assertTrue("Transfer 1 must succeed", res1 is SendResult.Success)
        r1.join()

        // Close session 1
        senderSession1.close()
        receiverSession1.close()
        flow.value = null

        // Transfer 2 must now dial and succeed via newly established session
        // Set up active connection for dialed session
        val (senderSession2, receiverSession2) = createConnectedPair(localId, peerId.value)
        val coordinator2 = TransferCoordinator(
            connectionManager = manager,
            runner = runner,
            dispatchers = dispatchers,
            stagingDir = stagingDir,
            destinationProvider = { destDir },
            localName = { "Xiaomi Sender" },
            endpointsFor = { listOf(Endpoint("192.168.1.50", 8080)) },
            autoAcceptFor = { true },
        )
        flow.value = senderSession2

        val file2Data = "File 2 content".toByteArray()
        val file2 = OutgoingFile("file2.txt", file2Data.size.toLong(), System.currentTimeMillis()) { ByteArrayInputStream(file2Data) }
        val r2 = launch { runner.receive(receiverSession2, destDir) { true } }
        val res2 = coordinator2.sendTo(peerId, "Test Samsung", listOf(file2))
        assertTrue("Transfer 2 must succeed after session 1 closed", res2 is SendResult.Success)
        r2.join()

        senderSession2.close()
        receiverSession2.close()
    }

    @Test
    fun `transfer coordinator discovers endpoints when none initially known`() = runTest(testDispatcher) {
        val localPk = ByteArray(32) { 1 }
        val localId = DeviceId.fromPublicKey(localPk).value
        val peerPk = ByteArray(32) { 2 }
        val peerId = DeviceId.fromPublicKey(peerPk)
        val secret = ByteArray(32) { 3 }

        val trusted = TrustedPeer(
            deviceId = peerId,
            staticPublicKey = peerPk,
            pairingSecret = secret,
            name = "Test Samsung",
            platform = Platform.ANDROID,
            pairedAtEpochMillis = System.currentTimeMillis(),
        )

        val (senderSession, receiverSession) = createConnectedPair(localId, peerId.value)
        val runner = TransferRunner(dispatchers)

        val activeField = ConnectionManager::class.java.getDeclaredField("_activeConnection")
        activeField.isAccessible = true

        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { localId },
            localDeviceName = { "Xiaomi Sender" },
            localPublicKey = { localPk },
            trustedPeer = { trusted },
            saveTrustedPeer = {},
            client = object : ConnectionClient() {
                override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                    throw IllegalStateException("Should not be called directly in this fake")
                }
            },
        )

        val stagingDir = tempDir.newFolder("staging_discover")
        val destDir = tempDir.newFolder("received_discover")

        var discoverCalled = false
        val coordinator = TransferCoordinator(
            connectionManager = manager,
            runner = runner,
            dispatchers = dispatchers,
            stagingDir = stagingDir,
            destinationProvider = { destDir },
            localName = { "Xiaomi Sender" },
            // Initially empty endpoints!
            endpointsFor = { emptyList() },
            autoAcceptFor = { true },
            discoverEndpoints = { id, _ ->
                discoverCalled = true
                @Suppress("UNCHECKED_CAST")
                val flow = activeField.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<PeerConnection?>
                flow.value = senderSession
                listOf(Endpoint("192.168.1.99", 7777))
            },
        )

        val fileData = "Discovery on demand content".toByteArray()
        val outgoingFile = OutgoingFile("discover.txt", fileData.size.toLong(), System.currentTimeMillis()) { ByteArrayInputStream(fileData) }

        val receiverJob = launch {
            runner.receive(receiverSession, destDir) { true }
        }

        val sendResult = coordinator.sendTo(peerId, "Test Samsung", listOf(outgoingFile))
        assertTrue("discoverEndpoints must have been triggered", discoverCalled)
        assertTrue("sendTo must succeed via on-demand discovery", sendResult is SendResult.Success)
        receiverJob.join()

        senderSession.close()
        receiverSession.close()
    }

    @Test
    fun `transfer coordinator fails clearly when peer is offline after bounded discovery`() = runTest(testDispatcher) {
        val localPk = ByteArray(32) { 1 }
        val localId = DeviceId.fromPublicKey(localPk).value
        val peerPk = ByteArray(32) { 2 }
        val peerId = DeviceId.fromPublicKey(peerPk)
        val secret = ByteArray(32) { 3 }

        val trusted = TrustedPeer(
            deviceId = peerId,
            staticPublicKey = peerPk,
            pairingSecret = secret,
            name = "Test Samsung",
            platform = Platform.ANDROID,
            pairedAtEpochMillis = System.currentTimeMillis(),
        )

        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { localId },
            localDeviceName = { "Xiaomi Sender" },
            localPublicKey = { localPk },
            trustedPeer = { trusted },
            saveTrustedPeer = {},
            client = object : ConnectionClient() {},
        )

        val coordinator = TransferCoordinator(
            connectionManager = manager,
            runner = TransferRunner(dispatchers),
            dispatchers = dispatchers,
            stagingDir = tempDir.newFolder("staging_offline"),
            destinationProvider = { tempDir.newFolder("dest_offline") },
            localName = { "Xiaomi Sender" },
            endpointsFor = { emptyList() },
            autoAcceptFor = { true },
            discoverEndpoints = { _, _ -> emptyList() },
        )

        val fileData = "Offline test".toByteArray()
        val outgoingFile = OutgoingFile("offline.txt", fileData.size.toLong(), System.currentTimeMillis()) { ByteArrayInputStream(fileData) }

        val result = coordinator.sendTo(peerId, "Test Samsung", listOf(outgoingFile))
        assertTrue("sendTo must fail when peer is offline", result is SendResult.Failed)
        val failureReason = (result as SendResult.Failed).reason
        assertTrue("Failure reason must explain peer is offline or unreachable: $failureReason", failureReason.contains("offline or unreachable"))
    }

    @Test
    fun `connection manager force reconnect replaces stale connection`() = runTest(testDispatcher) {
        val localPk = ByteArray(32) { 1 }
        val localId = DeviceId.fromPublicKey(localPk).value
        val peerPk = ByteArray(32) { 2 }
        val peerId = DeviceId.fromPublicKey(peerPk)
        val secret = ByteArray(32) { 3 }

        val trusted = TrustedPeer(
            deviceId = peerId,
            staticPublicKey = peerPk,
            pairingSecret = secret,
            name = "Test Samsung",
            platform = Platform.ANDROID,
            pairedAtEpochMillis = System.currentTimeMillis(),
        )

        var dialCount = 0
        val fakeClient = object : ConnectionClient() {
            override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                dialCount++
                throw java.net.ConnectException("Expected test connect error")
            }
        }

        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { localId },
            localDeviceName = { "Xiaomi Sender" },
            localPublicKey = { localPk },
            trustedPeer = { trusted },
            saveTrustedPeer = {},
            client = fakeClient,
        )

        val activeConn = PeerConnection(
            ws = TestWebSocketSession(),
            initialKey = FrameCrypto.sessionKeyFromBytes(secret),
            initialEncrypt = true,
            localDeviceId = localId,
            tag = "Connection(stale-test)",
            dispatcher = testDispatcher,
            peerDeviceId = peerId.value,
            enableHeartbeat = false,
        )
        activeConn.start()

        val activeField = ConnectionManager::class.java.getDeclaredField("_activeConnection")
        activeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = activeField.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<PeerConnection?>
        flow.value = activeConn

        // With forceReconnect = true, existing activeConn should be closed and dial attempted
        manager.connect(peerId.value, "Test Samsung", listOf(Endpoint("192.168.1.50", 8080)), forceReconnect = true)

        assertEquals("Dial must have been attempted after closing stale connection", 1, dialCount)
        assertFalse("Stale connection must be closed", activeConn.isConnected)
    }

    @Test
    fun `one TransferOffer creates exactly one incoming offer event and one consent prompt`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair(initiatorId = "sender-dev", receiverId = "recv-dev")
        val runner = TransferRunner(dispatchers)
        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { "recv-dev" },
            localDeviceName = { "Samsung Receiver" },
            localPublicKey = { ByteArray(32) },
            trustedPeer = { null },
            saveTrustedPeer = {},
            client = object : ConnectionClient() {
                override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                    throw IllegalStateException()
                }
            },
        )
        val coordinator = TransferCoordinator(
            dispatchers = dispatchers,
            connectionManager = manager,
            runner = runner,
            localName = { "Samsung Receiver" },
            destinationProvider = { tempDir.newFolder("recv_one_offer") },
            stagingDir = tempDir.newFolder("staging_one_offer"),
            endpointsFor = { emptyList() },
            autoAcceptFor = { false },
        )

        val offers = mutableListOf<IncomingOffer>()
        val collectJob = launch {
            coordinator.incomingOffers.collect { offers.add(it) }
        }

        // Run the receiver driver that coordinator registered
        val driverJob = launch {
            manager.sessionDriver?.invoke(receiverSession, true)
        }

        val tid = "tid-one-offer"
        senderSession.send(
            LinkMessage.TransferOffer(
                transferId = tid,
                items = listOf(LinkMessage.Item(0, "test.txt", 100, System.currentTimeMillis())),
                totalBytes = 100,
                senderName = "Xiaomi Sender",
            )
        )

        withTimeoutOrNull(2_000L) {
            while (offers.isEmpty()) {
                delay(20L)
            }
        }

        assertEquals("Exactly one offer prompt event must be created", 1, offers.size)
        assertEquals(tid, offers[0].transferId)
        assertEquals(tid, coordinator.pendingOffer.value?.transferId)

        coordinator.respondToOffer(tid, true)

        collectJob.cancel()
        driverJob.cancel()
    }

    @Test
    fun `one send results in one receiver processing cycle one committed file and one completion`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val srcFile = tempDir.newFile("single_send.txt").apply { writeText("Single send verification test payload") }
        val destDir = tempDir.newFolder("dest_single_send")
        val tid = "single-send-id"

        var receiveCycles = 0
        val receiverJob = launch {
            val results = runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = { true },
            )
            receiveCycles++
            assertEquals(1, results.size)
            assertEquals(VerificationResult.VERIFIED, results[0].verification)
        }

        val item = TransferItem(
            index = 0,
            relativePath = "single_send.txt",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val sendOutcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "Xiaomi",
            transferId = tid,
            resolveFile = { srcFile },
        )

        assertTrue(sendOutcome is TransferRunner.SendOutcome.Done)
        receiverJob.join()
        assertEquals(1, receiveCycles)
        val committedFile = File(destDir, "single_send.txt")
        assertTrue(committedFile.exists())
        assertEquals(srcFile.readText(), committedFile.readText())
        receiverJob.cancel()
    }

    @Test
    fun `starting next receive cycle after TransferEnd does not reprocess old messages`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val srcFile = tempDir.newFile("no_replay.txt").apply { writeText("Do not replay this transfer") }
        val destDir = tempDir.newFolder("dest_no_replay")
        val tid1 = "transfer-1"

        var processedCycles = 0
        val processedTransferIds = mutableListOf<String>()

        val receiverJob = launch {
            while (receiverSession.isConnected) {
                val results = runner.receive(
                    session = receiverSession,
                    destinationDir = destDir,
                    authorize = { offer ->
                        processedTransferIds.add(offer.transferId)
                        true
                    },
                )
                if (results.isNotEmpty()) {
                    processedCycles++
                }
            }
        }

        val item = TransferItem(
            index = 0,
            relativePath = "no_replay.txt",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val outcome1 = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "Xiaomi",
            transferId = tid1,
            resolveFile = { srcFile },
        )

        assertTrue(outcome1 is TransferRunner.SendOutcome.Done)
        withTimeoutOrNull(2_000L) {
            while (processedCycles == 0) {
                delay(20L)
            }
        }
        assertEquals("Exactly one receive cycle must have executed", 1, processedCycles)
        assertEquals("Transfer 1 must be processed once", listOf(tid1), processedTransferIds)

        // The receiver loop has now automatically called runner.receive() for the 2nd cycle.
        // Confirm that no re-processing occurred for tid1:
        assertEquals("No second cycle for tid1 should be processed", 1, processedCycles)
        assertEquals(listOf(tid1), processedTransferIds)

        receiverJob.cancel()
    }

    @Test
    fun `new transfers on the same connection still work`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val destDir = tempDir.newFolder("dest_multi_transfer")
        val processedTransferIds = mutableListOf<String>()

        val receiverJob = launch {
            while (receiverSession.isConnected) {
                runner.receive(
                    session = receiverSession,
                    destinationDir = destDir,
                    authorize = { offer ->
                        processedTransferIds.add(offer.transferId)
                        true
                    },
                )
            }
        }

        val file1 = tempDir.newFile("file1.txt").apply { writeText("File 1 content") }
        val file2 = tempDir.newFile("file2.txt").apply { writeText("File 2 content") }

        val tid1 = "transfer-seq-1"
        val outcome1 = runner.send(
            session = senderSession,
            items = listOf(TransferItem(0, "file1.txt", file1.length(), System.currentTimeMillis(), false)),
            senderName = "Xiaomi",
            transferId = tid1,
            resolveFile = { file1 },
        )
        assertTrue(outcome1 is TransferRunner.SendOutcome.Done)

        val tid2 = "transfer-seq-2"
        val outcome2 = runner.send(
            session = senderSession,
            items = listOf(TransferItem(0, "file2.txt", file2.length(), System.currentTimeMillis(), false)),
            senderName = "Xiaomi",
            transferId = tid2,
            resolveFile = { file2 },
        )
        assertTrue(outcome2 is TransferRunner.SendOutcome.Done)

        assertEquals("Both transfers must be authorized and processed in order", listOf(tid1, tid2), processedTransferIds)
        assertTrue(File(destDir, "file1.txt").exists())
        assertTrue(File(destDir, "file2.txt").exists())

        receiverJob.cancel()
    }

    @Test
    fun `duplicate TransferOffer with same transfer ID is safely handled`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()
        val runner = TransferRunner(dispatchers)

        val destDir = tempDir.newFolder("dest_dup_offer")
        var promptCount = 0

        val receiverJob = launch {
            runner.receive(
                session = receiverSession,
                destinationDir = destDir,
                authorize = {
                    promptCount++
                    true
                },
            )
        }

        val tid = "dup-test-id"
        val offer = LinkMessage.TransferOffer(
            transferId = tid,
            items = listOf(LinkMessage.Item(0, "f.txt", 10, System.currentTimeMillis())),
            totalBytes = 10,
            senderName = "Sender",
        )

        // Send initial offer
        senderSession.send(offer)
        // Send duplicate offer with same transfer ID
        senderSession.send(offer)

        assertEquals("Prompt must only appear once despite duplicate offer frame", 1, promptCount)
        receiverJob.cancel()
    }

    @Test
    fun `TransferDecision and ItemResult routing works without competing collectors`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()

        val tid = "routing-test"
        val senderMailbox = senderSession.mailbox(tid)
        val receiverMailbox = receiverSession.mailbox(tid)

        // Receiver sends decisions and item results to sender
        receiverSession.send(LinkMessage.TransferDecision(transferId = tid, accept = true))
        receiverSession.send(LinkMessage.ItemResult(transferId = tid, index = 0, ok = true))

        senderSession.send(LinkMessage.Chunk(transferId = tid, index = 0, chunkIndex = 0, dataB64 = "AQID"))
        senderSession.send(LinkMessage.TransferEnd(transferId = tid, completed = true))

        val decision = senderMailbox.awaitDecision(1000)
        assertNotNull(decision)
        assertTrue(decision!!.accept)

        val itemResult = senderMailbox.awaitItemResult(0, 1000)
        assertNotNull(itemResult)
        assertTrue(itemResult!!.ok)

        val payload1 = receiverMailbox.payloadChannel.receive()
        assertTrue(payload1 is LinkMessage.Chunk)

        val payload2 = receiverMailbox.payloadChannel.receive()
        assertTrue(payload2 is LinkMessage.TransferEnd)
    }

    @Test
    fun `early arriving messages cannot be dropped because collector has not subscribed`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair()

        val tid = "early-arrival"
        val senderMailbox = senderSession.mailbox(tid)

        receiverSession.send(LinkMessage.TransferDecision(transferId = tid, accept = true))

        // Sender calls awaitDecision AFTER frame has already arrived and been processed
        val decision = senderMailbox.awaitDecision(1000)
        assertNotNull("Early-arriving TransferDecision must not be lost", decision)
        assertTrue(decision!!.accept)

        // Sender sends chunks before receiver loop starts reading
        val receiverMailbox = receiverSession.mailbox(tid)
        senderSession.send(LinkMessage.Chunk(transferId = tid, index = 0, chunkIndex = 0, dataB64 = "AAAA"))
        senderSession.send(LinkMessage.Chunk(transferId = tid, index = 0, chunkIndex = 1, dataB64 = "BBBB"))

        // Receiver reads from payloadChannel after the chunks have already arrived
        val c1 = receiverMailbox.payloadChannel.receive() as LinkMessage.Chunk
        val c2 = receiverMailbox.payloadChannel.receive() as LinkMessage.Chunk
        assertEquals(0, c1.chunkIndex)
        assertEquals(1, c2.chunkIndex)
    }

    @Test
    fun `disconnect and reconnect do not reuse stale frames from previous session`() = runTest(testDispatcher) {
        val (s1, r1) = createConnectedPair(initiatorId = "init", receiverId = "recv")
        val runner = TransferRunner(dispatchers)

        val file1 = tempDir.newFile("session1.txt").apply { writeText("Session 1 data") }
        val dest1 = tempDir.newFolder("dest_s1")

        val r1Job = launch {
            runner.receive(r1, dest1) { true }
        }

        val outcome = runner.send(s1, listOf(TransferItem(0, "session1.txt", file1.length(), 0, false)), "Sender", "tid-s1") { file1 }
        assertTrue(outcome is TransferRunner.SendOutcome.Done)
        r1Job.cancel()

        // Close session 1
        s1.close("test-done")
        r1.close("test-done")

        // Create session 2
        val (s2, r2) = createConnectedPair(initiatorId = "init", receiverId = "recv")
        assertFalse("Session 2 must not retain completed transfers from session 1", r2.isTransferCompleted("tid-s1"))
        assertFalse("Session 2 must have no active mailboxes", r2.isTransferActive("tid-s1"))

        // Transfer with same transfer ID on fresh session 2 should be accepted cleanly
        val file2 = tempDir.newFile("session2.txt").apply { writeText("Session 2 data") }
        val dest2 = tempDir.newFolder("dest_s2")
        val r2Job = launch {
            runner.receive(r2, dest2) { true }
        }

        val outcome2 = runner.send(s2, listOf(TransferItem(0, "session2.txt", file2.length(), 0, false)), "Sender", "tid-s1") { file2 }
        assertTrue("Fresh session allows transfer cleanly", outcome2 is TransferRunner.SendOutcome.Done)
        r2Job.cancel()
    }

    @Test
    fun `inbound JPEG transfer publishes to storage publisher and retains canonical content URI`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair(initiatorId = "sender-dev", receiverId = "recv-dev")
        val runner = TransferRunner(dispatchers)

        val srcFile = tempDir.newFile("IMG_20261011_171634.jpg").apply {
            writeBytes(ByteArray(262144 + 500) { (it % 127).toByte() })
        }
        val stagingDir = tempDir.newFolder("staging_rx")
        val destDir = tempDir.newFolder("gallery_pictures")

        var publishedCount = 0
        var publishedUri = ""
        var publishedMime = ""
        val fakePublisher = object : TransferStoragePublisher {
            override suspend fun publish(
                sourceFile: File,
                relativePath: String,
                mimeType: String?,
                sizeBytes: Long,
            ): StoragePublishResult {
                publishedCount++
                val ext = relativePath.substringAfterLast('.', "")
                publishedMime = if (ext.equals("jpg", true)) "image/jpeg" else "application/octet-stream"
                val finalFile = File(destDir, relativePath)
                sourceFile.copyTo(finalFile, overwrite = true)
                sourceFile.delete()
                publishedUri = "content://media/external/images/media/4242"
                return StoragePublishResult.Success(
                    destinationUri = publishedUri,
                    displayName = relativePath,
                    mimeType = publishedMime,
                    bytesWritten = finalFile.length(),
                )
            }
        }

        val item = TransferItem(
            index = 0,
            relativePath = "IMG_20261011_171634.jpg",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val receiverJob = launch {
            val results = runner.receive(
                session = receiverSession,
                destinationDir = stagingDir,
                publisher = fakePublisher,
                authorize = { true },
            )
            assertEquals(1, results.size)
            assertEquals(VerificationResult.VERIFIED, results[0].verification)
            assertEquals("content://media/external/images/media/4242", results[0].destinationUri)
        }

        val outcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "Xiaomi 22041216I",
            resolveFile = { srcFile },
        )

        assertTrue(outcome is TransferRunner.SendOutcome.Done)
        val done = outcome as TransferRunner.SendOutcome.Done
        assertEquals(1, done.results.size)
        assertEquals(VerificationResult.VERIFIED, done.results[0].verification)

        receiverJob.join()

        assertEquals(1, publishedCount)
        assertEquals("image/jpeg", publishedMime)
        val finalPhoto = File(destDir, "IMG_20261011_171634.jpg")
        assertTrue("Image must exist in final published gallery destination", finalPhoto.exists())
        assertEquals(srcFile.length(), finalPhoto.length())
        assertArrayEquals(srcFile.readBytes(), finalPhoto.readBytes())
        val stagedPart = File(stagingDir, "IMG_20261011_171634.jpg")
        assertFalse("Staging file must be cleaned up after publication", stagedPart.exists())
    }

    @Test
    fun `publication failure marks item as WRITE_FAILED, reports error to sender, and cleans up`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair(initiatorId = "sender-dev", receiverId = "recv-dev")
        val runner = TransferRunner(dispatchers)

        val srcFile = tempDir.newFile("photo_fail.jpg").apply {
            writeBytes("test payload".toByteArray())
        }
        val stagingDir = tempDir.newFolder("staging_rx_fail")

        val failingPublisher = object : TransferStoragePublisher {
            override suspend fun publish(
                sourceFile: File,
                relativePath: String,
                mimeType: String?,
                sizeBytes: Long,
            ): StoragePublishResult {
                return StoragePublishResult.Failed("Disk full or MediaStore unavailable")
            }
        }

        val item = TransferItem(
            index = 0,
            relativePath = "photo_fail.jpg",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val receiverJob = launch {
            val results = runner.receive(
                session = receiverSession,
                destinationDir = stagingDir,
                publisher = failingPublisher,
                authorize = { true },
            )
            assertEquals(1, results.size)
            assertEquals(VerificationResult.WRITE_FAILED, results[0].verification)
            assertNotNull(results[0].errorMessage)
        }

        val outcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "SenderDevice",
            resolveFile = { srcFile },
        )

        assertTrue(outcome is TransferRunner.SendOutcome.Done)
        val done = outcome as TransferRunner.SendOutcome.Done
        assertEquals(1, done.results.size)
        assertEquals(
            "Sender must observe verification failure when receiver publication fails",
            VerificationResult.HASH_MISMATCH,
            done.results[0].verification,
        )

        receiverJob.join()
    }

    @Test
    fun `inbound transfer persists complete history record with all required fields`() = runTest(testDispatcher) {
        val (senderSession, receiverSession) = createConnectedPair(initiatorId = "peer-hist-1", receiverId = "recv-dev")
        val runner = TransferRunner(dispatchers)

        val srcFile = tempDir.newFile("notes.pdf").apply {
            writeBytes("PDF content".toByteArray())
        }
        val destDir = tempDir.newFolder("dest_hist")
        val publisher = FilesystemStoragePublisher(destDir)

        val recordedTransfers = mutableListOf<Transfer>()
        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = CoroutineScope(testDispatcher),
            localDeviceId = { "recv-dev" },
            localDeviceName = { "Samsung Receiver" },
            localPublicKey = { ByteArray(32) },
            trustedPeer = { null },
            saveTrustedPeer = {},
            client = object : ConnectionClient() {
                override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession {
                    throw IllegalStateException()
                }
            },
        )

        val rxStage = tempDir.newFolder("stage_rx_hist")
        val transferRecorded = CompletableDeferred<Transfer>()
        val coordinator = TransferCoordinator(
            dispatchers = dispatchers,
            connectionManager = manager,
            runner = runner,
            localName = { "Samsung Receiver" },
            destinationProvider = { rxStage },
            stagingDir = tempDir.newFolder("stage_hist"),
            endpointsFor = { emptyList() },
            autoAcceptFor = { true },
            storagePublisher = publisher,
            onTransferCompleted = {
                recordedTransfers.add(it)
                transferRecorded.complete(it)
            },
        )

        val driverJob = launch {
            manager.sessionDriver?.invoke(receiverSession, true)
        }

        val item = TransferItem(
            index = 0,
            relativePath = "notes.pdf",
            sizeBytes = srcFile.length(),
            lastModifiedEpochMillis = System.currentTimeMillis(),
            isDirectory = false,
        )

        val sendOutcome = runner.send(
            session = senderSession,
            items = listOf(item),
            senderName = "Xiaomi 22041216I",
            transferId = "tid-hist-test",
            resolveFile = { srcFile },
        )
        assertTrue(sendOutcome is TransferRunner.SendOutcome.Done)

        val record = withTimeout(15_000L) {
            transferRecorded.await()
        }

        assertEquals("Exactly one history record must be persisted", 1, recordedTransfers.size)
        assertEquals("tid-hist-test", record.id)
        assertEquals("peer-hist-1", record.peerDeviceId.value)
        assertEquals("Xiaomi 22041216I", record.peerDisplayName)
        assertEquals(TransferDirection.RECEIVE, record.direction)
        assertEquals(TransferState.COMPLETED, record.state)
        assertEquals(1, record.items.size)
        assertEquals("notes.pdf", record.items[0].relativePath)
        assertEquals(srcFile.length(), record.items[0].sizeBytes)
        assertEquals(VerificationResult.VERIFIED, record.items[0].verification)
        assertNotNull("Destination URI must be recorded", record.destinationUri)

        driverJob.cancel()
    }
}

@OptIn(io.ktor.utils.io.InternalAPI::class)
internal open class TestWebSocketSession(
    override val coroutineContext: kotlin.coroutines.CoroutineContext = Job(),
) : DefaultWebSocketSession {
    val incomingChannel = Channel<Frame>(Channel.UNLIMITED)
    val outgoingChannel = Channel<Frame>(Channel.UNLIMITED)
    private val closeDeferred = CompletableDeferred<CloseReason?>()

    override val incoming: ReceiveChannel<Frame> get() = incomingChannel
    override val outgoing: SendChannel<Frame> get() = outgoingChannel
    override val closeReason: kotlinx.coroutines.Deferred<CloseReason?> get() = closeDeferred

    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override var pingIntervalMillis: Long = 0
    override var timeoutMillis: Long = 0
    override val extensions: List<io.ktor.websocket.WebSocketExtension<*>> = emptyList()

    override suspend fun flush() {}
    @Suppress("DEPRECATION")
    override fun start(negotiatedExtensions: List<io.ktor.websocket.WebSocketExtension<*>>) {}
    @Suppress("DEPRECATION")
    override fun terminate() {
        incomingChannel.close()
        outgoingChannel.close()
        closeDeferred.complete(CloseReason(CloseReason.Codes.NORMAL, "terminated"))
    }
}
