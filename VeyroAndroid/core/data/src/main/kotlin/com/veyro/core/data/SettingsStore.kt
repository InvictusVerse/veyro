package com.veyro.core.data

import kotlinx.coroutines.flow.Flow
import java.io.File

/** User-visible settings that are not per-peer trust (REQ-I05, feature:settings). */
public interface SettingsStore {
    public val deviceName: Flow<String>
    public val destinationDir: Flow<File?>
    public val alwaysDiscoverable: Flow<Boolean>

    /** Mirror phone notifications to a connected PC (ADR-025). Off by default. */
    public val notificationMirrorEnabled: Flow<Boolean>

    public suspend fun setDeviceName(name: String)
    public suspend fun setDestinationDir(dir: File?)
    public suspend fun setAlwaysDiscoverable(enabled: Boolean)
    public suspend fun setNotificationMirrorEnabled(enabled: Boolean)

    /** Share this phone's media sessions with a connected PC (ADR-026). Explicit opt-in, off by default. */
    public val mediaShareEnabled: Flow<Boolean>

    /** Let a connected PC ring this phone and change its ringer/DND (ADR-026). Off by default. */
    public val deviceControlAllowed: Flow<Boolean>

    public suspend fun setMediaShareEnabled(enabled: Boolean)
    public suspend fun setDeviceControlAllowed(enabled: Boolean)

    /** Serve SMS and calls to a connected PC (ADR-027). Off by default. */
    public val messagesAndCallsEnabled: Flow<Boolean>

    public suspend fun setMessagesAndCallsEnabled(enabled: Boolean)

    /** Serve recent camera photos and screenshots to a connected PC (ADR-034). Off by default. */
    public val photosEnabled: Flow<Boolean>

    public suspend fun setPhotosEnabled(enabled: Boolean)

    /** First-run onboarding finished or skipped (ADR-030). False until then, and for old files. */
    public val onboardingComplete: Flow<Boolean>

    public suspend fun setOnboardingComplete(complete: Boolean)
}
