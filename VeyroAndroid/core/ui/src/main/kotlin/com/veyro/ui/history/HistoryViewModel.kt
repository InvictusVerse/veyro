package com.veyro.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veyro.core.data.TransferHistoryStore
import com.veyro.core.model.Transfer
import com.veyro.core.model.TransferState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

import com.veyro.core.model.TransferDirection

public enum class HistoryFilter {
    ALL,
    SENT,
    RECEIVED,
    VERIFIED,
}

public data class HistoryUiState(
    val isLoading: Boolean = true,
    val transfers: List<Transfer> = emptyList(),
    val filter: HistoryFilter = HistoryFilter.ALL,
    val searchQuery: String = "",
) {
    public val filteredTransfers: List<Transfer>
        get() = transfers.filter { transfer ->
            val matchesFilter = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.SENT -> transfer.direction == TransferDirection.SEND
                HistoryFilter.RECEIVED -> transfer.direction == TransferDirection.RECEIVE
                HistoryFilter.VERIFIED -> transfer.state == TransferState.COMPLETED
            }
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                val q = searchQuery.trim().lowercase()
                transfer.peerDisplayName?.lowercase()?.contains(q) == true ||
                    transfer.items.any { it.fileName.lowercase().contains(q) } ||
                    transfer.id.lowercase().contains(q)
            }
            matchesFilter && matchesSearch
        }
}

/**
 * Past transfers.
 *
 * Shows the **verification result**, not just "done". REQ-T04 says a file that fails its hash check
 * is never published; the history is where the user finds out that happened, and "completed with
 * errors" is a materially different outcome from "completed" that they need to be able to see after
 * the fact.
 */
public class HistoryViewModel(
    private val store: TransferHistoryStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    public val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        store.history
            .onEach { transfers ->
                _state.value = _state.value.copy(isLoading = false, transfers = transfers)
            }
            .launchIn(viewModelScope)
    }

    public fun setFilter(filter: HistoryFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    public fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    public fun clear() {
        viewModelScope.launch { store.clear() }
    }

    /** A short, honest description of how a transfer ended. */
    public fun describe(transfer: Transfer): String = when (transfer.state) {
        TransferState.COMPLETED -> "Completed"
        TransferState.COMPLETED_WITH_ERRORS -> "Completed, some files failed verification"
        TransferState.DECLINED -> "Declined"
        TransferState.CANCELLED -> "Cancelled"
        TransferState.FAILED -> "Failed"
        TransferState.INTERRUPTED -> "Interrupted, can be resumed"
        TransferState.PENDING_AUTHORIZATION -> "Waiting for a decision"
        TransferState.CONNECTING -> "Connecting"
        TransferState.TRANSFERRING -> "In progress"
    }

    public fun isProblem(transfer: Transfer): Boolean = when (transfer.state) {
        TransferState.COMPLETED_WITH_ERRORS,
        TransferState.FAILED,
        -> true
        else -> false
    }
}
