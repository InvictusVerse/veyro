package com.veyro.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

    /** Serve recent camera photos and screenshots to a connected PC (ADR-034). On by default. */
    public val photosEnabled: Flow<Boolean>

    public suspend fun setPhotosEnabled(enabled: Boolean)

    /** First-run onboarding finished or skipped (ADR-030). False until then, and for old files. */
    public val onboardingComplete: Flow<Boolean>

    public suspend fun setOnboardingComplete(complete: Boolean)

    /**
     * Keep the session to the paired PC alive in the background (ADR-036). On by default, so it
     * takes effect from the first pairing; it only runs while a PC is paired.
     */
    public val runInBackground: Flow<Boolean> get() = flowOf(true)

    public suspend fun setRunInBackground(enabled: Boolean) {}

    /** Start the background connection after the phone boots (ADR-036). Off by default. */
    public val startOnBoot: Flow<Boolean> get() = flowOf(false)

    public suspend fun setStartOnBoot(enabled: Boolean) {}

    /**
     * Send this phone's wallpaper to a connected PC so it can draw the phone (ADR-038). On by
     * default; reading the wallpaper may still need the photo permission, asked from its row.
     */
    public val wallpaperShareEnabled: Flow<Boolean> get() = flowOf(true)

    public suspend fun setWallpaperShareEnabled(enabled: Boolean) {}
}
