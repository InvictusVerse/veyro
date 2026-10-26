package com.veyro.core.data

import com.veyro.core.common.VeyroLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSON-on-disk [SettingsStore].
 *
 * JSON here, unlike the CBOR trust store, and for a reason: these values are human-readable, there
 * are no raw keys, and being able to `cat` the file while debugging a device-name or destination
 * problem is worth more than a few hundred bytes. It is also not authenticated - tampering with the
 * device name is a cosmetic problem, not a security one, so an HMAC would be ceremony without
 * benefit.
 *
 * Writes are atomic for the same reason as the trust store: a kill mid-write must not leave a
 * truncated file that fails to parse on next launch.
 */
public class FileSettingsStore(
    directory: File,
    private val defaultDeviceName: String,
) : SettingsStore {

    private companion object {
        const val TAG = "Settings"
        const val FILE_NAME = "settings.json"
        const val MAX_NAME_BYTES = 32
    }

    @Serializable
    private data class Stored(
        val version: Int = 1,
        val deviceName: String? = null,
        val destinationPath: String? = null,
        val alwaysDiscoverable: Boolean = false,
        // Added after v1 with a default, so an older settings file still decodes.
        val notificationMirrorEnabled: Boolean = false,
        // ADR-026, same rule: defaulted so older files decode, and off until the user opts in.
        val mediaShareEnabled: Boolean = false,
        val deviceControlAllowed: Boolean = false,
        // ADR-027, same rule.
        val messagesAndCallsEnabled: Boolean = false,
        // ADR-034, same rule.
        val photosEnabled: Boolean = false,
        // ADR-030. Defaulted false so a pre-onboarding file decodes; existing installs with a
        // trusted peer are migrated to true by the onboarding gate, not here.
        val onboardingComplete: Boolean = false,
        // ADR-036. Defaulted so older files decode; background running is on unless turned off,
        // start-on-boot is off unless turned on.
        val runInBackground: Boolean = true,
        val startOnBoot: Boolean = false,
        // ADR-038. Defaulted so older files decode; on unless turned off.
        val wallpaperShareEnabled: Boolean = true,
    )

    private val file = File(directory, FILE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val mutex = Mutex()
    private val state = MutableStateFlow(load())

    override val deviceName: Flow<String> =
        state.asStateFlow().map { it.deviceName ?: defaultDeviceName }

    override val destinationDir: Flow<File?> =
        state.asStateFlow().map { stored -> stored.destinationPath?.let(::File) }

    override val alwaysDiscoverable: Flow<Boolean> =
        state.asStateFlow().map { it.alwaysDiscoverable }

    override val notificationMirrorEnabled: Flow<Boolean> =
        state.asStateFlow().map { it.notificationMirrorEnabled }

    override suspend fun setDeviceName(name: String) {
        // The name goes into an mDNS TXT record, which is size-limited (Vlp.MAX_DISPLAY_NAME_BYTES).
        // Truncating here rather than at the advertiser means what the user sees in Settings is what
        // peers will actually see, instead of a silent difference.
        val trimmed = name.trim().ifEmpty { defaultDeviceName }
        val clamped = clampToBytes(trimmed, MAX_NAME_BYTES)
        update { it.copy(deviceName = clamped) }
    }

    override suspend fun setDestinationDir(dir: File?) {
        update { it.copy(destinationPath = dir?.absolutePath) }
    }

    override suspend fun setAlwaysDiscoverable(enabled: Boolean) {
        update { it.copy(alwaysDiscoverable = enabled) }
    }

    override suspend fun setNotificationMirrorEnabled(enabled: Boolean) {
        update { it.copy(notificationMirrorEnabled = enabled) }
    }

    override val mediaShareEnabled: Flow<Boolean> =
        state.asStateFlow().map { it.mediaShareEnabled }

    override val deviceControlAllowed: Flow<Boolean> =
        state.asStateFlow().map { it.deviceControlAllowed }

    override suspend fun setMediaShareEnabled(enabled: Boolean) {
        update { it.copy(mediaShareEnabled = enabled) }
    }

    override suspend fun setDeviceControlAllowed(enabled: Boolean) {
        update { it.copy(deviceControlAllowed = enabled) }
    }

    override val messagesAndCallsEnabled: Flow<Boolean> =
        state.asStateFlow().map { it.messagesAndCallsEnabled }

    override suspend fun setMessagesAndCallsEnabled(enabled: Boolean) {
        update { it.copy(messagesAndCallsEnabled = enabled) }
    }

    override val photosEnabled: Flow<Boolean> =
        state.asStateFlow().map { it.photosEnabled }

    override suspend fun setPhotosEnabled(enabled: Boolean) {
        update { it.copy(photosEnabled = enabled) }
    }

    override val onboardingComplete: Flow<Boolean> =
        state.asStateFlow().map { it.onboardingComplete }

    override suspend fun setOnboardingComplete(complete: Boolean) {
        update { it.copy(onboardingComplete = complete) }
    }

    override val runInBackground: Flow<Boolean> =
        state.asStateFlow().map { it.runInBackground }

    override suspend fun setRunInBackground(enabled: Boolean) {
        update { it.copy(runInBackground = enabled) }
    }

    override val startOnBoot: Flow<Boolean> =
        state.asStateFlow().map { it.startOnBoot }

    override suspend fun setStartOnBoot(enabled: Boolean) {
        update { it.copy(startOnBoot = enabled) }
    }

    override val wallpaperShareEnabled: Flow<Boolean> =
        state.asStateFlow().map { it.wallpaperShareEnabled }

    override suspend fun setWallpaperShareEnabled(enabled: Boolean) {
        update { it.copy(wallpaperShareEnabled = enabled) }
    }

    private suspend fun update(transform: (Stored) -> Stored) = mutex.withLock {
        val next = transform(state.value)
        persist(next)
        state.value = next
    }

    private fun load(): Stored {
        if (!file.exists()) return Stored()
        return try {
            json.decodeFromString<Stored>(file.readText())
        } catch (t: Throwable) {
            // Settings are not security-relevant, so defaults are a safe recovery. Logged rather
            // than silent, because repeatedly losing settings is a bug worth noticing.
            VeyroLog.w(TAG, t) { "Settings file unreadable; falling back to defaults" }
            Stored()
        }
    }

    private fun persist(stored: Stored) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(stored))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                VeyroLog.w(TAG) { "Could not persist settings" }
            }
        }
    }

    /**
     * Truncates to a UTF-8 byte budget without splitting a character.
     *
     * A naive `take(n)` counts UTF-16 chars, so an emoji or CJK name would still overflow the TXT
     * record; a naive byte cut would produce invalid UTF-8.
     */
    private fun clampToBytes(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        var end = value.length
        while (end > 0) {
            val candidate = value.substring(0, end)
            if (candidate.toByteArray(Charsets.UTF_8).size <= maxBytes) return candidate
            end--
        }
        return ""
    }
}
