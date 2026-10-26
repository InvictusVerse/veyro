package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferRoute
import com.veyro.core.model.VerificationResult
import com.veyro.core.protocol.tcp.TcpFrame
import com.veyro.core.protocol.tcp.TcpFrameCodec
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Random

/**
 * The Android side against a peer that behaves like the Windows client (third hardware test).
 *
 * The "PC" here is a bare [PeerConnection] plus a hand-written VTC1 endpoint that does exactly
 * what `Vtc1BulkTransfer` and `VeyroLinkServer` do: the receiver answers `ItemEnd` with a VTC1
 * `ItemResult` on the data socket and sends nothing per item on the WebSocket; the sender reads
 * `ItemResult` only from the data socket. Real dispatchers, real sockets on loopback.
 */
class WindowsInteropTransferTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val dispatchers = object : AppDispatchers {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val managers = mutableListOf<ConnectionManager>()

    @After
    fun tearDown() {
        managers.forEach { it.shutdown() }
        scope.cancel()
    }

    private fun pair(): Pair<PeerConnection, PeerConnection> {
        val key = FrameCrypto.sessionKeyFromBytes(ByteArray(32) { 21 })
        val toPc = Channel<Frame>(Channel.UNLIMITED)
        val toPhone = Channel<Frame>(Channel.UNLIMITED)
        val phoneWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = toPhone
            override val outgoing: SendChannel<Frame> = toPc
        }
        val pcWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = toPc
            override val outgoing: SendChannel<Frame> = toPhone
        }
        val phone = PeerConnection(
            ws = phoneWs, initialKey = key, initialEncrypt = true, localDeviceId = PHONE,
            tag = "Phone", dispatcher = Dispatchers.IO, peerDeviceId = PC, enableHeartbeat = false,
            remoteHost = "127.0.0.1", localHost = "127.0.0.1",
        )
        val pc = PeerConnection(
            ws = pcWs, initialKey = key, initialEncrypt = true, localDeviceId = PC,
            tag = "PC", dispatcher = Dispatchers.IO, peerDeviceId = PHONE, enableHeartbeat = false,
            remoteHost = "127.0.0.1", localHost = "127.0.0.1",
        )
        phone.start()
        pc.start()
        return phone to pc
    }

    private class PcReceived(
        val offer: LinkMessage.TransferOffer,
        val bytes: ByteArray,
        val chunkIndices: List<Int>,
    )

    /**
     * The Windows receiver: accepts with a complete LAN_TCP endpoint and an (empty) resume map,
     * answers each ItemEnd with a VTC1 ItemResult on the socket, and closes with a WebSocket
     * transferEnd after the VTC1 TransferEnd.
     */
    private suspend fun windowsReceive(
        pc: PeerConnection,
        resume: Map<Int, List<Int>> = emptyMap(),
    ): PcReceived = withContext(Dispatchers.IO) {
        val offer = pc.receiveOffer().payload as LinkMessage.TransferOffer
        val token = "A".repeat(64)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            pc.send(
                LinkMessage.TransferDecision(
                    transferId = offer.transferId, accept = true, resume = resume, route = "LAN_TCP",
                    tcpHost = "127.0.0.1", tcpPort = server.localPort, fallbackHost = "127.0.0.1", authToken = token,
                ),
            )
            server.soTimeout = 10_000
            server.accept().use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val auth = TcpFrameCodec.readFrame(input) as TcpFrame.Auth
                assertEquals(token, auth.authToken)
                assertEquals(PHONE, auth.senderDeviceId)
                TcpFrameCodec.writeFrame(output, TcpFrame.AuthAck(1, offer.transferId, true, null))
                var data = ByteArray(0)
                var chunkSize = 0
                val indices = mutableListOf<Int>()
                while (true) {
                    when (val frame = TcpFrameCodec.readFrame(input)) {
                        is TcpFrame.ItemBegin -> {
                            data = ByteArray(frame.sizeBytes.toInt())
                            chunkSize = frame.chunkSize
                        }
                        is TcpFrame.Chunk -> {
                            indices += frame.chunkIndex
                            System.arraycopy(frame.data, 0, data, frame.chunkIndex * chunkSize, frame.data.size)
                        }
                        is TcpFrame.ItemEnd -> TcpFrameCodec.writeFrame(
                            output,
                            TcpFrame.ItemResult(frame.seq + 1, offer.transferId, frame.itemIndex, ok = true),
                        )
                        is TcpFrame.TransferEnd -> break
                        else -> error("unexpected ${frame.javaClass.simpleName}")
                    }
                }
                pc.send(LinkMessage.TransferEnd(offer.transferId, true))
                PcReceived(offer, data, indices)
            }
        }
    }

    private fun file(bytes: ByteArray): File = tempDir.newFile().apply { writeBytes(bytes) }

    private fun item(source: File, name: String = "report.bin") =
        TransferItem(0, name, source.length(), 0L, false)

    private fun random(size: Int): ByteArray = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `phone send completes when the receiver answers ItemResult only on the data socket`() = runBlocking {
        val (phone, pc) = pair()
        val bytes = random(1_300_000) // three 512 KiB chunks, the last one short
        val source = file(bytes)
        val pcSide = scope.async { windowsReceive(pc) }

        // Before the fix the sender waited 60 s for a WebSocket itemResult that never came.
        val outcome = withTimeout(20_000) {
            TransferRunner(dispatchers).send(phone, listOf(item(source)), "Pixel", transferId = "w-1") { source }
        }

        assertTrue("send: $outcome", outcome is TransferRunner.SendOutcome.Done)
        val done = outcome as TransferRunner.SendOutcome.Done
        assertEquals(VerificationResult.VERIFIED, done.results.single().verification)
        val received = withTimeout(5_000) { pcSide.await() }
        assertArrayEquals(bytes, received.bytes)
        assertEquals(listOf(TransferRoute.LAN_TCP.name), received.offer.preferredRoutes)
        phone.close(); pc.close()
    }

    @Test
    fun `photo fetch answer carries requestId and its VTC1 bytes equal the MediaStore source`() = runBlocking {
        val (phone, pc) = pair()
        val coordinator = coordinator()
        val original = random(800_000)
        val pcSide = scope.async { windowsReceive(pc) }

        // A content:// stream as AndroidPhotoSource hands it over; MediaStore's SIZE may be stale
        // or missing, so the staged length is what goes on the wire.
        val result = withTimeout(20_000) {
            coordinator.sendRequested(
                session = phone,
                file = OutgoingFile("IMG_20261026_101500.jpg", sizeBytes = -1L, lastModifiedEpochMillis = 0L) {
                    original.inputStream()
                },
                requestId = "req-7",
            )
        }

        assertTrue("requested send: $result", result is SendResult.Success)
        val received = withTimeout(5_000) { pcSide.await() }
        assertEquals("req-7", received.offer.requestId)
        assertEquals(1, received.offer.items.size)
        assertEquals("IMG_20261026_101500.jpg", received.offer.items.single().relPath)
        assertEquals(original.size.toLong(), received.offer.items.single().size)
        assertEquals(sha256(original), sha256(received.bytes))
        assertNull("the phone never prompts for its own answer", coordinator.pendingOffer.value)
        assertNull("and shows no banner", coordinator.activeTransfer.value)
        phone.close(); pc.close()
    }

    @Test
    fun `each decline reason from the PC reaches the sender`() = runBlocking {
        for (reason in listOf("declined", "timeout", "busy", "error")) {
            val (phone, pc) = pair()
            val source = file(byteArrayOf(1, 2, 3))
            val pcSide = scope.async {
                val offer = pc.receiveOffer().payload as LinkMessage.TransferOffer
                pc.send(LinkMessage.TransferDecision(offer.transferId, false, reason))
            }

            val outcome = withTimeout(10_000) {
                TransferRunner(dispatchers).send(phone, listOf(item(source)), "Pixel", transferId = "d-$reason") { source }
            }
            pcSide.await()

            assertEquals(TransferRunner.SendOutcome.Declined(DeclineReason.fromWire(reason)), outcome)
            assertEquals(reason, (outcome as TransferRunner.SendOutcome.Declined).reason.wire)
            phone.close(); pc.close()
        }
    }

    @Test
    fun `decline reasons map to distinct sentences`() {
        assertEquals(DeclineReason.DECLINED, DeclineReason.fromWire(null))
        assertEquals(DeclineReason.DECLINED, DeclineReason.fromWire("User declined"))
        assertEquals(DeclineReason.TIMEOUT, DeclineReason.fromWire(" Timeout "))
        assertEquals(DeclineReason.BUSY, DeclineReason.fromWire("busy"))
        assertEquals(DeclineReason.ERROR, DeclineReason.fromWire("error"))
        assertEquals(DeclineReason.ERROR, DeclineReason.fromWire("disk full"))
        val sentences = DeclineReason.entries.map { it.message("Office PC") }
        assertEquals(sentences.size, sentences.toSet().size)
        assertTrue(sentences.all { "Office PC" in it })
        assertTrue(DeclineReason.BUSY.message("Office PC").contains("busy"))
    }

    @Test
    fun `a send that fails after the offer tells the receiver with a cancel`() = runBlocking {
        val (phone, pc) = pair()
        val source = file(byteArrayOf(9, 9, 9))
        val closedPort = ServerSocket(0).use { it.localPort }
        val pcSide = scope.async {
            val offer = pc.receiveOffer().payload as LinkMessage.TransferOffer
            val mailbox = pc.mailbox(offer.transferId)
            pc.send(
                LinkMessage.TransferDecision(
                    offer.transferId, true, route = "LAN_TCP", tcpHost = "127.0.0.1",
                    tcpPort = closedPort, fallbackHost = "127.0.0.1", authToken = "t",
                ),
            )
            withTimeout(15_000) { mailbox.payloadChannel.receive() }
        }

        val outcome = withTimeout(20_000) {
            TransferRunner(dispatchers).send(phone, listOf(item(source)), "Pixel", transferId = "f-1") { source }
        }

        assertTrue("send: $outcome", outcome is TransferRunner.SendOutcome.Failed)
        val cancel = pcSide.await() as LinkMessage.Cancel
        assertEquals("f-1", cancel.transferId)
        assertEquals("error", cancel.reason)
        phone.close(); pc.close()
    }

    @Test
    fun `an offer naming no known route is answered with a complete LAN_TCP endpoint`() = runBlocking {
        val (phone, pc) = pair()
        val inbox = tempDir.newFolder()
        val bytes = random(700_000)
        val phoneSide = scope.async {
            TransferRunner(dispatchers).receive(phone, inbox) { true }
        }

        val decision = withContext(Dispatchers.IO) {
            val mailbox = pc.mailbox("u-1")
            pc.send(
                LinkMessage.TransferOffer(
                    transferId = "u-1",
                    items = listOf(LinkMessage.Item(0, "from-pc.bin", bytes.size.toLong(), 0L)),
                    totalBytes = bytes.size.toLong(),
                    senderName = "Office PC",
                    preferredRoutes = listOf("LANTCP"), // what the Windows build advertised
                ),
            )
            withTimeout(10_000) { mailbox.awaitDecision(10_000) }
        }

        assertNotNull(decision)
        assertTrue(decision!!.accept)
        assertEquals(TransferRoute.LAN_TCP.name, decision.route)
        assertNotNull("tcpHost", decision.tcpHost)
        assertNotNull("tcpPort", decision.tcpPort)
        assertNotNull("authToken", decision.authToken)

        // Then send it the way Vtc1BulkTransfer.SendAsync does, reading ItemResult from the socket.
        val itemResult = withContext(Dispatchers.IO) {
            Socket(decision.tcpHost, decision.tcpPort!!).use { socket ->
                socket.soTimeout = 10_000
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                var seq = 1L
                TcpFrameCodec.writeFrame(output, TcpFrame.Auth(seq++, "u-1", decision.authToken!!, PC, TransferRoute.LAN_TCP))
                assertTrue((TcpFrameCodec.readFrame(input) as TcpFrame.AuthAck).ok)
                val chunk = 4 * 1024 * 1024
                TcpFrameCodec.writeFrame(output, TcpFrame.ItemBegin(seq++, "u-1", 0, bytes.size.toLong(), 1, chunk))
                TcpFrameCodec.writeFrame(output, TcpFrame.Chunk(seq++, "u-1", 0, 0, bytes), autoFlush = true)
                TcpFrameCodec.writeFrame(output, TcpFrame.ItemEnd(seq++, "u-1", 0, sha256(bytes)))
                val result = TcpFrameCodec.readFrame(input) as TcpFrame.ItemResult
                TcpFrameCodec.writeFrame(output, TcpFrame.TransferEnd(seq, "u-1", true))
                result
            }
        }

        assertTrue("ItemResult on the data socket must say ok", itemResult.ok)
        val received = withTimeout(10_000) { phoneSide.await() }
        assertEquals(VerificationResult.VERIFIED, received.single().verification)
        assertArrayEquals(bytes, File(inbox, "from-pc.bin").readBytes())
        phone.close(); pc.close()
    }

    @Test
    fun `resume sends only the chunks the receiver lists as still needed`() = runBlocking {
        val (phone, pc) = pair()
        val bytes = random(1_100_000) // chunks 0, 1, 2
        val source = file(bytes)
        val pcSide = scope.async { windowsReceive(pc, resume = mapOf(0 to listOf(2))) }

        val outcome = withTimeout(20_000) {
            TransferRunner(dispatchers).send(phone, listOf(item(source)), "Pixel", transferId = "r-1") { source }
        }

        assertTrue("send: $outcome", outcome is TransferRunner.SendOutcome.Done)
        assertEquals(listOf(2), withTimeout(5_000) { pcSide.await() }.chunkIndices)
        phone.close(); pc.close()
    }

    @Test
    fun `an Android receiver refusing an offer sends the coordinator's reason`() = runBlocking {
        val (phone, pc) = pair()
        val phoneSide = scope.async {
            TransferRunner(dispatchers).receive(
                session = phone,
                destinationDir = tempDir.newFolder(),
                authorize = { false },
                declineReason = { "busy" },
            )
        }
        val decision = withContext(Dispatchers.IO) {
            val mailbox = pc.mailbox("b-1")
            pc.send(LinkMessage.TransferOffer("b-1", listOf(LinkMessage.Item(0, "x", 1, 0)), 1, "PC", listOf("LAN_TCP")))
            withTimeout(10_000) { mailbox.awaitDecision(10_000) }
        }
        phoneSide.await()

        assertEquals(false, decision?.accept)
        assertEquals("busy", decision?.reason)
        phone.close(); pc.close()
    }

    private fun fiveFileOffer(id: String) = LinkMessage.TransferOffer(
        transferId = id,
        items = (0 until 5).map { LinkMessage.Item(it, "IMG_$it.jpg", 1_000L + it, 0L) },
        totalBytes = 5_010L,
        senderName = "Office PC",
        preferredRoutes = listOf("LAN_TCP"),
    )

    private suspend fun awaitPending(coordinator: TransferCoordinator): IncomingOffer = withTimeout(10_000) {
        while (coordinator.pendingOffer.value == null) kotlinx.coroutines.delay(20)
        coordinator.pendingOffer.value!!
    }

    @Test
    fun `the prompt lists sender, three files and the deadline, and Always accept is saved`() = runBlocking {
        val (phone, pc) = pair()
        val remembered = mutableListOf<String>()
        val coordinator = coordinator(rememberAutoAccept = { remembered += it.value })
        val driver = scope.async { coordinator.sessionDriverFor(phone) }
        val mailbox = pc.mailbox("p-1")
        pc.send(fiveFileOffer("p-1"))

        val offer = awaitPending(coordinator)
        assertEquals("Office PC", offer.peerName)
        assertEquals(PC, offer.peerDeviceId?.value)
        assertEquals(5, offer.fileCount)
        assertEquals(listOf("IMG_0.jpg", "IMG_1.jpg", "IMG_2.jpg"), offer.files.map { it.name })
        val left = offer.expiresAtEpochMillis - System.currentTimeMillis()
        assertTrue("deadline about 60 s away, was $left", left in 50_000..TransferCoordinator.OFFER_TIMEOUT_MS)

        coordinator.respondToOffer("p-1", accept = true, alwaysAccept = true)
        val decision = withTimeout(10_000) { mailbox.awaitDecision(10_000) }
        assertEquals(listOf(PC), remembered)
        assertTrue(decision!!.accept)
        assertNotNull(decision.tcpPort)

        pc.send(LinkMessage.Cancel("p-1", "test over"))
        phone.close(); pc.close()
        driver.cancel()
    }

    @Test
    fun `accepting with Always accept ticked means the next offer from that PC is not prompted`() = runBlocking {
        // ADR-038: the prompt's box is ticked by default; what it stores is what auto-accept reads,
        // exactly as VeyroContainer wires it to the trust store.
        val (phone, pc) = pair()
        val remembered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val coordinator = coordinator(
            rememberAutoAccept = { remembered += it.value },
            autoAcceptFor = { it.value in remembered },
        )
        val driver = scope.async { coordinator.sessionDriverFor(phone) }

        val first = pc.mailbox("a-1")
        pc.send(fiveFileOffer("a-1"))
        awaitPending(coordinator)
        coordinator.respondToOffer("a-1", accept = true, alwaysAccept = true)
        assertTrue(withTimeout(10_000) { first.awaitDecision(10_000) }!!.accept)
        assertEquals(setOf(PC), remembered.toSet())
        pc.send(LinkMessage.Cancel("a-1", "test over"))
        withTimeout(10_000) { while (coordinator.activeTransfer.value?.isTerminal != true) kotlinx.coroutines.delay(20) }

        val second = pc.mailbox("a-2")
        pc.send(fiveFileOffer("a-2"))
        val decision = withTimeout(15_000) { second.awaitDecision(15_000) }
        assertTrue("the second offer is accepted without a prompt", decision!!.accept)
        assertNull(coordinator.pendingOffer.value)

        pc.send(LinkMessage.Cancel("a-2", "test over"))
        phone.close(); pc.close()
        driver.cancel()
    }

    @Test
    fun `accepting with the box unticked keeps prompting`() = runBlocking {
        val (phone, pc) = pair()
        val remembered = mutableListOf<String>()
        val coordinator = coordinator(rememberAutoAccept = { remembered += it.value })
        val driver = scope.async { coordinator.sessionDriverFor(phone) }
        val mailbox = pc.mailbox("o-1")
        pc.send(fiveFileOffer("o-1"))
        awaitPending(coordinator)

        coordinator.respondToOffer("o-1", accept = true, alwaysAccept = false)
        assertTrue(withTimeout(10_000) { mailbox.awaitDecision(10_000) }!!.accept)
        assertTrue("nothing stored without the box", remembered.isEmpty())

        pc.send(LinkMessage.Cancel("o-1", "test over"))
        phone.close(); pc.close()
        driver.cancel()
    }

    @Test
    fun `an unanswered prompt is declined with timeout`() = runBlocking {
        val (phone, pc) = pair()
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val coordinator = coordinator(clock = { clock.get() })
        val driver = scope.async { coordinator.sessionDriverFor(phone) }
        val mailbox = pc.mailbox("t-1")
        pc.send(fiveFileOffer("t-1"))

        awaitPending(coordinator)
        clock.addAndGet(TransferCoordinator.OFFER_TIMEOUT_MS + 1)
        val decision = withTimeout(10_000) { mailbox.awaitDecision(10_000) }

        assertEquals(false, decision?.accept)
        assertEquals("timeout", decision?.reason)
        assertNull(coordinator.pendingOffer.value)
        phone.close(); pc.close()
        driver.cancel()
    }

    @Test
    fun `a cancel from the PC closes the prompt`() = runBlocking {
        val (phone, pc) = pair()
        val coordinator = coordinator()
        val driver = scope.async { coordinator.sessionDriverFor(phone) }
        pc.send(fiveFileOffer("c-1"))
        awaitPending(coordinator)

        pc.send(LinkMessage.Cancel("c-1", "timeout"))
        withTimeout(10_000) { while (coordinator.pendingOffer.value != null) kotlinx.coroutines.delay(20) }

        assertNull(coordinator.pendingOffer.value)
        phone.close(); pc.close()
        driver.cancel()
    }

    /** Runs the coordinator's receive loop on [connection], as ConnectionManager would. */
    private suspend fun TransferCoordinator.sessionDriverFor(connection: PeerConnection) {
        drivers.getValue(this).sessionDriver!!.invoke(connection, false)
    }

    private val drivers = HashMap<TransferCoordinator, ConnectionManager>()

    private fun coordinator(
        rememberAutoAccept: (suspend (com.veyro.core.model.DeviceId) -> Unit)? = null,
        clock: () -> Long = System::currentTimeMillis,
        autoAcceptFor: suspend (com.veyro.core.model.DeviceId) -> Boolean = { false },
    ): TransferCoordinator {
        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = scope,
            localDeviceId = { PHONE },
            localDeviceName = { "Pixel" },
            trustedPeer = { null },
            saveTrustedPeer = {},
        ).also { managers += it }
        return TransferCoordinator(
            connectionManager = manager,
            runner = TransferRunner(dispatchers),
            dispatchers = dispatchers,
            stagingDir = tempDir.newFolder(),
            destinationProvider = { tempDir.newFolder() },
            localName = { "Pixel" },
            endpointsFor = { emptyList() },
            autoAcceptFor = autoAcceptFor,
            rememberAutoAccept = rememberAutoAccept,
            clock = clock,
        ).also { drivers[it] = manager }
    }

    private companion object {
        const val PHONE = "phone-dev"
        const val PC = "pc-dev"
    }
}
