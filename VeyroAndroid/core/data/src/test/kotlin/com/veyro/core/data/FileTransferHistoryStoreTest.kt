package com.veyro.core.data

import com.veyro.core.model.DeviceId
import com.veyro.core.model.Transfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferState
import com.veyro.core.model.VerificationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Transfer history persistence, schema migration compatibility, and idempotent upserts.
 */
class FileTransferHistoryStoreTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private lateinit var dir: File
    private val peerId = DeviceId("15SBN9RA00000000")

    @Before
    fun setUp() {
        dir = temp.newFolder("history")
    }

    private fun store(maxEntries: Int = 200) = FileTransferHistoryStore(dir, maxEntries)

    @Test
    fun `starts empty when no file exists`() = runBlocking {
        val s = store()
        assertTrue(s.history.first().isEmpty())
    }

    @Test
    fun `append persists record and loads across store instances`() = runBlocking {
        val s1 = store()
        val item = TransferItem(
            index = 0,
            relativePath = "IMG_20261011_171634.jpg",
            sizeBytes = 2_432_168L,
            lastModifiedEpochMillis = 1728647794000L,
            isDirectory = false,
            mimeType = "image/jpeg",
            destinationUri = "content://media/external/images/media/42",
            verification = VerificationResult.VERIFIED,
        )
        val transfer = Transfer(
            id = "ea3cc227",
            peerDeviceId = peerId,
            peerDisplayName = "Xiaomi 22041216I",
            direction = TransferDirection.RECEIVE,
            items = listOf(item),
            totalBytes = 2_432_168L,
            state = TransferState.COMPLETED,
            startedAtEpochMillis = 1728647790000L,
            finishedAtEpochMillis = 1728647794000L,
            destinationUri = "content://media/external/images/media/42",
        )

        s1.append(transfer)
        val history1 = s1.history.first()
        assertEquals(1, history1.size)
        assertEquals("ea3cc227", history1[0].id)
        assertEquals("Xiaomi 22041216I", history1[0].peerDisplayName)
        assertEquals("content://media/external/images/media/42", history1[0].destinationUri)
        assertEquals("image/jpeg", history1[0].items[0].mimeType)
        assertEquals(VerificationResult.VERIFIED, history1[0].items[0].verification)

        // Simulate app restart by instantiating new store on same directory
        val s2 = store()
        val history2 = s2.history.first()
        assertEquals(1, history2.size)
        val loaded = history2[0]
        assertEquals("ea3cc227", loaded.id)
        assertEquals(peerId, loaded.peerDeviceId)
        assertEquals("Xiaomi 22041216I", loaded.peerDisplayName)
        assertEquals(TransferDirection.RECEIVE, loaded.direction)
        assertEquals(TransferState.COMPLETED, loaded.state)
        assertEquals(2_432_168L, loaded.totalBytes)
        assertEquals("content://media/external/images/media/42", loaded.destinationUri)
        assertEquals(1, loaded.items.size)
        assertEquals("IMG_20261011_171634.jpg", loaded.items[0].relativePath)
        assertEquals("image/jpeg", loaded.items[0].mimeType)
        assertEquals(VerificationResult.VERIFIED, loaded.items[0].verification)
    }

    @Test
    fun `append is idempotent and replaces by transferId without duplication`() = runBlocking {
        val s = store()
        val initial = Transfer(
            id = "tid-100",
            peerDeviceId = peerId,
            peerDisplayName = "Peer 1",
            direction = TransferDirection.RECEIVE,
            items = listOf(TransferItem(0, "file.png", 100, 0, false)),
            totalBytes = 100,
            state = TransferState.TRANSFERRING,
            startedAtEpochMillis = 1000L,
        )
        s.append(initial)
        assertEquals(1, s.history.first().size)
        assertEquals(TransferState.TRANSFERRING, s.history.first()[0].state)

        // Updated completed record with same transferId
        val completed = initial.copy(
            state = TransferState.COMPLETED,
            finishedAtEpochMillis = 2000L,
            destinationUri = "content://media/external/images/media/99",
        )
        s.append(completed)

        val list = s.history.first()
        assertEquals("Must not create duplicate entry for same transferId", 1, list.size)
        assertEquals(TransferState.COMPLETED, list[0].state)
        assertEquals("content://media/external/images/media/99", list[0].destinationUri)
    }

    @Test
    fun `backwards compatibility with legacy JSON records without rich fields`() = runBlocking {
        // Write raw legacy JSON format with only itemPaths and without items/peerDisplayName
        val legacyJson = """
            {
              "version": 1,
              "transfers": [
                {
                  "id": "legacy-1",
                  "peerDeviceId": "${peerId.value}",
                  "direction": "RECEIVE",
                  "state": "COMPLETED",
                  "totalBytes": 12345,
                  "startedAt": 1000,
                  "finishedAt": 2000,
                  "itemPaths": ["photo.jpg"],
                  "itemCount": 1
                }
              ]
            }
        """.trimIndent()
        File(dir, "history.json").writeText(legacyJson)

        val s = store()
        val history = s.history.first()
        assertEquals(1, history.size)
        val record = history[0]
        assertEquals("legacy-1", record.id)
        assertEquals(peerId, record.peerDeviceId)
        assertEquals(null, record.peerDisplayName)
        assertEquals(1, record.items.size)
        assertEquals("photo.jpg", record.items[0].relativePath)
        assertEquals(12345L, record.totalBytes)
    }

    @Test
    fun `history enforces maxEntries capacity`() = runBlocking {
        val s = store(maxEntries = 3)
        for (i in 1..5) {
            s.append(
                Transfer(
                    id = "id-$i",
                    peerDeviceId = peerId,
                    direction = TransferDirection.SEND,
                    items = emptyList(),
                    totalBytes = i.toLong(),
                    state = TransferState.COMPLETED,
                    startedAtEpochMillis = i.toLong(),
                )
            )
        }
        val list = s.history.first()
        assertEquals(3, list.size)
        assertEquals("id-5", list[0].id)
        assertEquals("id-4", list[1].id)
        assertEquals("id-3", list[2].id)
    }

    @Test
    fun `clear deletes history file and resets state`() = runBlocking {
        val s = store()
        s.append(
            Transfer(
                id = "clear-test",
                peerDeviceId = peerId,
                direction = TransferDirection.RECEIVE,
                items = emptyList(),
                totalBytes = 10,
                state = TransferState.COMPLETED,
                startedAtEpochMillis = 10,
            )
        )
        assertEquals(1, s.history.first().size)
        s.clear()
        assertTrue(s.history.first().isEmpty())
        assertTrue(!File(dir, "history.json").exists())
    }
}
