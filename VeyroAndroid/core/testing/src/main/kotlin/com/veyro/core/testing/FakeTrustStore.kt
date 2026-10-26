package com.veyro.core.testing

import com.veyro.core.data.TrustStore
import com.veyro.core.model.DeviceId
import com.veyro.core.model.TrustedPeer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory TrustStore for JVM tests. No Android, no filesystem, no crypto. */
public class FakeTrustStore(initial: List<TrustedPeer> = emptyList()) : TrustStore {
    private val state = MutableStateFlow(initial.toList())

    override val peers: Flow<List<TrustedPeer>> = state.asStateFlow()
    override suspend fun snapshot(): List<TrustedPeer> = state.value
    override suspend fun find(deviceId: DeviceId): TrustedPeer? = state.value.firstOrNull { it.deviceId == deviceId }
    override suspend fun put(peer: TrustedPeer) {
        state.value = state.value.filterNot { it.deviceId == peer.deviceId } + peer
    }
    override suspend fun remove(deviceId: DeviceId) {
        state.value = state.value.filterNot { it.deviceId == deviceId }
    }
    override suspend fun setAutoAccept(deviceId: DeviceId, autoAccept: Boolean) {
        state.value = state.value.map { if (it.deviceId == deviceId) it.copy(autoAcceptTransfers = autoAccept) else it }
    }
    override suspend fun markConnected(deviceId: DeviceId, atMillis: Long) {
        state.value = state.value.map { if (it.deviceId == deviceId) it.copy(lastConnectedEpochMillis = atMillis) else it }
    }
}