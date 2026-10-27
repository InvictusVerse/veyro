package com.veyro.feature.clipboard

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One item of the phone's virtual clipboard, ready to draw (ADR-043). */
public data class ClipRow(
    val id: String,
    val text: String?,
    val thumbnail: ImageBitmap?,
    /** The PC it came from. */
    val source: String,
    val atMillis: Long,
)

/** What "Send clipboard to PC" did. */
public enum class SendClipOutcome { SENT, EMPTY, TOO_LARGE, NO_PC }

public data class ClipboardUiState(
    val rows: List<ClipRow> = emptyList(),
    val autoFetch: Boolean = true,
    val sending: Boolean = false,
    /** A one-shot confirmation; cleared by [ClipboardViewModel.messageShown]. */
    val message: String? = null,
)

/**
 * The Clipboard screen (ADR-043). Everything it touches is handed in by `:app`, so this module
 * knows nothing about links or the system clipboard and is testable with plain lambdas.
 */
public class ClipboardViewModel(
    rows: Flow<List<ClipRow>>,
    autoFetch: Flow<Boolean>,
    private val setAutoFetch: suspend (Boolean) -> Unit,
    private val copy: (id: String) -> Boolean,
    private val remove: (id: String) -> Unit,
    private val clear: () -> Unit,
    /** Reads the phone's clipboard and sends it; must run while Veyro is in the foreground. */
    private val sendClipboard: suspend () -> SendClipOutcome,
) : ViewModel() {

    private val transient = MutableStateFlow(ClipboardUiState())

    public val state: StateFlow<ClipboardUiState> =
        combine(rows, autoFetch, transient) { list, fetch, t -> t.copy(rows = list, autoFetch = fetch) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClipboardUiState())

    public fun copy(row: ClipRow) {
        val ok = copy(row.id)
        // Android 13+ confirms a copy itself; a second message would only repeat it.
        if (!ok) transient.update { it.copy(message = "Could not copy") }
    }

    public fun remove(row: ClipRow): Unit = remove(row.id)

    public fun clearAll(): Unit = clear()

    public fun setAutoFetchEnabled(enabled: Boolean) {
        viewModelScope.launch { setAutoFetch(enabled) }
    }

    public fun sendClipboardToPc() {
        if (transient.value.sending) return
        transient.update { it.copy(sending = true) }
        viewModelScope.launch {
            val outcome = runCatching { sendClipboard() }.getOrDefault(SendClipOutcome.NO_PC)
            val message = when (outcome) {
                SendClipOutcome.SENT -> "Sent to your PC's clipboard list"
                SendClipOutcome.EMPTY -> "Nothing copied on this phone"
                SendClipOutcome.TOO_LARGE -> "Too large to send"
                SendClipOutcome.NO_PC -> "No PC connected"
            }
            transient.update { it.copy(sending = false, message = message) }
        }
    }

    public fun messageShown() {
        transient.update { it.copy(message = null) }
    }
}
