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

    /**
     * The user removed [deviceId] here (ADR-057). Removes it like [remove], but keeps its pairing
     * secret for a while, so the device can be told with an authenticated `unpair` when it next
     * dials in. The secret is used for nothing else. The default keeps nothing.
     */
    public suspend fun removeAndNotify(deviceId: DeviceId) {
        remove(deviceId)
    }

    /** The kept pairing secret of a device removed here and not yet told, or null (ADR-057). */
    public suspend fun pendingUnpairSecret(deviceId: DeviceId): ByteArray? = null

    /** [deviceId] has been told it was removed, or was paired again: drop its kept secret. */
    public suspend fun clearPendingUnpair(deviceId: DeviceId) {}

    /**
     * The device announced [name] for itself (its `hello`, `helloAck` or `deviceInfo`, ADR-059;
     * already validated). Replaces the stored name and keeps a nickname. Returns true when the
     * record changed; false for an unknown or revoked device or the same name.
     */
    public suspend fun setAnnouncedName(deviceId: DeviceId, name: String): Boolean {
        val peer = find(deviceId)?.takeUnless { it.revoked || it.name == name } ?: return false
        put(peer.copy(name = name))
        return true
    }

    /** The user's own name for [deviceId] here (ADR-059); null goes back to the device's own name. */
    public suspend fun setNickname(deviceId: DeviceId, nickname: String?) {
        val peer = find(deviceId) ?: return
        if (peer.nickname != nickname) put(peer.copy(nickname = nickname))
    }
}