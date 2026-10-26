package com.veyro.ui.components

import com.veyro.core.model.ActiveTransfer
import com.veyro.core.model.TransferStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the transfer banner shows, as a small state machine (ADR-038 point 4):
 *
 *  - a running transfer is shown, updated in place (same id, so the composable never restarts);
 *  - a finished transfer (completed, or cancelled/declined by someone's choice) stays for
 *    [autoDismissMillis], then slides out and the coordinator's record is cleared;
 *  - a failed transfer stays until the user dismisses it;
 *  - [dismiss] hides the banner at once. For a transfer still running that only hides it: the
 *    transfer continues, and the banner comes back only if it then fails.
 *
 * Pure Kotlin on a caller-supplied scope, so the timer runs on a test dispatcher in tests.
 */
public class TransferBannerModel(
    private val scope: CoroutineScope,
    transfers: Flow<ActiveTransfer?>,
    /** Clears the coordinator's record of [transferId] once its banner is gone. */
    private val clear: (transferId: String) -> Unit,
    private val autoDismissMillis: Long = AUTO_DISMISS_MS,
) {

    public companion object {
        public const val AUTO_DISMISS_MS: Long = 3_000L

        /** A terminal stage that is not a failure: it leaves on its own. */
        public fun leavesByItself(stage: TransferStage): Boolean =
            stage == TransferStage.COMPLETED || stage == TransferStage.CANCELLED
    }

    private val _shown = MutableStateFlow<ActiveTransfer?>(null)

    /** The transfer the banner shows, or null when it is hidden. */
    public val shown: StateFlow<ActiveTransfer?> = _shown.asStateFlow()

    private var hiddenId: String? = null
    private var timer: Job? = null
    private var timerFor: String? = null

    init {
        scope.launch { transfers.collect(::onTransfer) }
    }

    /** The close button or a swipe. */
    public fun dismiss() {
        val current = _shown.value ?: return
        cancelTimer()
        _shown.value = null
        if (current.isTerminal) {
            clear(current.transferId)
        } else {
            hiddenId = current.transferId
        }
    }

    private fun onTransfer(transfer: ActiveTransfer?) {
        if (transfer == null) {
            cancelTimer()
            _shown.value = null
            return
        }
        if (transfer.transferId != hiddenId) hiddenId = null

        if (hiddenId != null) {
            when {
                transfer.stage == TransferStage.FAILED -> hiddenId = null // a failure is never silent
                transfer.isTerminal -> {
                    clear(transfer.transferId)
                    return
                }
                else -> return
            }
        }

        if (timerFor != null && timerFor != transfer.transferId) cancelTimer()
        _shown.value = transfer

        if (transfer.isTerminal && leavesByItself(transfer.stage)) {
            if (timerFor != transfer.transferId) startTimer(transfer.transferId)
        } else {
            cancelTimer()
        }
    }

    private fun startTimer(transferId: String) {
        cancelTimer()
        timerFor = transferId
        timer = scope.launch {
            delay(autoDismissMillis)
            if (_shown.value?.transferId == transferId) {
                _shown.value = null
                clear(transferId)
            }
            timerFor = null
        }
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
        timerFor = null
    }
}
