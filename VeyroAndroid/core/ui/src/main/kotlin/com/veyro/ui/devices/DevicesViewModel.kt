package com.veyro.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veyro.core.data.TrustStore
import com.veyro.core.model.DeviceId
import com.veyro.core.model.DeviceNames
import com.veyro.core.model.TrustedPeer
import com.veyro.core.model.fingerprint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** State for the paired-devices screen. */
public data class DevicesUiState(
    val isLoading: Boolean = true,
    val devices: List<TrustedPeer> = emptyList(),
    /** Set when the trust file failed its HMAC, so the user knows to re-pair (REQ-I09). */
    val loadError: String? = null,
    val renaming: DeviceId? = null,
)

/**
 * Paired-device management: rename, revoke, auto-accept, and the fingerprint.
 *
 * The fingerprint is shown here rather than hidden behind a "details" tap because REQ-U04 is about
 * the user telling two similarly-named devices apart, and the fingerprint is the only thing that
 * actually distinguishes them. Putting it one tap deeper means the user compares names instead,
 * which is exactly the mistake that leads to sending a private file to the wrong machine.
 */
public class DevicesViewModel(
    private val trustStore: TrustStore,
    /** Tells the PC and forgets it (ADR-042). Defaults to a local-only removal for tests. */
    private val unpair: suspend (DeviceId) -> Unit = { trustStore.remove(it) },
) : ViewModel() {

    private val _state = MutableStateFlow(DevicesUiState())
    public val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    init {
        trustStore.peers
            .onEach { peers ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    devices = peers.filterNot { it.revoked },
                )
            }
            .launchIn(viewModelScope)
    }

    /**
     * A rename here is this phone's nickname for the device (ADR-059): the device's own name is
     * still stored and still follows its renames, and the nickname wins on every screen. Typing
     * the device's own name clears the nickname, so it follows the device again.
     */
    public fun rename(deviceId: DeviceId, name: String) {
        val cleaned = DeviceNames.clean(name) ?: return
        viewModelScope.launch {
            val peer = trustStore.find(deviceId) ?: return@launch
            trustStore.setNickname(deviceId, cleaned.takeUnless { it == peer.name })
            _state.value = _state.value.copy(renaming = null)
        }
    }

    /** "Use the device's own name": drops the nickname. */
    public fun useOwnName(deviceId: DeviceId) {
        viewModelScope.launch {
            trustStore.setNickname(deviceId, null)
            _state.value = _state.value.copy(renaming = null)
        }
    }

    public fun beginRename(deviceId: DeviceId) {
        _state.value = _state.value.copy(renaming = deviceId)
    }

    public fun cancelRename() {
        _state.value = _state.value.copy(renaming = null)
    }

    public fun setAutoAccept(deviceId: DeviceId, enabled: Boolean) {
        viewModelScope.launch { trustStore.setAutoAccept(deviceId, enabled) }
    }

    /**
     * Revokes trust.
     *
     * Deletes the record outright rather than flagging it: a revoked entry that lingers in the store
     * is one bug away from being resurrected, and the pairing secret in it is no longer something we
     * want to keep. Re-pairing regenerates everything.
     */
    public fun revoke(deviceId: DeviceId) {
        viewModelScope.launch { runCatching { unpair(deviceId) } }
    }

    /** The 8-byte comparable fingerprint, for display next to the name. */
    public fun fingerprintOf(peer: TrustedPeer): String = peer.deviceId.fingerprint()
}
