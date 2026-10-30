package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Platform
import com.veyro.core.model.Transfer
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferStage
import com.veyro.core.model.TransferState
import com.veyro.core.model.TrustedPeer
import com.veyro.core.transfer.FilesystemStoragePublisher
import com.veyro.core.transfer.TransferStoragePublisher
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * ADR-064 end to end: a folder from the other device arrives with its structure and its empty
 * directories, a second copy gets its own name, hostile paths never land; a folder sent from
 * here is streamed without a staged copy, only to a device that takes folders, and Cancel or a
 * file that changed stops it cleanly. The phone is a real [ConnectionManager] and
 * [TransferCoordinator]; the other device a bare [PeerConnection] and [TransferRunner].
 */
class FolderTransferTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val dispatchers = object : AppDispatchers {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val phonePk = ByteArray(32) { 1 }
    private val phoneId = DeviceId.fromPublicKey(phonePk).value
    private val pcPk = ByteArray(32) { 2 }
    private val pcId = DeviceId.fromPublicKey(pcPk)
    private val pc = TrustedPeer(
        deviceId = pcId,
        staticPublicKey = pcPk,
        pairingSecret = ByteArray(32) { 3 },
        name = "Office PC",
        platform = Platform.WINDOWS,
        pairedAtEpochMillis = 1L,
    )
    private val bigBytes = ByteArray(1_300_000) { (it % 253).toByte() }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** The phone's end and the PC's end of one session. */
    private fun pair(): Pair<PeerConnection, PeerConnection> {
        val key = FrameCrypto.sessionKeyFromBytes(ByteArray(32) { 42 })
        val toPc = Channel<Frame>(Channel.UNLIMITED)
        val toPhone = Channel<Frame>(Channel.UNLIMITED)
        fun end(inbox: Channel<Frame>, outbox: Channel<Frame>, local: String, peer: String, tag: String) = PeerConnection(
            ws = object : TestWebSocketSession() {
                override val incoming: ReceiveChannel<Frame> = inbox
                override val outgoing: SendChannel<Frame> = outbox
            },
            initialKey = key,
            initialEncrypt = true,
            localDeviceId = local,
            tag = tag,
            dispatcher = Dispatchers.IO,
            peerDeviceId = peer,
            enableHeartbeat = false,
            remoteHost = "127.0.0.1",
            localHost = "127.0.0.1",
        ).apply { start() }
        return end(toPhone, toPc, phoneId, pcId.value, "Phone") to end(toPc, toPhone, pcId.value, phoneId, "PC")
    }

    private fun manager() = ConnectionManager(
        dispatchers = dispatchers,
        scope = scope,
        localDeviceId = { phoneId },
        localDeviceName = { "Pixel" },
        trustedPeer = { id -> pc.takeIf { it.deviceId.value == id } },
        saveTrustedPeer = {},
        client = object : ConnectionClient() {},
        localPublicKey = { phonePk },
    )

    private fun coordinator(
        manager: ConnectionManager,
        staging: File = tempDir.newFolder(),
        inbox: File = tempDir.newFolder(),
        publisher: TransferStoragePublisher? = null,
        history: Channel<Transfer> = Channel(Channel.UNLIMITED),
    ) = TransferCoordinator(
        connectionManager = manager,
        runner = TransferRunner(dispatchers),
        dispatchers = dispatchers,
        stagingDir = staging,
        destinationProvider = { inbox },
        localName = { "Pixel" },
        endpointsFor = { emptyList() },
        autoAcceptFor = { true },
        storagePublisher = publisher,
        onTransferCompleted = { history.send(it) },
    )

    /** The PC sends "Trip": two files, one of them nested, and an empty directory. */
    private suspend fun pcSendsTrip(pcEnd: PeerConnection, id: String, extra: List<TransferItem> = emptyList()): TransferRunner.SendOutcome {
        val a = tempDir.newFile().apply { writeText("A") }
        val b = tempDir.newFile().apply { writeBytes(bigBytes) }
        val items = listOf(
            TransferItem(0, "Trip/a.txt", a.length(), 0L),
            TransferItem(1, "Trip/Day 2/b.bin", b.length(), 0L),
        ) + extra + TransferItem(9, "Trip/Empty", 0L, 0L, isDirectory = true)
        val sources = mapOf(0 to a, 1 to b) + extra.associate { it.index to tempDir.newFile().apply { writeText("x") } }
        return withTimeout(30_000) {
            TransferRunner(dispatchers).send(pcEnd, items, "Office PC", transferId = id, resolveFile = { sources.getValue(it.index) })
        }
    }

    private suspend fun PeerConnection.advertise(vararg capabilities: Capability) {
        send(PeerMessage.HelloAck(pcId.value, "Office PC", "windows", capabilities = Capabilities.of(*capabilities).bits))
    }

    private fun streamed(path: String, bytes: ByteArray, open: () -> InputStream = { ByteArrayInputStream(bytes) }) = OutgoingFile(
        effectiveName = path.substringAfterLast('/'),
        sizeBytes = bytes.size.toLong(),
        lastModifiedEpochMillis = 0L,
        relativePath = path,
        streamed = true,
        openStream = open,
    )

    private fun emptyDir(path: String) = OutgoingFile(
        effectiveName = path.substringAfterLast('/'),
        sizeBytes = 0L,
        lastModifiedEpochMillis = 0L,
        relativePath = path,
        isDirectory = true,
        openStream = { throw IllegalStateException("a directory is never opened") },
    )

    // ── Receiving ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a received folder keeps its structure and empty directories, and a second copy gets its own name`() = runBlocking {
        val (phoneEnd, pcEnd) = pair()
        val manager = manager()
        val saved = tempDir.newFolder("Veyro")
        val history = Channel<Transfer>(Channel.UNLIMITED)
        val coordinator = coordinator(manager, publisher = FilesystemStoragePublisher(saved), history = history)
        val driver = scope.launch { manager.sessionDriver?.invoke(phoneEnd, true) }

        assertTrue(pcSendsTrip(pcEnd, "trip-1") is TransferRunner.SendOutcome.Done)
        val first = withTimeout(15_000) { history.receive() }
        val shown = withTimeout(5_000) { coordinator.activeTransfer.first { it?.stage == TransferStage.COMPLETED } }!!

        assertEquals(TransferState.COMPLETED, first.state)
        assertEquals("only files count, never the directory", 2, first.items.size)
        assertEquals("A", File(saved, "Trip/a.txt").readText())
        assertArrayEquals(bigBytes, File(saved, "Trip/Day 2/b.bin").readBytes())
        assertTrue(File(saved, "Trip/Empty").isDirectory)
        assertEquals("Trip", shown.folderName)
        assertEquals("Received Trip folder, 2 files", shown.statusText)

        assertTrue(pcSendsTrip(pcEnd, "trip-2") is TransferRunner.SendOutcome.Done)
        withTimeout(15_000) { history.receive() }
        assertEquals("the first copy is untouched", "A", File(saved, "Trip/a.txt").readText())
        assertEquals("A", File(saved, "Trip (2)/a.txt").readText())
        assertTrue(File(saved, "Trip (2)/Empty").isDirectory)
        assertFalse("nothing doubled inside the first copy", File(saved, "Trip/a (1).txt").exists())

        driver.cancel()
        phoneEnd.close()
        pcEnd.close()
    }

    @Test
    fun `hostile paths in a received folder fail alone and nothing lands outside`() = runBlocking {
        val (phoneEnd, pcEnd) = pair()
        val manager = manager()
        val root = tempDir.newFolder("root")
        val saved = File(root, "Veyro").apply { mkdirs() }
        val history = Channel<Transfer>(Channel.UNLIMITED)
        coordinator(manager, inbox = File(root, "staging").apply { mkdirs() }, publisher = FilesystemStoragePublisher(saved), history = history)
        val driver = scope.launch { manager.sessionDriver?.invoke(phoneEnd, true) }

        pcSendsTrip(
            pcEnd,
            "trip-hostile",
            extra = listOf(
                TransferItem(2, "Trip/../escape.txt", 1L, 0L),
                TransferItem(3, "Trip/CON/a.txt", 1L, 0L),
                TransferItem(4, "../../outside", 0L, 0L, isDirectory = true),
            ),
        )
        val record = withTimeout(15_000) { history.receive() }

        assertEquals(TransferState.COMPLETED_WITH_ERRORS, record.state)
        assertEquals("A", File(saved, "Trip/a.txt").readText())
        assertTrue(File(saved, "Trip/Empty").isDirectory)
        assertFalse(File(saved, "escape.txt").exists())
        assertFalse(File(root, "escape.txt").exists())
        assertFalse(File(root.parentFile, "outside").exists())
        assertFalse(File(root, "outside").exists())
        assertFalse(File(saved, "Trip/CON").exists())

        driver.cancel()
        phoneEnd.close()
        pcEnd.close()
    }

    // ── Sending ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a folder is streamed without a staged copy, with its empty directories on the offer`() = runBlocking {
        val (phoneEnd, pcEnd) = pair()
        val manager = manager()
        assertTrue(manager.adoptForTest(phoneEnd))
        pcEnd.advertise(Capability.FILE_TRANSFER, Capability.FOLDER_TRANSFER)
        val staging = tempDir.newFolder("staging")
        val coordinator = coordinator(manager, staging = staging)
        val pcInbox = tempDir.newFolder("pc")
        val offered = CompletableDeferred<PeerMessage.TransferOffer>()
        val receive = scope.async {
            TransferRunner(dispatchers).receive(pcEnd, pcInbox) { offer -> offered.complete(offer); true }
        }
        var stagedWhileSending = false
        val folder = listOf(
            emptyDir("Trip/Empty"),
            streamed("Trip/a.txt", "A".toByteArray()),
            streamed("Trip/Day 2/b.bin", bigBytes) {
                stagedWhileSending = stagedWhileSending || staging.walkTopDown().any { it.isFile }
                ByteArrayInputStream(bigBytes)
            },
        )

        val result = withTimeout(30_000) { coordinator.sendTo(pcId, "Office PC", folder) }
        withTimeout(15_000) { receive.await() }

        assertTrue("send: $result", result is SendResult.Success)
        val offer = offered.await()
        assertEquals(listOf("Trip/a.txt", "Trip/Day 2/b.bin", "Trip/Empty"), offer.items.map { it.relPath })
        assertEquals(listOf(false, false, true), offer.items.map { it.isDir })
        assertEquals(1L + bigBytes.size, offer.totalBytes)
        assertEquals("A", File(pcInbox, "Trip/a.txt").readText())
        assertArrayEquals(bigBytes, File(pcInbox, "Trip/Day 2/b.bin").readBytes())
        assertFalse("nothing was copied into the cache", stagedWhileSending)
        assertTrue(staging.walkTopDown().none { it.isFile })
        val shown = coordinator.activeTransfer.value!!
        assertEquals("Trip", shown.folderName)
        assertEquals(2, shown.totalFiles)
        assertEquals("Sent Trip folder, 2 files, all verified", shown.statusText)
        phoneEnd.close()
        pcEnd.close()
    }

    @Test
    fun `a folder is not offered to a device that does not take folders`() = runBlocking {
        val (phoneEnd, pcEnd) = pair()
        val manager = manager()
        assertTrue(manager.adoptForTest(phoneEnd))
        pcEnd.advertise(Capability.FILE_TRANSFER)
        val coordinator = coordinator(manager)

        val result = withTimeout(15_000) {
            coordinator.sendTo(pcId, "Office PC", listOf(streamed("Trip/a.txt", "A".toByteArray())))
        }

        assertTrue(result is SendResult.Failed)
        assertEquals("Office PC cannot receive folders. Update Veyro on Office PC.", (result as SendResult.Failed).reason)
        assertEquals(null, withTimeoutOrNull(500) { pcEnd.receiveOffer() })
        phoneEnd.close()
        pcEnd.close()
    }

    /** A folder file read slowly, so Cancel lands while it streams. */
    private class SlowStream(private val reading: CompletableDeferred<Unit>) : InputStream() {
        @Volatile var closed = false
        override fun read(): Int = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reading.complete(Unit)
            Thread.sleep(2)
            return minOf(len, 16 * 1024)
        }
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `cancel while a folder file streams stops the send and closes the file`() = runBlocking {
        val (phoneEnd, pcEnd) = pair()
        val manager = manager()
        assertTrue(manager.adoptForTest(phoneEnd))
        pcEnd.advertise(Capability.FILE_TRANSFER, Capability.FOLDER_TRANSFER)
        val staging = tempDir.newFolder("staging")
        val coordinator = coordinator(manager, staging = staging)
        val receive = scope.async { runCatching { TransferRunner(dispatchers).receive(pcEnd, tempDir.newFolder()) { true } } }
        val reading = CompletableDeferred<Unit>()
        val stream = SlowStream(reading)
        val big = OutgoingFile("big.bin", 512L shl 20, 0L, relativePath = "Trip/big.bin", streamed = true) { stream }

        val send = scope.async { coordinator.sendTo(pcId, "Office PC", listOf(big)) }
        withTimeout(15_000) { reading.await() }
        coordinator.cancel()
        val result = withTimeout(15_000) { send.await() }
        withTimeout(15_000) { receive.await() }

        assertEquals(SendResult.Cancelled, result)
        assertTrue("the file is closed on the way out", stream.closed)
        assertEquals(TransferStage.CANCELLED, coordinator.activeTransfer.value?.stage)
        assertTrue(staging.walkTopDown().none { it.isFile })
        phoneEnd.close()
        pcEnd.close()
    }

    @Test
    fun `a folder file that grew since it was listed fails the send instead of arriving cut short`() = runBlocking {
        val (phoneEnd, pcEnd) = pair()
        val manager = manager()
        assertTrue(manager.adoptForTest(phoneEnd))
        pcEnd.advertise(Capability.FILE_TRANSFER, Capability.FOLDER_TRANSFER)
        val coordinator = coordinator(manager)
        val pcInbox = tempDir.newFolder("pc")
        val receive = scope.async { runCatching { TransferRunner(dispatchers).receive(pcEnd, pcInbox) { true } } }
        val grown = streamed("Trip/grown.txt", "0123456789".toByteArray()) { ByteArrayInputStream("0123456789 and more".toByteArray()) }

        val result = withTimeout(15_000) { coordinator.sendTo(pcId, "Office PC", listOf(grown)) }
        withTimeout(15_000) { receive.await() }

        assertTrue("send: $result", result is SendResult.Failed)
        assertFalse(File(pcInbox, "Trip/grown.txt").exists())
        phoneEnd.close()
        pcEnd.close()
    }
}
