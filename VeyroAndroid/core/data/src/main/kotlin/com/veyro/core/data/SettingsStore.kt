package com.veyro.core.data

import kotlinx.coroutines.flow.Flow
import java.io.File

/** User-visible settings that are not per-peer trust (REQ-I05, feature:settings). */
public interface SettingsStore {
    public val deviceName: Flow<String>
    public val destinationDir: Flow<File?>
    public val alwaysDiscoverable: Flow<Boolean>

    public suspend fun setDeviceName(name: String)
    public suspend fun setDestinationDir(dir: File?)
    public suspend fun setAlwaysDiscoverable(enabled: Boolean)
}