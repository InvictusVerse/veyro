package com.veyro.ui.keepalive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veyro.core.network.LocalNetworkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/** The checklist as the screen draws it; empty until the first read. */
public data class KeepConnectedUiState(
    val rows: List<CheckRow> = emptyList(),
    /** A one-shot line for the snackbar; cleared by [KeepConnectedViewModel.messageShown]. */
    val message: String? = null,
)

/**
 * "Keep Veyro connected" (ADR-061). Everything Android-specific (the reads, opening the screens)
 * is a lambda from `:app`, so the decisions stay testable.
 */
public class KeepConnectedViewModel(
    private val readFacts: () -> KeepAliveFacts,
    localNetwork: Flow<LocalNetworkState>,
    /** Opens [KeepAliveTarget]'s screen; false when nothing on this phone could open it. */
    private val open: (KeepAliveTarget) -> Boolean,
) : ViewModel() {

    private var facts: KeepAliveFacts? = null
    private val _state = MutableStateFlow(KeepConnectedUiState())
    public val state: StateFlow<KeepConnectedUiState> = _state.asStateFlow()

    init {
        localNetwork
            .catch { }
            .onEach { network -> facts?.let { publish(it.copy(localNetwork = network)) } }
            .launchIn(viewModelScope)
    }

    /** Re-reads every fact; called when the screen resumes. */
    public fun refresh() {
        publish(readFacts())
    }

    /** A row's button other than local network, which the Activity handles. */
    public fun onAction(target: KeepAliveTarget) {
        if (!open(target)) _state.update { it.copy(message = "This screen is not available on this phone") }
    }

    public fun messageShown() {
        _state.update { it.copy(message = null) }
    }

    private fun publish(next: KeepAliveFacts) {
        facts = next
        _state.update { it.copy(rows = KeepConnectedChecklist.rows(next)) }
    }
}
