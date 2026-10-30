package com.veyro.ui.components

import com.veyro.core.model.ActiveTransfer
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Platform
import com.veyro.core.model.Transfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferStage
import com.veyro.core.model.TransferState
import com.veyro.core.model.TrustedPeer
import com.veyro.ui.format.ReceivedFolders
import com.veyro.ui.home.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-064: how a folder reads in the banner, the notification, Recent and where it is saved. */
class FolderWordingTest {

    private fun sending(folder: String?) = ActiveTransfer(
        transferId = "t",
        direction = TransferDirection.SEND,
        peerDisplayName = "Office PC",
        currentFileName = "IMG_0001.jpg",
        currentFileIndex = 2,
        totalFiles = 120,
        bytesTransferred = 10,
        totalBytes = 100,
        stage = TransferStage.TRANSFERRING,
        folderName = folder,
    )

    @Test
    fun `a folder reads as the folder and its file count`() {
        assertEquals("Sending to Office PC", bannerTitle(sending("Photos")))
        assertEquals("Photos folder, 120 files", bannerFileLine(sending("Photos")))
        assertTrue(bannerDetail(sending("Photos")).endsWith("file 3 of 120"))
        assertEquals("loose files keep the file name", "IMG_0001.jpg", bannerFileLine(sending(null)))
    }

    @Test
    fun `received folder items keep their structure, loose files go by type`() {
        assertEquals("Download/Veyro/Trip/Day 1", ReceivedFolders.dirFor("Trip/Day 1/a.jpg", "image/jpeg"))
        assertEquals("Download/Veyro/Trip", ReceivedFolders.dirFor("Trip/notes.txt"))
        assertEquals("Download/Veyro/Photos", ReceivedFolders.dirFor("a.jpg", "image/jpeg"))
        assertTrue(ReceivedFolders.isTypeFolder("photos"))
        assertFalse(ReceivedFolders.isTypeFolder("Trip"))
    }

    private val pc = TrustedPeer(
        deviceId = DeviceId.fromPublicKey(ByteArray(32) { 5 }),
        staticPublicKey = ByteArray(32) { 5 },
        pairingSecret = ByteArray(32) { 6 },
        name = "Office PC",
        platform = Platform.WINDOWS,
        pairedAtEpochMillis = 1L,
    )

    @Test
    fun `send folder needs a connected device to take folders`() {
        val away = HomeUiState(pairedPeers = listOf(pc))
        assertTrue("one not connected is tried", away.canSendFolderToSelected)

        val old = away.copy(
            connectedPeerIds = setOf(pc.deviceId),
            peerCapabilities = mapOf(pc.deviceId.value to Capabilities.of(Capability.FILE_TRANSFER)),
        )
        assertFalse(old.canSendFolderToSelected)

        val current = old.copy(peerCapabilities = mapOf(pc.deviceId.value to Capabilities.of(Capability.FILE_TRANSFER, Capability.FOLDER_TRANSFER)))
        assertTrue(current.canSendFolderToSelected)
    }

    @Test
    fun `recent shows a folder as one entry`() {
        val transfer = Transfer(
            id = "r",
            peerDeviceId = pc.deviceId,
            peerDisplayName = "Office PC",
            direction = TransferDirection.RECEIVE,
            items = listOf(
                TransferItem(0, "Trip/a.jpg", 1, 0),
                TransferItem(1, "Trip/b.jpg", 1, 0),
                TransferItem(2, "Trip/Empty", 0, 0, isDirectory = true),
            ),
            totalBytes = 2,
            state = TransferState.COMPLETED,
            startedAtEpochMillis = 1,
            finishedAtEpochMillis = 2,
        )
        val recent = HomeUiState(history = listOf(transfer)).recent!!
        assertEquals("Trip folder, 2 files", recent.fileName)
        assertEquals(0, recent.moreCount)
    }
}
