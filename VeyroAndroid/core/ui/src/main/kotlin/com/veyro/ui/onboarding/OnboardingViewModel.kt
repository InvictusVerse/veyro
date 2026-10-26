package com.veyro.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veyro.core.data.SettingsStore
import com.veyro.core.model.TrustedPeer
import com.veyro.core.model.fingerprint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** The onboarding pages, in order (ADR-030). */
public enum class OnboardingStep { WELCOME, DEVICE_NAME, FEATURES, PAIR, DONE }

/** The optional companion features offered on the Features page. */
public enum class OnboardingFeature { NOTIFICATIONS, MEDIA, MESSAGES_AND_CALLS, RING_AND_SOUND }

/** A system screen a feature needs the user to visit (not a runtime permission dialog). */
public enum class SpecialAccess { NOTIFICATION_LISTENER, NOTIFICATION_POLICY }

/**
 * What a feature still needs from the system, read fresh on every resume.
 *
 * [missingPermissions] are manifest names for a runtime request; [missingLabels] are what the
 * card shows. [specialAccess] is the settings screen, if any, and [specialGranted] its state.
 */
public data class FeatureAccess(
    val missingPermissions: List<String> = emptyList(),
    val missingLabels: List<String> = emptyList(),
    val specialAccess: SpecialAccess? = null,
    val specialGranted: Boolean = true,
) {
    val granted: Boolean get() = missingPermissions.isEmpty() && (specialAccess == null || specialGranted)
}

public data class FeatureState(
    val feature: OnboardingFeature,
    val enabled: Boolean = false,
    val access: FeatureAccess = FeatureAccess(),
)

/** The PC paired during onboarding, for the Done page. */
public data class PairedSummary(val name: String, val fingerprint: String)

/**
 * First-run onboarding state (ADR-030).
 *
 * [Gate] decides once, at construction, whether onboarding is shown at all: it is shown only when
 * the `onboardingComplete` flag is false **and** there is no trusted peer. An existing install
 * that already paired something is migrated by writing the flag, so a later "forget all devices"
 * does not bring onboarding back.
 *
 * Pairing itself runs through the regular `PairingViewModel`; this class only notices a new
 * trusted peer appearing in [trustedPeers] (whichever path created it) and moves to Done. The
 * trust store is the signal, rather than connection events, because it is the one record that
 * only a completed pairing writes.
 */
public class OnboardingViewModel(
    private val settingsStore: SettingsStore,
    private val trustedPeers: Flow<List<TrustedPeer>>,
    /** Reads what [OnboardingFeature] still needs from the system. Called on every resume. */
    private val featureAccess: (OnboardingFeature) -> FeatureAccess,
) : ViewModel() {

    public enum class Gate { LOADING, REQUIRED, NOT_REQUIRED }

    public data class UiState(
        val gate: Gate = Gate.LOADING,
        val step: OnboardingStep = OnboardingStep.WELCOME,
        /** The editable draft; persisted when leaving the name page. */
        val deviceName: String = "",
        val features: List<FeatureState> = OnboardingFeature.entries.map { FeatureState(it) },
        val paired: PairedSummary? = null,
        /** Set by [finish]; the host swaps to the main UI. */
        val completed: Boolean = false,
    ) {
        val stepIndex: Int get() = step.ordinal
        val stepCount: Int get() = OnboardingStep.entries.size
        val canGoBack: Boolean get() = step != OnboardingStep.WELCOME
    }

    private val _state = MutableStateFlow(UiState())
    public val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { decideGate() }
    }

    private suspend fun decideGate() {
        val complete = settingsStore.onboardingComplete.first()
        val peers = currentPeers()
        if (complete) {
            update { it.copy(gate = Gate.NOT_REQUIRED) }
            return
        }
        if (peers.isNotEmpty()) {
            // Existing install from before onboarding existed: it is already set up.
            settingsStore.setOnboardingComplete(true)
            update { it.copy(gate = Gate.NOT_REQUIRED) }
            return
        }
        update {
            it.copy(
                gate = Gate.REQUIRED,
                deviceName = settingsStore.deviceName.first(),
                features = readFeatures(),
            )
        }
        observeFeatureSwitches()
        observeNewPeers(known = peers.map { it.deviceId.value }.toSet())
    }

    private suspend fun currentPeers(): List<TrustedPeer> =
        runCatching { trustedPeers.first() }.getOrDefault(emptyList()).filterNot { it.revoked }

    private fun observeFeatureSwitches() {
        settingsStore.notificationMirrorEnabled
            .onEach { on -> setEnabledInState(OnboardingFeature.NOTIFICATIONS, on) }
            .launchIn(viewModelScope)
        settingsStore.mediaShareEnabled
            .onEach { on -> setEnabledInState(OnboardingFeature.MEDIA, on) }
            .launchIn(viewModelScope)
        settingsStore.messagesAndCallsEnabled
            .onEach { on -> setEnabledInState(OnboardingFeature.MESSAGES_AND_CALLS, on) }
            .launchIn(viewModelScope)
        settingsStore.deviceControlAllowed
            .onEach { on -> setEnabledInState(OnboardingFeature.RING_AND_SOUND, on) }
            .launchIn(viewModelScope)
    }

    private fun observeNewPeers(known: Set<String>) {
        trustedPeers
            .catch { emit(emptyList()) }
            .onEach { peers ->
                val fresh = peers.lastOrNull { !it.revoked && it.deviceId.value !in known } ?: return@onEach
                if (_state.value.paired?.fingerprint == fresh.deviceId.fingerprint()) return@onEach
                update {
                    it.copy(
                        paired = PairedSummary(fresh.name, fresh.deviceId.fingerprint()),
                        step = if (it.step == OnboardingStep.PAIR) OnboardingStep.DONE else it.step,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    // ── Navigation ─────────────────────────────────────────────────────────────────────────

    /** Primary button. Leaving the name page saves the name; on Done it finishes. */
    public fun next() {
        val current = _state.value
        when (current.step) {
            OnboardingStep.DEVICE_NAME -> {
                val name = current.deviceName.trim()
                if (name.isNotEmpty()) viewModelScope.launch { settingsStore.setDeviceName(name) }
                goTo(OnboardingStep.FEATURES)
            }
            OnboardingStep.DONE -> finish()
            else -> goTo(OnboardingStep.entries[current.step.ordinal + 1])
        }
    }

    /** "Skip" on the optional pages: Features skips to Pair, Pair skips to Done unpaired. */
    public fun skip() {
        when (_state.value.step) {
            OnboardingStep.FEATURES -> goTo(OnboardingStep.PAIR)
            OnboardingStep.PAIR -> goTo(OnboardingStep.DONE)
            else -> next()
        }
    }

    /** System Back. Returns false on the first page so the host can let the Activity handle it. */
    public fun back(): Boolean {
        val step = _state.value.step
        if (step == OnboardingStep.WELCOME) return false
        goTo(OnboardingStep.entries[step.ordinal - 1])
        return true
    }

    /** Marks onboarding complete. Called from Done ("Go to Home"). */
    public fun finish() {
        viewModelScope.launch {
            settingsStore.setOnboardingComplete(true)
            update { it.copy(completed = true) }
        }
    }

    private fun goTo(step: OnboardingStep) {
        update { it.copy(step = step) }
        if (step == OnboardingStep.FEATURES) refreshAccess()
    }

    // ── Pages ──────────────────────────────────────────────────────────────────────────────

    public fun setDeviceNameDraft(name: String) {
        update { it.copy(deviceName = name) }
    }

    /**
     * Stores the switch. The screen requests exactly what [FeatureState.access] says is missing,
     * at the moment the switch is turned on; nothing is requested for a switch left off.
     */
    public fun setFeatureEnabled(feature: OnboardingFeature, enabled: Boolean) {
        setEnabledInState(feature, enabled)
        viewModelScope.launch {
            when (feature) {
                OnboardingFeature.NOTIFICATIONS -> settingsStore.setNotificationMirrorEnabled(enabled)
                OnboardingFeature.MEDIA -> settingsStore.setMediaShareEnabled(enabled)
                OnboardingFeature.MESSAGES_AND_CALLS -> settingsStore.setMessagesAndCallsEnabled(enabled)
                OnboardingFeature.RING_AND_SOUND -> settingsStore.setDeviceControlAllowed(enabled)
            }
        }
    }

    /** Grants happen in system UI, outside the app, so they are re-read on resume. */
    public fun refreshAccess() {
        update { state ->
            state.copy(features = state.features.map { it.copy(access = featureAccess(it.feature)) })
        }
    }

    private fun readFeatures(): List<FeatureState> =
        _state.value.features.map { it.copy(access = featureAccess(it.feature)) }

    private fun setEnabledInState(feature: OnboardingFeature, enabled: Boolean) {
        update { state ->
            state.copy(features = state.features.map { if (it.feature == feature) it.copy(enabled = enabled) else it })
        }
    }

    private inline fun update(transform: (UiState) -> UiState) {
        _state.value = transform(_state.value)
    }
}
