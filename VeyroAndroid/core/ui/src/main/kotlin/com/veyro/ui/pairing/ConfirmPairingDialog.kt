package com.veyro.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.veyro.core.designsystem.components.VeyroButton
import com.veyro.core.designsystem.components.VeyroOutlineButton
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanMuted
import com.veyro.core.designsystem.theme.VeyroRed
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.designsystem.theme.VeyroViolet
import com.veyro.core.designsystem.theme.VeyroVioletContainer
import com.veyro.core.designsystem.theme.VeyroVioletGlow
import com.veyro.ui.R
import com.veyro.ui.components.GlassmorphicDialogSurface
import kotlinx.coroutines.delay

/**
 * Pairing started from the nearby list (ADR-031): connecting, then the code both screens show,
 * then the outcome. Nothing is typed; the user accepts on the other device.
 *
 * Shared by the Pairing screen and the onboarding Pair page. Renders nothing while [state] is
 * [ConfirmPairingState.Idle].
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

    Dialog(
        onDismissRequest = { if (busy) onCancel() else onDismiss() },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        GlassmorphicDialogSurface {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(min = 300.dp, max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state) {
                    is ConfirmPairingState.Connecting -> Working(
                        peerName = state.peerName,
                        onPc = state.onPc,
                        line = "Connecting to ${state.peerName}...",
                        onCancel = onCancel,
                    )
                    is ConfirmPairingState.Securing -> Working(
                        peerName = state.peerName,
                        onPc = state.onPc,
                        line = "Securing the connection...",
                        onCancel = onCancel,
                    )
                    is ConfirmPairingState.AwaitingAcceptance -> Awaiting(state, onCancel)
                    is ConfirmPairingState.Paired -> Outcome(
                        success = true,
                        title = "Paired with ${state.peerName}",
                        body = "Fingerprint ${state.fingerprint}",
                        monospaceBody = true,
                        primary = "Done" to onDismiss,
                        secondary = null,
                    )
                    is ConfirmPairingState.Failed -> Outcome(
                        success = false,
                        title = state.error.title,
                        body = state.error.hint,
                        monospaceBody = false,
                        primary = "Try again" to onRetry,
                        secondary = "Close" to onDismiss,
                    )
                    ConfirmPairingState.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun PeerHeader(peerName: String, onPc: Boolean) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(VeyroVioletContainer)
            .border(1.5.dp, VeyroViolet.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (onPc) R.drawable.ic_veyro_desktop else R.drawable.ic_veyro_smartphone),
            contentDescription = null,
            tint = VeyroViolet,
            modifier = Modifier.size(32.dp),
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Pair with $peerName",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Working(peerName: String, onPc: Boolean, line: String, onCancel: () -> Unit) {
    PeerHeader(peerName, onPc)
    Spacer(modifier = Modifier.height(24.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = VeyroCyan, strokeWidth = 3.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = line, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(modifier = Modifier.height(24.dp))
    VeyroOutlineButton(text = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Awaiting(state: ConfirmPairingState.AwaitingAcceptance, onCancel: () -> Unit) {
    PeerHeader(state.peerName, state.onPc)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Your pairing code",
        style = MaterialTheme.typography.labelLarge,
        color = VeyroTextSecondary,
    )
    Spacer(modifier = Modifier.height(12.dp))
    GroupedPin(pin = state.code, color = VeyroVioletGlow)
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = if (state.onPc) {
            "Check that your PC shows the same code and click Accept there."
        } else {
            "Check that ${state.peerName} shows the same code and tap Accept there."
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "If the codes differ, cancel: someone may be in between.",
        style = MaterialTheme.typography.bodySmall,
        color = VeyroTextSecondary,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))

    var secondsLeft by remember(state.expiresAtMillis) {
        mutableLongStateOf(((state.expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
    }
    LaunchedEffect(state.expiresAtMillis) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft = ((state.expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = VeyroCyan, strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Waiting for Accept ($secondsLeft s)",
            style = MaterialTheme.typography.labelMedium,
            color = if (secondsLeft <= 10) VeyroCyanMuted else VeyroTextSecondary,
        )
    }
    Spacer(modifier = Modifier.height(20.dp))
    VeyroOutlineButton(text = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Outcome(
    success: Boolean,
    title: String,
    body: String,
    monospaceBody: Boolean,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>?,
) {
    Icon(
        imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.Warning,
        contentDescription = null,
        tint = if (success) VeyroTeal else VeyroRed,
        modifier = Modifier.size(48.dp),
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = if (monospaceBody) FontFamily.Monospace else null,
        color = VeyroTextSecondary,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (secondary != null) {
            VeyroOutlineButton(text = secondary.first, onClick = secondary.second, modifier = Modifier.weight(1f))
        }
        VeyroButton(text = primary.first, onClick = primary.second, modifier = Modifier.weight(1f))
    }
}
