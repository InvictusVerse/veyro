package com.veyro.di

import com.veyro.core.model.DeviceId
import com.veyro.core.model.Platform
import com.veyro.core.model.TrustedPeer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Send files with several PCs: "All connected", one chosen PC, and the old single-PC fallback. */
class SendTargetsTest {

    private fun peer(seed: Int, name: String, lastConnected: Long = 0L): TrustedPeer {
        val pk = ByteArray(32) { seed.toByte() }
        return TrustedPeer(
            deviceId = DeviceId.fromPublicKey(pk),
            staticPublicKey = pk,
            pairingSecret = ByteArray(32),
            name = name,
            platform = Platform.WINDOWS,
            pairedAtEpochMillis = 1L,
            lastConnectedEpochMillis = lastConnected,
        )
    }

    private val office = peer(1, "Office PC")
    private val laptop = peer(2, "Laptop", lastConnected = 50L)
    private val revoked = peer(3, "Old PC").copy(revoked = true)
    private val paired = listOf(office, laptop, revoked)

    @Test
    fun `send to all connected sends the same files to every PC, one after the other`() = runTest {
        val sentTo = mutableListOf<String>()
        val count = SendTargets.sendToEach(
            paired = paired,
            deviceIds = listOf(office.deviceId.value, laptop.deviceId.value),
            selectedConnected = office.deviceId.value,
        ) { sentTo += it.name }

        assertEquals(2, count)
        assertEquals(listOf("Office PC", "Laptop"), sentTo)
    }

    @Test
    fun `one chosen PC gets the files, and revoked or unknown ids are dropped`() {
        assertEquals(listOf(laptop), SendTargets.resolve(paired, listOf(laptop.deviceId.value), office.deviceId.value))
        assertEquals(
            emptyList<TrustedPeer>(),
            SendTargets.resolve(paired, listOf(revoked.deviceId.value, "not-a-pc"), null),
        )
        assertEquals(
            "a repeated id is sent to once",
            listOf(office),
            SendTargets.resolve(paired, listOf(office.deviceId.value, office.deviceId.value), null),
        )
    }

    @Test
    fun `no choice means the selected connected PC, else the most recently used`() {
        assertEquals(listOf(office), SendTargets.resolve(paired, emptyList(), office.deviceId.value))
        assertEquals(listOf(laptop), SendTargets.resolve(paired, emptyList(), null))
        assertEquals(emptyList<TrustedPeer>(), SendTargets.resolve(emptyList(), emptyList(), null))
    }
}
