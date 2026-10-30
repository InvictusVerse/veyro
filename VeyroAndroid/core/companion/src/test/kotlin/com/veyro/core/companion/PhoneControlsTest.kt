package com.veyro.core.companion

import com.veyro.core.link.PeerMessage
import com.veyro.core.link.PeerMessage.PhoneControlState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-060: flashlight, media volume and brightness through [DeviceStatusPublisher], with fakes.
 * Status fields only for a PHONE_CONTROLS peer, the switch and the brightness grant gate every
 * change, ranges are validated, and the result goes back in `devStatus`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneControlsTest {

    private class FakeLink(caps: Capabilities) : CompanionLink {
        val caps = MutableStateFlow(caps)
        val inbound = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 16)
        val sent = mutableListOf<PeerMessage>()
        override val peerCapabilities: Flow<Capabilities> get() = caps
        override val incoming: Flow<PeerMessage> get() = inbound
        override val peerDeviceId: String = "PC-ID"
        override suspend fun send(message: PeerMessage) {
            sent += message
        }
        val statuses get() = sent.filterIsInstance<PeerMessage.DeviceStatus>()
    }

    private class FakeSource : DeviceStatusSource {
        override val changes: Flow<Unit> = MutableSharedFlow()
        override suspend fun current(): PeerMessage.DeviceStatus = PeerMessage.DeviceStatus(battery = 70)
    }

    private class FakeController : DeviceController {
        val calls = mutableListOf<String>()
        override suspend fun startRing(fromName: String?) {
            calls += "ring"
        }
        override suspend fun stopRing() {
            calls += "stop"
        }
        override fun hasPolicyAccess(): Boolean = true
        override suspend fun setRinger(mode: String) {
            calls += "ringer:$mode"
        }
        override suspend fun setDnd(enabled: Boolean) {
            calls += "dnd:$enabled"
        }
    }

    /** Applies what it is told to its own reading, like the phone would, and signals the change. */
    private class FakeControls : PhoneControls {
        val calls = mutableListOf<String>()
        val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        var reading = PhoneControlsReading(
            torch = false,
            torchPresent = true,
            mediaVolume = 40,
            volumeAdjustable = true,
            brightness = 75,
            autoBrightness = true,
            canWriteSettings = true,
        )
        var failTorch = false
        override val changes: Flow<Unit> get() = changeFlow
        override suspend fun current(): PhoneControlsReading = reading
        override suspend fun setTorch(on: Boolean) {
            if (failTorch) throw IllegalStateException("camera in use")
            calls += "torch:$on"
            reading = reading.copy(torch = on)
        }
        override suspend fun setMediaVolume(percent: Int) {
            calls += "volume:$percent"
            reading = reading.copy(mediaVolume = percent)
        }
        override suspend fun setBrightness(percent: Int) {
            calls += "brightness:$percent"
            reading = reading.copy(brightness = percent, autoBrightness = false)
        }
        override suspend fun setAutoBrightness(on: Boolean) {
            calls += "auto:$on"
            reading = reading.copy(autoBrightness = on)
        }
    }

    private class Rig(
        val controls: FakeControls,
        val allowed: MutableStateFlow<Boolean>,
        val ringAllowed: MutableStateFlow<Boolean>,
        val controller: FakeController,
        val links: MutableStateFlow<CompanionLink?>,
    )

    private val pcCaps = Capabilities.of(Capability.DEVICE_STATUS, Capability.PHONE_CONTROLS)

    private fun TestScope.rig(allowed: Boolean = true, ringAllowed: Boolean = true): Rig {
        val rig = Rig(FakeControls(), MutableStateFlow(allowed), MutableStateFlow(ringAllowed), FakeController(), MutableStateFlow(null))
        DeviceStatusPublisher(
            links = rig.links.asLinkList(),
            controlAllowed = rig.ringAllowed,
            source = FakeSource(),
            controller = rig.controller,
            peerName = { null },
            scope = backgroundScope,
            phoneControls = rig.controls,
            phoneControlsAllowed = rig.allowed,
        ).start()
        runCurrent()
        return rig
    }

    private fun TestScope.connect(rig: Rig, caps: Capabilities = pcCaps): FakeLink {
        val link = FakeLink(caps)
        rig.links.value = link
        runCurrent()
        return link
    }

    @Test
    fun `a PHONE_CONTROLS peer gets the values and why each can or cannot change`() = runTest {
        val rig = rig()
        val link = connect(rig)
        val status = link.statuses.single()
        assertEquals(70, status.battery)
        assertEquals(false, status.torch)
        assertEquals(40, status.mediaVolume)
        assertEquals(75, status.brightness)
        assertEquals(true, status.autoBrightness)
        assertEquals(PhoneControlState(PhoneControlState.OK, PhoneControlState.OK, PhoneControlState.OK), status.controls)
    }

    @Test
    fun `a peer without the bit, such as another phone, gets none of it and cannot use it`() = runTest {
        // Capabilities.PHONE_TO_PHONE has no PHONE_CONTROLS, so a phone's link looks like this.
        val rig = rig()
        val link = connect(rig, Capabilities.PHONE_TO_PHONE)
        val status = link.statuses.single()
        assertNull(status.torch)
        assertNull(status.mediaVolume)
        assertNull(status.controls)

        link.inbound.emit(PeerMessage.DeviceControl(torch = true, mediaVolume = 10, brightness = 10, autoBrightness = false))
        runCurrent()
        assertTrue(rig.controls.calls.isEmpty())
    }

    @Test
    fun `with the switch off nothing changes and every control reads off`() = runTest {
        val rig = rig(allowed = false)
        val link = connect(rig)
        assertEquals(PhoneControlState(PhoneControlState.OFF, PhoneControlState.OFF, PhoneControlState.OFF), link.statuses.last().controls)

        link.inbound.emit(PeerMessage.DeviceControl(torch = true, mediaVolume = 10))
        runCurrent()
        assertTrue(rig.controls.calls.isEmpty())
        assertEquals("the reply still goes out", 2, link.statuses.size)
    }

    @Test
    fun `turning the switch on or off is reported at once`() = runTest {
        val rig = rig(allowed = false)
        val link = connect(rig)
        rig.allowed.value = true
        runCurrent()
        rig.allowed.value = false
        runCurrent()
        assertEquals(
            listOf(PhoneControlState.OFF, PhoneControlState.OK, PhoneControlState.OFF),
            link.statuses.map { it.controls?.volume },
        )
    }

    @Test
    fun `flashlight, volume and brightness are applied and the result reported`() = runTest {
        val rig = rig()
        val link = connect(rig)
        link.inbound.emit(PeerMessage.DeviceControl(torch = true))
        link.inbound.emit(PeerMessage.DeviceControl(mediaVolume = 0))
        link.inbound.emit(PeerMessage.DeviceControl(mediaVolume = 100, brightness = 30, autoBrightness = false))
        runCurrent()

        assertEquals(listOf("torch:true", "volume:0", "volume:100", "brightness:30", "auto:false"), rig.controls.calls)
        val last = link.statuses.last()
        assertEquals(true, last.torch)
        assertEquals(100, last.mediaVolume)
        assertEquals(30, last.brightness)
        assertEquals(false, last.autoBrightness)
    }

    @Test
    fun `out-of-range values are ignored, not clamped`() = runTest {
        val rig = rig()
        val link = connect(rig)
        link.inbound.emit(PeerMessage.DeviceControl(mediaVolume = 101))
        link.inbound.emit(PeerMessage.DeviceControl(mediaVolume = -1, brightness = 250))
        link.inbound.emit(PeerMessage.DeviceControl(brightness = Int.MIN_VALUE))
        runCurrent()
        assertTrue(rig.controls.calls.isEmpty())
    }

    @Test
    fun `brightness needs the system grant and says so, the rest still works`() = runTest {
        val rig = rig()
        rig.controls.reading = rig.controls.reading.copy(canWriteSettings = false)
        val link = connect(rig)
        assertEquals(PhoneControlState.PERMISSION, link.statuses.single().controls?.brightness)
        assertEquals(PhoneControlState.OK, link.statuses.single().controls?.volume)

        link.inbound.emit(PeerMessage.DeviceControl(brightness = 50, autoBrightness = true, mediaVolume = 20))
        runCurrent()
        assertEquals(listOf("volume:20"), rig.controls.calls)
    }

    @Test
    fun `a busy or missing flashlight is reported, and a failure does not stop the rest`() = runTest {
        val rig = rig()
        rig.controls.reading = rig.controls.reading.copy(torchBusy = true)
        val link = connect(rig)
        assertEquals(PhoneControlState.BUSY, link.statuses.single().controls?.torch)

        rig.controls.failTorch = true
        link.inbound.emit(PeerMessage.DeviceControl(torch = true, mediaVolume = 60))
        runCurrent()
        assertEquals(listOf("volume:60"), rig.controls.calls)

        rig.controls.reading = rig.controls.reading.copy(torchPresent = false, torch = null)
        rig.controls.changeFlow.emit(Unit)
        runCurrent()
        val last = link.statuses.last()
        assertNull(last.torch)
        assertEquals(PhoneControlState.UNSUPPORTED, last.controls?.torch)
    }

    @Test
    fun `a change on the phone is sent once, an unchanged reading is not resent`() = runTest {
        val rig = rig()
        val link = connect(rig)
        repeat(3) { rig.controls.changeFlow.emit(Unit) }
        runCurrent()
        assertEquals(1, link.statuses.size)
        rig.controls.reading = rig.controls.reading.copy(mediaVolume = 41)
        rig.controls.changeFlow.emit(Unit)
        rig.controls.changeFlow.emit(Unit)
        runCurrent()
        assertEquals(listOf(40, 41), link.statuses.map { it.mediaVolume })
    }

    @Test
    fun `the two switches are separate`() = runTest {
        val rig = rig(allowed = true, ringAllowed = false)
        val link = connect(rig)
        link.inbound.emit(PeerMessage.DeviceControl(ring = true, torch = true))
        runCurrent()
        assertTrue("ring and sound mode is off", rig.controller.calls.isEmpty())
        assertEquals(listOf("torch:true"), rig.controls.calls)
    }

    @Test
    fun `percent math rounds and clamps`() {
        assertEquals(0, PhoneControlMath.toPercent(0, 0, 15))
        assertEquals(100, PhoneControlMath.toPercent(15, 0, 15))
        assertEquals(53, PhoneControlMath.toPercent(8, 0, 15))
        assertEquals(100, PhoneControlMath.toPercent(400, 0, 255))
        assertNull(PhoneControlMath.toPercent(3, 5, 5))
        assertEquals(8, PhoneControlMath.fromPercent(53, 0, 15))
        assertEquals(15, PhoneControlMath.fromPercent(100, 0, 15))
        assertEquals(1, PhoneControlMath.fromPercent(0, 1, 15))
        assertEquals(128, PhoneControlMath.fromPercent(50, 0, 255))
        assertTrue(PhoneControlMath.isPercent(0) && PhoneControlMath.isPercent(100))
        assertTrue(!PhoneControlMath.isPercent(101) && !PhoneControlMath.isPercent(-1) && !PhoneControlMath.isPercent(null))
    }

    @Test
    fun `the slider curve is AOSP's and inverts`() {
        assertEquals(0.0, PhoneControlMath.linearToSlider(0.0), 1e-9)
        assertEquals(1.0, PhoneControlMath.linearToSlider(1.0), 1e-6)
        // The two halves meet at 1/12 linear = half the slider.
        assertEquals(0.5, PhoneControlMath.linearToSlider(1.0 / 12.0), 1e-6)
        for (p in 0..100) {
            val back = PhoneControlMath.linearToSlider(PhoneControlMath.sliderToLinear(p / 100.0))
            assertEquals("slider $p", p / 100.0, back, 1e-6)
        }
    }

    @Test
    fun `Android 11 and later use 1 to 255 whatever the OEM config says`() {
        // The Redmi K50i (HyperOS, Android 14): setting 27, config maximum from MIUI's old scale.
        val range = PhoneControlMath.brightnessRange(sdkInt = 34, configMin = 1, configMax = 2047, observed = 27)
        assertEquals(1..255, range)
        val percent = PhoneControlMath.brightnessToPercent(27, range)!!
        assertTrue("about half on the phone's slider, was 1 %: $percent", percent in 53..57)
        assertEquals(255, PhoneControlMath.percentToBrightness(100, range))
        assertEquals("0 % is the dimmest, never 0 (off)", 1, PhoneControlMath.percentToBrightness(0, range))
    }

    @Test
    fun `Android 10 uses the config range, and a larger OEM scale widens it`() {
        assertEquals(10..1023, PhoneControlMath.brightnessRange(sdkInt = 29, configMin = 10, configMax = 1023))
        assertEquals(1..255, PhoneControlMath.brightnessRange(sdkInt = 29, configMin = null, configMax = null))
        // An OEM that kept a 0..2047 setting on Android 11+: the reading widens the range.
        assertEquals(1..2047, PhoneControlMath.brightnessRange(sdkInt = 31, configMin = 1, configMax = 2047, observed = 900))
        assertEquals(1..3000, PhoneControlMath.brightnessRange(sdkInt = 31, configMin = 1, configMax = null, observed = 3000))
        assertEquals(100, PhoneControlMath.brightnessToPercent(2047, 1..2047))
    }

    @Test
    fun `a percent set from the PC comes back within one setting step`() {
        val range = 1..255
        for (p in 0..100) {
            val value = PhoneControlMath.percentToBrightness(p, range)
            val back = PhoneControlMath.brightnessToPercent(value, range)!!
            val next = PhoneControlMath.brightnessToPercent((value + 1).coerceAtMost(255), range)!!
            val prev = PhoneControlMath.brightnessToPercent((value - 1).coerceAtLeast(1), range)!!
            assertTrue("percent $p -> $value -> $back", p in minOf(prev, back)..maxOf(next, back))
        }
    }
}
