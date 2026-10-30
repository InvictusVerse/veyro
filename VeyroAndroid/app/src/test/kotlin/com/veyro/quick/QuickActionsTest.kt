package com.veyro.quick

import com.veyro.core.model.DeviceId
import com.veyro.core.model.Platform
import com.veyro.core.model.TrustedPeer
import com.veyro.ui.clipboard.SendClipOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-062: what each Quick Settings tile and the widget show for the connection state. */
class QuickActionsTest {

    private fun peer(seed: Int, name: String, platform: Platform = Platform.WINDOWS, lastConnected: Long = 0L): TrustedPeer {
        val pk = ByteArray(32) { seed.toByte() }
        return TrustedPeer(
            deviceId = DeviceId.fromPublicKey(pk),
            staticPublicKey = pk,
            pairingSecret = ByteArray(32),
            name = name,
            platform = platform,
            pairedAtEpochMillis = 1L,
            lastConnectedEpochMillis = lastConnected,
        )
    }

    private val office = peer(1, "Office PC", lastConnected = 10L)
    private val laptop = peer(2, "Laptop", lastConnected = 50L)
    private val pixel = peer(3, "Pixel", Platform.ANDROID, lastConnected = 99L)
    private val old = peer(4, "Old PC").copy(revoked = true)
    private val all = listOf(office, laptop, pixel, old)

    private fun snapshot(
        connected: Set<TrustedPeer> = emptySet(),
        lockable: Set<TrustedPeer> = connected,
        selected: TrustedPeer? = null,
        paused: Set<TrustedPeer> = emptySet(),
        blocked: Boolean = false,
        peers: List<TrustedPeer> = all,
    ) = QuickSnapshot.of(
        peers = peers,
        connectedIds = connected.map { it.deviceId.value }.toSet(),
        lockable = lockable.map { it.deviceId.value }.toSet(),
        selectedId = selected?.deviceId?.value,
        disconnectedByUser = paused.map { it.deviceId.value }.toSet(),
        localNetworkBlocked = blocked,
    )

    @Test
    fun `devices are connected first, then most recent, revoked left out, phones never lockable`() {
        val s = snapshot(connected = setOf(office, pixel), lockable = setOf(office, pixel))
        assertEquals(listOf("Pixel", "Office PC", "Laptop"), s.devices.map { it.name })
        assertFalse(s.devices.first { it.name == "Pixel" }.canLock)
        assertTrue(s.devices.first { it.name == "Office PC" }.canLock)
        assertEquals("Office PC", s.lockTarget?.name)
    }

    @Test
    fun `the primary device is Home's choice while connected, else a connected one, else the most recent`() {
        assertEquals("Laptop", snapshot(connected = setOf(office, laptop), selected = laptop).primary?.name)
        assertEquals("Office PC", snapshot(connected = setOf(office), selected = laptop).primary?.name)
        assertEquals("Laptop", snapshot(selected = laptop).primary?.name)
        assertEquals("Pixel", snapshot().primary?.name)
        assertNull(snapshot(peers = emptyList()).primary)
        // An id that is not paired (revoked, or stale) is ignored.
        assertNull(snapshot(selected = old).selectedId)
    }

    @Test
    fun `Send clipboard needs a connected device and names it`() {
        assertEquals(TileSpec("Send clipboard", "To Office PC", TileLook.ACTIVE), QuickActions.sendClipboardTile(snapshot(connected = setOf(office))))
        assertEquals(TileSpec("Send clipboard", "To 2 devices", TileLook.ACTIVE), QuickActions.sendClipboardTile(snapshot(connected = setOf(office, pixel))))
        assertEquals(TileLook.UNAVAILABLE, QuickActions.sendClipboardTile(snapshot()).look)
        assertEquals("Not connected", QuickActions.sendClipboardTile(snapshot()).subtitle)
        assertEquals("No device paired", QuickActions.sendClipboardTile(snapshot(peers = emptyList())).subtitle)
    }

    @Test
    fun `Send files works for an offline device too, inactive rather than unavailable`() {
        val online = QuickActions.sendFilesTile(snapshot(connected = setOf(laptop), selected = laptop))
        assertEquals(TileSpec("Send files", "To Laptop", TileLook.ACTIVE), online)
        val offline = QuickActions.sendFilesTile(snapshot(selected = office))
        assertEquals(TileSpec("Send files", "To Office PC", TileLook.INACTIVE), offline)
        assertEquals(TileClick.ACT, QuickActions.click(offline, snapshot(selected = office)))
        assertEquals(TileLook.UNAVAILABLE, QuickActions.sendFilesTile(snapshot(peers = emptyList())).look)
    }

    @Test
    fun `Lock PC is unavailable unless a connected PC supports it`() {
        val ok = QuickActions.lockPcTile(snapshot(connected = setOf(office)))
        assertEquals(TileSpec("Lock PC", "Office PC", TileLook.ACTIVE), ok)
        val unsupported = QuickActions.lockPcTile(snapshot(connected = setOf(office), lockable = emptySet()))
        assertEquals(TileSpec("Lock PC", "Not supported by this PC", TileLook.UNAVAILABLE), unsupported)
        assertEquals("No PC connected", QuickActions.lockPcTile(snapshot(connected = setOf(pixel))).subtitle)
        assertEquals("No PC paired", QuickActions.lockPcTile(snapshot(peers = listOf(pixel))).subtitle)
        assertEquals(TileClick.NONE, QuickActions.click(unsupported, snapshot(connected = setOf(office), lockable = emptySet())))
        // A phone is selected on Home: Lock PC still locks the connected PC.
        assertEquals("Office PC", QuickActions.lockPcTile(snapshot(connected = setOf(office, pixel), selected = pixel)).subtitle)
    }

    @Test
    fun `a blocked local network makes every tile open Veyro to explain`() {
        val s = snapshot(connected = emptySet(), blocked = true)
        for (spec in listOf(QuickActions.sendClipboardTile(s), QuickActions.sendFilesTile(s), QuickActions.lockPcTile(s))) {
            assertEquals("Allow Nearby devices", spec.subtitle)
            assertEquals(TileLook.INACTIVE, spec.look)
            assertEquals(TileClick.OPEN_APP, QuickActions.click(spec, s))
        }
    }

    @Test
    fun `the widget shows the device and Lock PC only for a PC`() {
        val pc = QuickActions.widget(snapshot(connected = setOf(office), selected = office))
        assertEquals("Office PC", pc.title)
        assertEquals("Connected", pc.status)
        assertTrue(pc.connected && pc.isPc && pc.showActions && pc.clipboardEnabled)
        assertTrue(pc.showLock && pc.lockEnabled)
        assertEquals(office.deviceId.value, pc.lockTargetId)
        assertEquals(office.deviceId.value, pc.sendTargetId)

        val phone = QuickActions.widget(snapshot(connected = setOf(pixel), selected = pixel))
        assertFalse(phone.isPc)
        assertFalse(phone.showLock)

        val offlinePc = QuickActions.widget(snapshot(selected = laptop))
        assertEquals("Not connected", offlinePc.status)
        assertTrue(offlinePc.showLock)
        assertFalse(offlinePc.lockEnabled)
        assertNull(offlinePc.lockTargetId)
        assertFalse(offlinePc.clipboardEnabled)

        assertEquals("Disconnected", QuickActions.widget(snapshot(selected = laptop, paused = setOf(laptop))).status)
        assertEquals("Allow Nearby devices in Veyro", QuickActions.widget(snapshot(selected = laptop, blocked = true)).status)
    }

    @Test
    fun `the widget with nothing paired invites pairing and hides the buttons`() {
        val empty = QuickActions.widget(QuickSnapshot.EMPTY)
        assertEquals("Veyro", empty.title)
        assertFalse(empty.showActions)
        assertFalse(empty.showLock)
        assertNull(empty.sendTargetId)
    }

    @Test
    fun `toasts use the device name when one device is connected`() {
        val one = snapshot(connected = setOf(office))
        assertEquals("Clipboard sent to Office PC", QuickActions.clipboardToast(SendClipOutcome.SENT, one))
        assertEquals("Clipboard sent to your devices", QuickActions.clipboardToast(SendClipOutcome.SENT, snapshot(connected = setOf(office, laptop))))
        assertEquals("Nothing copied on this phone", QuickActions.clipboardToast(SendClipOutcome.EMPTY, one))
        assertEquals("No device connected", QuickActions.clipboardToast(SendClipOutcome.NO_PC, snapshot()))
        assertEquals("Locking Office PC", QuickActions.lockToast(true, "Office PC"))
        assertEquals("Could not reach Office PC", QuickActions.lockToast(false, "Office PC"))
    }
}
