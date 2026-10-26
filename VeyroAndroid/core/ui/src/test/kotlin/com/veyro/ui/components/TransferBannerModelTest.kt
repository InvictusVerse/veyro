package com.veyro.ui.components

import com.veyro.core.model.ActiveTransfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The banner's state machine (ADR-038 point 4), on a test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferBannerModelTest {

    private class Rig(val model: TransferBannerModel, val transfers: MutableStateFlow<ActiveTransfer?>, val cleared: MutableList<String>)

    private fun TestScope.rig(): Rig {
        val transfers = MutableStateFlow<ActiveTransfer?>(null)
        val cleared = mutableListOf<String>()
        val model = TransferBannerModel(
            scope = backgroundScope,
            transfers = transfers,
            clear = { id ->
                cleared += id
                // Like the coordinator: clearing removes the record the flow carries.
                if (transfers.value?.transferId == id) transfers.value = null
            },
        )
        runCurrent()
        return Rig(model, transfers, cleared)
    }

    private fun transfer(id: String, stage: TransferStage, sent: Long = 0) = ActiveTransfer(
        transferId = id,
        direction = TransferDirection.SEND,
        peerDisplayName = "Office PC",
        currentFileName = "report.pdf",
        bytesTransferred = sent,
        totalBytes = 1_000,
        stage = stage,
    )

    @Test
    fun `a running transfer is shown and updated in place`() = runTest {
        val rig = rig()
        rig.transfers.value = transfer("t1", TransferStage.TRANSFERRING, sent = 100)
        runCurrent()
        assertEquals(100L, rig.model.shown.value?.bytesTransferred)

        rig.transfers.value = transfer("t1", TransferStage.TRANSFERRING, sent = 600)
        runCurrent()
        assertEquals("t1", rig.model.shown.value?.transferId)
        assertEquals(600L, rig.model.shown.value?.bytesTransferred)
    }

    @Test
    fun `success slides out by itself after about 3 seconds`() = runTest {
        val rig = rig()
        rig.transfers.value = transfer("t1", TransferStage.TRANSFERRING)
        runCurrent()
        rig.transfers.value = transfer("t1", TransferStage.COMPLETED, sent = 1_000)
        runCurrent()

        advanceTimeBy(TransferBannerModel.AUTO_DISMISS_MS - 1)
        runCurrent()
        assertEquals("still visible just before the timer", TransferStage.COMPLETED, rig.model.shown.value?.stage)

        advanceTimeBy(1)
        runCurrent()
        assertNull(rig.model.shown.value)
        assertEquals(listOf("t1"), rig.cleared)
    }

    @Test
    fun `a failure stays until dismissed`() = runTest {
        val rig = rig()
        rig.transfers.value = transfer("t1", TransferStage.FAILED)
        runCurrent()
        advanceTimeBy(TransferBannerModel.AUTO_DISMISS_MS * 10)
        runCurrent()
        assertEquals(TransferStage.FAILED, rig.model.shown.value?.stage)
        assertTrue(rig.cleared.isEmpty())

        rig.model.dismiss()
        runCurrent()
        assertNull(rig.model.shown.value)
        assertEquals(listOf("t1"), rig.cleared)
    }

    @Test
    fun `dismissing a running transfer hides it without stopping it, and a later failure comes back`() = runTest {
        val rig = rig()
        rig.transfers.value = transfer("t1", TransferStage.TRANSFERRING, sent = 100)
        runCurrent()
        rig.model.dismiss()
        runCurrent()
        assertNull(rig.model.shown.value)
        assertTrue("a running transfer's record is kept", rig.cleared.isEmpty())

        rig.transfers.value = transfer("t1", TransferStage.TRANSFERRING, sent = 900)
        runCurrent()
        assertNull("progress does not bring it back", rig.model.shown.value)

        rig.transfers.value = transfer("t1", TransferStage.FAILED)
        runCurrent()
        assertEquals("a failure is never silent", TransferStage.FAILED, rig.model.shown.value?.stage)
    }

    @Test
    fun `a hidden transfer that succeeds is cleared quietly`() = runTest {
        val rig = rig()
        rig.transfers.value = transfer("t1", TransferStage.TRANSFERRING)
        runCurrent()
        rig.model.dismiss()
        rig.transfers.value = transfer("t1", TransferStage.COMPLETED, sent = 1_000)
        runCurrent()
        assertNull(rig.model.shown.value)
        assertEquals(listOf("t1"), rig.cleared)
    }

    @Test
    fun `a new transfer replaces a finished one and cancels its timer`() = runTest {
        val rig = rig()
        rig.transfers.value = transfer("t1", TransferStage.COMPLETED, sent = 1_000)
        runCurrent()
        advanceTimeBy(1_000)
        rig.transfers.value = transfer("t2", TransferStage.TRANSFERRING)
        runCurrent()
        advanceTimeBy(TransferBannerModel.AUTO_DISMISS_MS * 2)
        runCurrent()
        assertEquals("t2", rig.model.shown.value?.transferId)
        assertTrue("the old timer must not clear the new transfer", rig.cleared.isEmpty())
    }

    @Test
    fun `banner wording names the direction and outcome`() {
        assertEquals("Sending to Office PC", bannerTitle(transfer("t", TransferStage.TRANSFERRING)))
        assertEquals("Sent to Office PC", bannerTitle(transfer("t", TransferStage.COMPLETED)))
        assertEquals(
            "Received from Office PC",
            bannerTitle(transfer("t", TransferStage.COMPLETED).copy(direction = TransferDirection.RECEIVE)),
        )
        val running = transfer("t", TransferStage.TRANSFERRING, sent = 500).copy(bytesPerSecond = 2L * 1024 * 1024)
        assertEquals("500 B of 1000 B, 2.0 MB/s", bannerDetail(running))
        val failed = transfer("t", TransferStage.FAILED).copy(errorMessage = "Office PC declined")
        assertEquals("Office PC declined", bannerDetail(failed))
    }
}
