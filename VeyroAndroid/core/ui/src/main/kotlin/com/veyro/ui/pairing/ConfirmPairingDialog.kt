package com.veyro.ui.pairing

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons

/** How long the PC waits for Accept (04-PROTOCOL.md 4.3); drives the countdown bar. */
private const val CONFIRM_WINDOW_MS = 120_000L

/**
 * Pairing started from the phone (ADR-031): connecting, the code both screens show, the
 * outcome. Nothing is typed; the user accepts on the PC. Rebuilt on the app theme after the
 * hardware test (see [PairingPromptDialog]). Shared by the Pairing screen and onboarding.
 */
@Composable
internal fun ConfirmPairingDialog(
    state: ConfirmPairingState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    if (state == ConfirmPairingState.Idle) return
    val busy = state is ConfirmPairingState.Connecting ||
        state is ConfirmPairingState.Securing ||
        state is ConfirmPairingState.AwaitingAcceptance

    PairingPromptDialog(onDismissRequest = { if (busy) onCancel() else onDismiss() }) {
        when (state) {
            is ConfirmPairingState.Connecting -> Working(state.peerName, "Connecting...", onCancel)
            is ConfirmPairingState.Securing -> Working(state.peerName, "Securing the connection...", onCancel)
            is ConfirmPairingState.AwaitingAcceptance -> {
                DevicePairHeader()
                PromptTitle("Pair with ${state.peerName}")
                PromptBody(
                    if (state.onPc) "Check that your PC shows this code, then click Accept on the PC."
                    else "Check that ${state.peerName} shows this code, then tap Accept there.",
                )
                PairingCodeTiles(state.code, modifier = Modifier.padding(top = 24.dp))
                PromptBody("Different code? Cancel: someone may be in between.")
                ExpiryBar(state.expiresAtMillis, CONFIRM_WINDOW_MS, modifier = Modifier.padding(top = 20.dp))
                PromptActions(primary = null, secondary = "Cancel" to onCancel)
            }
            is ConfirmPairingState.Paired -> {
                OutcomeIcon(success = true)
                PromptTitle("Paired with ${state.peerName}")
                Text(
                    text = "Fingerprint ${state.fingerprint}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                PromptActions(primary = "Done" to onDismiss, secondary = null)
            }
            is ConfirmPairingState.Failed -> {
                OutcomeIcon(success = false)
                PromptTitle(state.error.title)
                PromptBody(state.error.hint)
                PromptActions(primary = "Try again" to onRetry, secondary = "Close" to onDismiss)
            }
            ConfirmPairingState.Idle -> Unit
        }
    }
}

@Composable
private fun Working(peerName: String, line: String, onCancel: () -> Unit) {
    DevicePairHeader()
    PromptTitle("Pair with $peerName")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 20.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium)
    }
    PromptActions(primary = null, secondary = "Cancel" to onCancel)
}

@Composable
private fun OutcomeIcon(success: Boolean) {
    Icon(
        imageVector = if (success) VeyroIcons.Check else VeyroIcons.Warning,
        contentDescription = null,
        tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = Modifier.size(48.dp),
    )
}
