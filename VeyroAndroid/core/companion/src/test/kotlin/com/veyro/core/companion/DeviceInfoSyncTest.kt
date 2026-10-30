package com.veyro.core.companion

import com.veyro.core.link.PeerMessage
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DeviceInfoSync] with fakes (ADR-059): names and the auto-fetch switch, out and in. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceInfoSyncTest {

    private class FakeLink(caps: Capabilities, override val peerDeviceId: String) : CompanionLink {
        val caps = MutableStateFlow(caps)
        val name = MutableStateFlow<String?>(null)
        val sent = mutableListOf<PeerMessage>()
        override val peerCapabilities: Flow<Capabilities> get() = caps
        override val incoming: Flow<PeerMessage> = MutableSharedFlow()
        override val peerAnnouncedName: Flow<String?> get() = name
        override suspend fun send(message: PeerMessage) {
            sent += message
        }
        val infos get() = sent.filterIsInstance<PeerMessage.DeviceInfo>()
    }

    private class Rig(
        val links: MutableStateFlow<List<CompanionLink>>,
        val localName: MutableStateFlow<String>,
        val clipReceive: MutableStateFlow<Boolean>,
        val stored: MutableList<Pair<String, String>>,
    )

    private val infoCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.DEVICE_INFO)

    private fun TestScope.rig(vararg links: CompanionLink): Rig {
        val rig = Rig(MutableStateFlow(links.toList()), MutableStateFlow("Pixel"), MutableStateFlow(true), mutableListOf())
        DeviceInfoSync(
            links = rig.links,
            localName = rig.localName,
            clipReceive = rig.clipReceive,
            storeAnnouncedName = { id, name -> rig.stored += id to name },
            scope = backgroundScope,
        ).start()
        runCurrent()
        return rig
    }

    @Test
    fun `announces name and auto-fetch on connect, only to a capable peer`() = runTest {
        val pc = FakeLink(infoCaps, "PC-A")
        val old = FakeLink(Capabilities.of(Capability.FILE_TRANSFER), "PC-OLD")
        rig(pc, old)
        assertEquals(listOf(PeerMessage.DeviceInfo(name = "Pixel", clipReceive = true)), pc.infos)
        assertTrue(old.sent.isEmpty())
    }

    @Test
    fun `a rename reaches every connected device at once, once`() = runTest {
        val a = FakeLink(infoCaps, "PC-A")
        val b = FakeLink(infoCaps, "PHONE-B")
        val rig = rig(a, b)
        rig.localName.value = "Work phone"
        runCurrent()
        rig.localName.value = "Work phone"
        runCurrent()
        for (link in listOf(a, b)) {
            assertEquals(listOf("Pixel", "Work phone"), link.infos.map { it.name })
        }
    }

    @Test
    fun `auto-fetch off, on, off is announced live, each change once`() = runTest {
        val pc = FakeLink(infoCaps, "PC-A")
        val rig = rig(pc)
        rig.clipReceive.value = false
        runCurrent()
        rig.clipReceive.value = true
        runCurrent()
        rig.clipReceive.value = false
        runCurrent()
        assertEquals(listOf(true, false, true, false), pc.infos.map { it.clipReceive })
        assertTrue(pc.infos.all { it.name == "Pixel" })
    }

    @Test
    fun `a peer that gains the bit later is told then`() = runTest {
        val pc = FakeLink(Capabilities.of(Capability.FILE_TRANSFER), "PC-A")
        rig(pc)
        assertTrue(pc.sent.isEmpty())
        pc.caps.value = infoCaps
        runCurrent()
        assertEquals(1, pc.infos.size)
    }

    @Test
    fun `the peer's own name is stored whenever it changes, valid names only`() = runTest {
        val pc = FakeLink(infoCaps, "PC-A")
        val rig = rig(pc)
        pc.name.value = "Office PC"
        runCurrent()
        pc.name.value = "Office PC"
        runCurrent()
        pc.name.value = "Bad\u0007name"
        runCurrent()
        pc.name.value = "Study PC"
        runCurrent()
        assertEquals(listOf("PC-A" to "Office PC", "PC-A" to "Study PC"), rig.stored)
    }

    @Test
    fun `a name from a peer without the bit, from its hello, is stored too`() = runTest {
        // An offline device catches up from its next hello, whatever it advertises.
        val pc = FakeLink(Capabilities.of(Capability.FILE_TRANSFER), "PC-A")
        val rig = rig(pc)
        pc.name.value = "Renamed while away"
        runCurrent()
        assertEquals(listOf("PC-A" to "Renamed while away"), rig.stored)
        assertTrue("nothing is sent to it", pc.sent.isEmpty())
    }
}
