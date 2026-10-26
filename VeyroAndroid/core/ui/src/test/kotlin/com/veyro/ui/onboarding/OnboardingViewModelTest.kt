package com.veyro.ui.onboarding

import com.veyro.core.data.SettingsStore
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Platform
import com.veyro.core.model.TrustedPeer
import com.veyro.core.model.fingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeSettings(onboardingDone: Boolean = false, name: String = "Pixel 9") : SettingsStore {
        val name = MutableStateFlow(name)
        val done = MutableStateFlow(onboardingDone)
        val notif = MutableStateFlow(false)
        val media = MutableStateFlow(false)
        val control = MutableStateFlow(false)
        val messages = MutableStateFlow(false)
        override val deviceName: Flow<String> = this.name
        override val destinationDir: Flow<File?> = MutableStateFlow(null)
        override val alwaysDiscoverable: Flow<Boolean> = MutableStateFlow(false)
        override val notificationMirrorEnabled: Flow<Boolean> = notif
        override val mediaShareEnabled: Flow<Boolean> = media
        override val deviceControlAllowed: Flow<Boolean> = control
        override val messagesAndCallsEnabled: Flow<Boolean> = messages
        val photos = MutableStateFlow(false)
        override val photosEnabled: Flow<Boolean> = photos
        override suspend fun setPhotosEnabled(enabled: Boolean) { photos.value = enabled }
        override val onboardingComplete: Flow<Boolean> = done
        override suspend fun setDeviceName(name: String) { this.name.value = name }
        override suspend fun setDestinationDir(dir: File?) = Unit
        override suspend fun setAlwaysDiscoverable(enabled: Boolean) = Unit
        override suspend fun setNotificationMirrorEnabled(enabled: Boolean) { notif.value = enabled }
        override suspend fun setMediaShareEnabled(enabled: Boolean) { media.value = enabled }
        override suspend fun setDeviceControlAllowed(enabled: Boolean) { control.value = enabled }
        override suspend fun setMessagesAndCallsEnabled(enabled: Boolean) { messages.value = enabled }
        override suspend fun setOnboardingComplete(complete: Boolean) { done.value = complete }
    }

    private fun peer(seed: Int, name: String = "Office PC", revoked: Boolean = false): TrustedPeer {
        val pk = ByteArray(32) { seed.toByte() }
        return TrustedPeer(
            deviceId = DeviceId.fromPublicKey(pk),
            staticPublicKey = pk,
            pairingSecret = ByteArray(32),
            name = name,
            platform = Platform.WINDOWS,
            pairedAtEpochMillis = 1L,
            revoked = revoked,
        )
    }

    private fun vm(
        settings: FakeSettings = FakeSettings(),
        peers: MutableStateFlow<List<TrustedPeer>> = MutableStateFlow(emptyList()),
        access: (OnboardingFeature) -> FeatureAccess = { FeatureAccess() },
    ) = OnboardingViewModel(settings, peers, access)

    // ── Gate and migration ─────────────────────────────────────────────────────────────────

    @Test
    fun `fresh install requires onboarding and prefills the device name`() {
        val model = vm()
        assertEquals(OnboardingViewModel.Gate.REQUIRED, model.state.value.gate)
        assertEquals(OnboardingStep.WELCOME, model.state.value.step)
        assertEquals("Pixel 9", model.state.value.deviceName)
    }

    @Test
    fun `completed flag skips onboarding`() {
        val model = vm(settings = FakeSettings(onboardingDone = true))
        assertEquals(OnboardingViewModel.Gate.NOT_REQUIRED, model.state.value.gate)
    }

    @Test
    fun `existing install with a trusted peer is migrated to complete`() {
        val settings = FakeSettings()
        val model = vm(settings = settings, peers = MutableStateFlow(listOf(peer(1))))
        assertEquals(OnboardingViewModel.Gate.NOT_REQUIRED, model.state.value.gate)
        assertTrue("migration must persist the flag", settings.done.value)
    }

    @Test
    fun `revoked peers alone do not count as an existing install`() {
        val settings = FakeSettings()
        val model = vm(settings = settings, peers = MutableStateFlow(listOf(peer(1, revoked = true))))
        assertEquals(OnboardingViewModel.Gate.REQUIRED, model.state.value.gate)
        assertFalse(settings.done.value)
    }

    // ── Steps ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `next walks every page and back returns`() {
        val model = vm()
        model.next()
        assertEquals(OnboardingStep.DEVICE_NAME, model.state.value.step)
        model.next()
        assertEquals(OnboardingStep.FEATURES, model.state.value.step)
        model.next()
        assertEquals(OnboardingStep.PAIR, model.state.value.step)
        assertTrue(model.back())
        assertEquals(OnboardingStep.FEATURES, model.state.value.step)
        assertTrue(model.back())
        assertTrue(model.back())
        assertEquals(OnboardingStep.WELCOME, model.state.value.step)
        assertFalse("Back on the first page is left to the Activity", model.back())
    }

    @Test
    fun `leaving the name page saves the edited name and ignores a blank one`() {
        val settings = FakeSettings()
        val model = vm(settings = settings)
        model.next()
        model.setDeviceNameDraft("  Work phone ")
        model.next()
        assertEquals("Work phone", settings.name.value)

        model.back()
        model.setDeviceNameDraft("   ")
        model.next()
        assertEquals("Work phone", settings.name.value)
    }

    @Test
    fun `skip on features goes to pair and skip on pair finishes unpaired`() {
        val model = vm()
        model.next(); model.next()
        assertEquals(OnboardingStep.FEATURES, model.state.value.step)
        model.skip()
        assertEquals(OnboardingStep.PAIR, model.state.value.step)
        model.skip()
        assertEquals(OnboardingStep.DONE, model.state.value.step)
        assertNull(model.state.value.paired)
    }

    @Test
    fun `go to home sets the completion flag`() {
        val settings = FakeSettings()
        val model = vm(settings = settings)
        repeat(3) { model.next() }
        model.skip()
        assertFalse(settings.done.value)
        model.next() // "Go to Home" on Done
        assertTrue(settings.done.value)
        assertTrue(model.state.value.completed)
    }

    @Test
    fun `a new trusted peer on the pair page moves to done with name and fingerprint`() {
        val peers = MutableStateFlow<List<TrustedPeer>>(emptyList())
        val model = vm(peers = peers)
        repeat(3) { model.next() }
        assertEquals(OnboardingStep.PAIR, model.state.value.step)

        val pc = peer(7, name = "Studio PC")
        peers.value = listOf(pc)

        assertEquals(OnboardingStep.DONE, model.state.value.step)
        assertEquals("Studio PC", model.state.value.paired?.name)
        assertEquals(pc.deviceId.fingerprint(), model.state.value.paired?.fingerprint)
    }

    @Test
    fun `a peer paired before the pair page is remembered but does not jump pages`() {
        val peers = MutableStateFlow<List<TrustedPeer>>(emptyList())
        val model = vm(peers = peers)
        model.next()
        peers.value = listOf(peer(8))
        assertEquals(OnboardingStep.DEVICE_NAME, model.state.value.step)
        assertEquals("Office PC", model.state.value.paired?.name)
    }

    // ── Features ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `feature switches persist and are off by default`() {
        val settings = FakeSettings()
        val model = vm(settings = settings)
        // Wallpaper is the one feature on by default (ADR-038); the fake keeps the store default.
        assertTrue(model.state.value.features.filter { it.feature != OnboardingFeature.WALLPAPER }.none { it.enabled })

        model.setFeatureEnabled(OnboardingFeature.NOTIFICATIONS, true)
        model.setFeatureEnabled(OnboardingFeature.MEDIA, true)
        model.setFeatureEnabled(OnboardingFeature.MESSAGES_AND_CALLS, true)
        model.setFeatureEnabled(OnboardingFeature.RING_AND_SOUND, true)
        model.setFeatureEnabled(OnboardingFeature.PHOTOS, true)
        assertTrue(settings.notif.value && settings.media.value && settings.messages.value && settings.control.value)
        assertTrue(settings.photos.value)
        assertTrue(model.state.value.features.all { it.enabled })

        model.setFeatureEnabled(OnboardingFeature.MEDIA, false)
        assertFalse(settings.media.value)
        assertFalse(model.state.value.features.first { it.feature == OnboardingFeature.MEDIA }.enabled)
    }

    @Test
    fun `access state is re-read on refresh`() {
        var policyGranted = false
        val model = vm(access = { feature ->
            if (feature == OnboardingFeature.RING_AND_SOUND) {
                FeatureAccess(specialAccess = SpecialAccess.NOTIFICATION_POLICY, specialGranted = policyGranted)
            } else {
                FeatureAccess()
            }
        })
        fun ring() = model.state.value.features.first { it.feature == OnboardingFeature.RING_AND_SOUND }
        assertFalse(ring().access.granted)
        policyGranted = true
        model.refreshAccess()
        assertTrue(ring().access.granted)
    }

    @Test
    fun `access is granted only when nothing is missing`() {
        assertTrue(FeatureAccess().granted)
        assertFalse(FeatureAccess(missingPermissions = listOf("p")).granted)
        assertFalse(FeatureAccess(specialAccess = SpecialAccess.NOTIFICATION_LISTENER, specialGranted = false).granted)
        assertTrue(FeatureAccess(specialAccess = SpecialAccess.NOTIFICATION_LISTENER, specialGranted = true).granted)
    }
}
