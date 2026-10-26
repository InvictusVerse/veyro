package com.veyro.ui.components

import com.veyro.core.model.ActiveTransfer
import com.veyro.core.model.DeviceId
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferRoute
import com.veyro.core.model.TransferStage
import com.veyro.core.model.VerificationResult
import com.veyro.ui.format.formatBytes
import com.veyro.ui.format.formatEta
import com.veyro.ui.format.formatSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTransferBannerModelTest {

    @Test
    fun `ActiveTransfer terminal states are accurately classified`() {
        val completed = ActiveTransfer(
            transferId = "t1",
            direction = TransferDirection.RECEIVE,
            stage = TransferStage.COMPLETED,
            isVerified = true,
            verification = VerificationResult.VERIFIED,
        )
        assertTrue("COMPLETED stage must be terminal", completed.isTerminal)

        val failed = ActiveTransfer(
            transferId = "t2",
            direction = TransferDirection.RECEIVE,
            stage = TransferStage.FAILED,
            errorMessage = "Socket reset",
        )
        assertTrue("FAILED stage must be terminal", failed.isTerminal)

        val cancelled = ActiveTransfer(
            transferId = "t3",
            direction = TransferDirection.SEND,
            stage = TransferStage.CANCELLED,
        )
        assertTrue("CANCELLED stage must be terminal", cancelled.isTerminal)

        val active = ActiveTransfer(
            transferId = "t4",
            direction = TransferDirection.SEND,
            stage = TransferStage.TRANSFERRING,
            bytesTransferred = 50_000_000L,
            totalBytes = 100_000_000L,
        )
        assertFalse("TRANSFERRING stage must not be terminal", active.isTerminal)
    }

    @Test
    fun `ActiveTransfer overallFraction calculates truthful ratio and clamps within 0 and 1`() {
        val transferZeroTotal = ActiveTransfer(
            transferId = "tz",
            direction = TransferDirection.SEND,
            bytesTransferred = 0L,
            totalBytes = 0L,
        )
        assertEquals(0f, transferZeroTotal.overallFraction, 0.001f)

        val transferPartial = ActiveTransfer(
            transferId = "tp",
            direction = TransferDirection.SEND,
            bytesTransferred = 46_160_466L,
            totalBytes = 92_320_932L,
        )
        assertEquals(0.5f, transferPartial.overallFraction, 0.001f)

        val transferOver = ActiveTransfer(
            transferId = "to",
            direction = TransferDirection.SEND,
            bytesTransferred = 100_000_000L,
            totalBytes = 92_320_932L,
        )
        assertEquals(1.0f, transferOver.overallFraction, 0.001f)
    }

    @Test
    fun `route badge distinguishes P2P Wi-Fi Direct from LAN TCP`() {
        fun badgeLabel(route: TransferRoute?): String? = when (route) {
            TransferRoute.WIFI_DIRECT -> "P2P"
            TransferRoute.LAN_TCP -> "LAN"
            null -> null
        }

        assertEquals("P2P", badgeLabel(TransferRoute.WIFI_DIRECT))
        assertEquals("LAN", badgeLabel(TransferRoute.LAN_TCP))
        assertEquals(null, badgeLabel(null))
    }

    @Test
    fun `format functions report truthful units for real transfer benchmarks`() {
        val fileSize = 92_320_932L // 88.0 MB (92.3 MB)
        assertEquals("88.0 MB", formatBytes(fileSize))

        val speedBytes = (2.7 * 1024 * 1024).toLong() // 2.7 MB/s
        assertEquals("2.7 MB/s", formatSpeed(speedBytes))

        val fastSpeedBytes = (25.5 * 1024 * 1024).toLong() // 25.5 MB/s (Wi-Fi Direct speed)
        assertEquals("25.5 MB/s", formatSpeed(fastSpeedBytes))

        assertEquals("30s left", formatEta(30L))
        assertEquals("2m left", formatEta(120L))
        assertEquals(null, formatEta(null))
        assertEquals(null, formatEta(0L))
    }

    @Test
    fun `distinct transfer sessions maintain independent identity`() {
        val transferA = ActiveTransfer(
            transferId = "tid-aaa",
            direction = TransferDirection.SEND,
            peerDeviceId = DeviceId("dev-xiaomi"),
            peerDisplayName = "Xiaomi 22041216I",
            currentFileName = "video.mp4",
            totalBytes = 92_320_932L,
        )
        val transferB = ActiveTransfer(
            transferId = "tid-bbb",
            direction = TransferDirection.RECEIVE,
            peerDeviceId = DeviceId("dev-samsung"),
            peerDisplayName = "Samsung Galaxy A20s",
            currentFileName = "notes.pdf",
            totalBytes = 5_000_000L,
        )

        assertNotEquals("Transfers must have unique IDs", transferA.transferId, transferB.transferId)
        assertNotEquals("Transfers must preserve distinct peers", transferA.peerDisplayName, transferB.peerDisplayName)
        assertNotEquals("Transfers must preserve directions", transferA.direction, transferB.direction)
    }
}
