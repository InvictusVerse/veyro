package com.veyro.core.data

import com.veyro.core.model.DeviceId
import com.veyro.core.model.TrustedPeer
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for paired peers (REQ-I08, REQ-I09).
 *
 * Stored as CBOR in app-private files with an atomic write-temp-then-rename and an HMAC so that
 * an attacker with filesystem access cannot silently insert a peer (05-SECURITY section 4.4).
 * No Room, no DataStore - the dataset is tiny and an interface keeps the storage replaceable
 * (ADR-006).
 */
public interface TrustStore {
    public val peers: Flow<List<TrustedPeer>>
    public suspend fun snapshot(): List<TrustedPeer>
    public suspend fun find(deviceId: DeviceId): TrustedPeer?
    public suspend fun put(peer: TrustedPeer)
    public suspend fun remove(deviceId: DeviceId)
    public suspend fun setAutoAccept(deviceId: DeviceId, autoAccept: Boolean)
    public suspend fun markConnected(deviceId: DeviceId, atMillis: Long = System.currentTimeMillis())
}