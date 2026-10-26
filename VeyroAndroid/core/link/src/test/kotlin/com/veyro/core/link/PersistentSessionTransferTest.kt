package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.model.Platform
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TrustedPeer
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PC-to-phone transfers on the phone's persistent session (ADR-034, the hardware-test failure).
 *
 * The phone side is the real [ConnectionManager] + [TransferCoordinator]; the PC side is a bare
 * [PeerConnection] + [TransferRunner] on the other end of an in-memory WebSocket pair, with the
 * data socket on loopback. Real dispatchers and real time, so timeouts mean what they say.
 */
class PersistentSessionTransferTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val dispatchers = object : AppDispatchers {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pcPk = ByteArray(32) { 6 }
    private val pcId = DeviceId.fromPublicKey(pcPk)
    private val phoneId = DeviceId.fromPublicKey(ByteArray(32) { 7 }).value
    private val secret = ByteArray(32) { 11 }
    private val pc = TrustedPeer(
        deviceId = pcId,
        staticPublicKey = pcPk,
        pairingSecret = secret,
        name = "Office PC",
        platform = Platform.WINDOWS,
        pairedAtEpochMillis = 1L,
    )

    @Before
    fun setUp() {
        com.veyro.core.common.VeyroLog.install { _, tag, msg, tr -> println("[$tag] $msg ${tr?.message ?: ""}") }
    }

    private val managers = mutableListOf<ConnectionManager>()

    @After
    fun tearDown() {
        managers.forEach { it.shutdown() }
        scope.cancel()
        com.veyro.core.common.VeyroLog.uninstall()
    }

    /** Two ends of one in-memory WebSocket. */
    private class Wire {
        val toPhone = Channel<Frame>(Channel.UNLIMITED)
        val toPc = Channel<Frame>(Channel.UNLIMITED)
        val phoneEnd: DefaultWebSocketSession = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = toPhone
            override val outgoing: SendChannel<Frame> = toPc
        }
        val pcEnd: DefaultWebSocketSession = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = toPc
            override val outgoing: SendChannel<Frame> = toPhone
        }
    }

    private class Phone(val manager: ConnectionManager, val coordinator: TransferCoordinator, val inbox: File)

    private fun phone(dial: Wire? = null): Phone {
        val inbox = tempDir.newFolder()
        val manager = ConnectionManager(
            dispatchers = dispatchers,
            scope = scope,
            localDeviceId = { phoneId },
            localDeviceName = { "Pixel" },
            trustedPeer = { id -> pc.takeIf { it.deviceId.value == id } },
            saveTrustedPeer = {},
            client = object : ConnectionClient() {
                override suspend fun connect(host: String, port: Int, path: String): DefaultWebSocketSession =
                    dial?.phoneEnd ?: throw IllegalStateException("no dial expected")
            },
        ).also { managers += it }
        val coordinator = TransferCoordinator(
            connectionManager = manager,
            runner = TransferRunner(dispatchers),
            dispatchers = dispatchers,
            stagingDir = tempDir.newFolder(),
            destinationProvider = { inbox },
            localName = { "Pixel" },
            endpointsFor = { emptyList() },
            autoAcceptFor = { true },
        )
        return Phone(manager, coordinator, inbox)
    }

    /** The PC's end of a session, started once the phone's unauthenticated text frame was read. */
    private suspend fun pcSession(wire: Wire): PeerConnection {
        withTimeout(5_000) { (wire.toPc.receive() as Frame.Text).readText() } // preamble or ack
        return PeerConnection(
            ws = wire.pcEnd,
            initialKey = FrameCrypto.sessionKeyFromBytes(secret),
            initialEncrypt = true,
            localDeviceId = pcId.value,
            tag = "PC",
            dispatcher = Dispatchers.IO,
            peerDeviceId = phoneId,
            enableHeartbeat = false,
            remoteHost = "127.0.0.1",
            localHost = "127.0.0.1",
        ).also { it.start() }
    }

    /** The phone dials the PC and the PC answers in session mode: the persistent companion session. */
    private suspend fun phoneDialledSession(phone: Phone, wire: Wire): Pair<PeerConnection, PeerConnection> {
        wire.toPhone.send(Frame.Text("""{"mode":"session"}"""))
        assertTrue(phone.manager.connect(pcId.value, "Office PC", listOf(Endpoint("127.0.0.1", 5150))))
        val pcEnd = pcSession(wire)
        val phoneEnd = withTimeout(5_000) { phone.manager.activeConnection.filterNotNull().first() }
        return phoneEnd to pcEnd
    }

    private suspend fun pcSends(pcEnd: PeerConnection, name: String, content: String, id: String): TransferRunner.SendOutcome {
        val file = tempDir.newFile(name).apply { writeText(content) }
        val item = TransferItem(0, name, file.length(), System.currentTimeMillis(), false)
        return withTimeout(30_000) {
            TransferRunner(dispatchers).send(
                session = pcEnd,
                items = listOf(item),
                senderName = "Office PC",
                transferId = id,
                resolveFile = { file },
            )
        }
    }

    @Test
    fun `an offer from the PC on the session the phone dialled is received end to end`() = runBlocking {
        val wire = Wire()
        val phone = phone(dial = wire)
        val (_, pcEnd) = phoneDialledSession(phone, wire)

        val outcome = pcSends(pcEnd, "from-pc.txt", "sent over the phone's own session", "pc-t1")

        assertTrue("PC send must complete, was $outcome", outcome is TransferRunner.SendOutcome.Done)
        assertEquals("sent over the phone's own session", File(phone.inbox, "from-pc.txt").readText())
        pcEnd.close()
    }

    @Test
    fun `two sequential PC transfers on one session both arrive`() = runBlocking {
        val wire = Wire()
        val phone = phone(dial = wire)
        val (phoneEnd, pcEnd) = phoneDialledSession(phone, wire)

        val first = pcSends(pcEnd, "one.txt", "first", "pc-seq-1")
        val second = pcSends(pcEnd, "two.txt", "second", "pc-seq-2")

        assertTrue("first: $first", first is TransferRunner.SendOutcome.Done)
        assertTrue("second: $second", second is TransferRunner.SendOutcome.Done)
        assertEquals("first", File(phone.inbox, "one.txt").readText())
        assertEquals("second", File(phone.inbox, "two.txt").readText())
        assertTrue("the session outlives both transfers", phoneEnd.isConnected)
        pcEnd.close()
    }

    @Test
    fun `a requested send carries the requestId on its offer over the same session`() = runBlocking {
        val wire = Wire()
        val phone = phone(dial = wire)
        val (phoneEnd, pcEnd) = phoneDialledSession(phone, wire)
        val pcInbox = tempDir.newFolder()
        var seenRequestId: String? = null
        val pcReceive = scope.launch {
            TransferRunner(dispatchers).receive(pcEnd, pcInbox) { offer ->
                seenRequestId = offer.requestId
                true
            }
        }

        val bytes = "original photo bytes".toByteArray()
        val result = withTimeout(30_000) {
            phone.coordinator.sendRequested(
                session = phoneEnd,
                file = OutgoingFile("IMG_1.jpg", bytes.size.toLong(), 0L) { bytes.inputStream() },
                requestId = "r-1",
            )
        }
        withTimeout(10_000) { pcReceive.join() }

        assertTrue("requested send: $result", result is SendResult.Success)
        assertEquals("r-1", seenRequestId)
        assertEquals("original photo bytes", File(pcInbox, "IMG_1.jpg").readText())
        assertTrue("a requested send shows no banner on the phone", phone.coordinator.activeTransfer.value == null)
        pcEnd.close()
    }

    @Test
    fun `companion session survives a parallel inbound transfer connection`() = runBlocking {
        val companionWire = Wire()
        val phone = phone(dial = companionWire)
        val (companion, pcCompanion) = phoneDialledSession(phone, companionWire)

        // The PC dials a second connection just for this transfer.
        val transferWire = Wire()
        transferWire.toPhone.send(
            Frame.Text(LinkJson.codec.encodeToString(LinkPreamble.serializer(), LinkPreamble(pcId.value, "Office PC", platform = "windows"))),
        )
        val inboundJob = scope.launch {
            phone.manager.handleInbound(transferWire.phoneEnd, remoteHost = "127.0.0.1", localHost = "127.0.0.1")
        }
        val pcTransfer = pcSession(transferWire)

        val outcome = pcSends(pcTransfer, "parallel.txt", "via a second connection", "pc-par-1")
        assertTrue("transfer on the second connection: $outcome", outcome is TransferRunner.SendOutcome.Done)
        assertEquals("via a second connection", File(phone.inbox, "parallel.txt").readText())
        assertTrue("companion session still open", companion.isConnected)
        assertSame("companion session still the active one", companion, phone.manager.activeConnection.value)

        pcTransfer.close()
        withTimeout(5_000) { inboundJob.join() }
        assertTrue("closing the transfer connection leaves the companion session up", companion.isConnected)
        assertSame(companion, phone.manager.activeConnection.value)

        // And the companion session still carries transfers afterwards.
        val after = pcSends(pcCompanion, "after.txt", "still works", "pc-after-1")
        assertTrue("after: $after", after is TransferRunner.SendOutcome.Done)
        pcCompanion.close()
    }
}
