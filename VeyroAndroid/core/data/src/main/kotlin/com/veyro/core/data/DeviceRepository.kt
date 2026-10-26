package com.veyro.core.data

import com.veyro.core.model.PeerDevice
import kotlinx.coroutines.flow.Flow

/**
 * The single stream of devices the UI collects.
 *
 * Feature modules never talk to `DiscoveryManager` or `TrustStore` directly
 * (ProjectInfo/03-ARCHITECTURE.md): they collect this. That keeps the windowing rules - when
 * discovery may run at all - in one place instead of spread across screens, which is what makes
 * the NFR-02 "zero packets when idle" promise auditable.
 */
public interface DeviceRepository {

    /** Nearby and trusted devices, merged and deduplicated. */
    public val devices: Flow<List<PeerDevice>>

    /**
     * Opens a discovery window for as long as the returned handle is held.
     *
     * A resource rather than a flag, so a screen can scope it with `use {}` and forgetting to
     * release it is visible rather than a silent battery cost.
     */
    public fun observeNearby(forPairing: Boolean = false): DiscoverySubscription
}

/** Release to stop discovery. Idempotent. */
public interface DiscoverySubscription : AutoCloseable {
    override fun close()
}