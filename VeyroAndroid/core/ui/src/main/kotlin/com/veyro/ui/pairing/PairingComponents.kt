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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.components.VeyroChip
import com.veyro.core.designsystem.components.VeyroOutlineButton
import com.veyro.core.designsystem.components.VeyroTextButton
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanContainer
import com.veyro.core.designsystem.theme.VeyroPinStyle
import com.veyro.core.designsystem.theme.VeyroRed
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTealContainer
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.designsystem.theme.VeyroViolet
import com.veyro.core.model.Platform
import com.veyro.ui.R

/*
 * Pairing building blocks shared by the Pairing screen and the onboarding Pair page (ADR-030).
 */

/** Launches the Play services scanner through the ViewModel, so rotation keeps the result. */
@Composable
internal fun rememberQrScanAction(viewModel: PairingViewModel): () -> Unit {
    val context = LocalContext.current
    return { viewModel.scanQr { QrScanner.scan(context) } }
}

/** The primary action: one large scan button, the "open Veyro on the PC" line, code fallback. */
@Composable
internal fun ScanQrCard(
    busy: Boolean,
    onScan: () -> Unit,
    onEnterCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VeyroCard(modifier = modifier, glow = true) {
        Text(
            text = "Open Veyro on your PC first and go to its pairing screen. It shows a QR code.",
            style = MaterialTheme.typography.bodyMedium,
            color = VeyroTextSecondary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onScan,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = VeyroCyan),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_veyro_qr_scan),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Scan QR code on your PC",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        VeyroTextButton(
            text = "No camera? Pick it from the nearby list",
            onClick = onEnterCode,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/** Explains the nearby-list path; shown after "Pick it from the nearby list" or a scanner failure. */
@Composable
internal fun CodeEntryHint(modifier: Modifier = Modifier) {
    VeyroCard(modifier = modifier) {
        Text(
            text = "Tap your PC in the list below. Both screens then show the same 6-digit code: " +
                "check that they match and click Accept on the PC. Nothing to type.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** One status card for the whole scan-to-pair attempt. */
@Composable
internal fun QrStatusCard(
    state: QrPairingState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == QrPairingState.Idle) return
    VeyroCard(modifier = modifier, glow = state !is QrPairingState.Failed) {
        when (state) {
            is QrPairingState.Connecting -> ProgressRow(
                title = "Connecting${state.pcName?.let { " to $it" } ?: ""}...",
                subtitle = "Reaching your PC on this network.",
            )
            is QrPairingState.Securing -> ProgressRow(
                title = "Securing the connection...",
                subtitle = "Exchanging keys with your PC using the scanned code.",
            )
            is QrPairingState.Paired -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = VeyroTeal)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Paired with ${state.pcName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Fingerprint ${state.fingerprint}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VeyroTextSecondary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                VeyroTextButton(text = "OK", onClick = onDismiss, modifier = Modifier.align(Alignment.End))
            }
            is QrPairingState.Failed -> {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = VeyroRed)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.error.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.error.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = VeyroTextSecondary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    VeyroTextButton(text = "Dismiss", onClick = onDismiss, color = VeyroTextSecondary)
                    if (state.error != QrPairingError.SCANNER_UNAVAILABLE) {
                        VeyroTextButton(text = "Scan again", onClick = onRetry)
                    }
                }
            }
            QrPairingState.Idle -> Unit
        }
    }
}

@Composable
private fun ProgressRow(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = VeyroCyan, strokeWidth = 3.dp)
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = VeyroTextSecondary)
        }
    }
}

/** A nearby device: platform icon, name, and either an "Already paired" badge or Pair. */
@Composable
internal fun CandidateCard(
    candidate: PairingViewModel.Candidate,
    onPair: () -> Unit,
    enabled: Boolean = true,
) {
    val platformLabel = when (candidate.platform) {
        Platform.WINDOWS -> "Windows PC"
        Platform.ANDROID -> "Android device"
        Platform.UNKNOWN -> "Device"
    }
    VeyroCard(selected = candidate.isAlreadyPaired) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (candidate.isAlreadyPaired) VeyroTealContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (candidate.platform == Platform.ANDROID) R.drawable.ic_veyro_smartphone
                        else R.drawable.ic_veyro_desktop,
                    ),
                    contentDescription = platformLabel,
                    tint = if (candidate.isAlreadyPaired) VeyroTeal else VeyroTextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = platformLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = VeyroTextSecondary,
                )
            }

            if (candidate.isAlreadyPaired) {
                VeyroChip(text = "Already paired", color = VeyroTeal)
            } else if (candidate.endpoints.isNotEmpty()) {
                VeyroOutlineButton(
                    text = "Pair",
                    onClick = onPair,
                    enabled = enabled,
                    height = 36.dp,
                )
            }
        }
    }
}

/** Placeholder while discovery has found nothing yet. */
@Composable
internal fun SearchingCard(modifier: Modifier = Modifier) {
    VeyroCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(VeyroCyanContainer),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = VeyroCyan, strokeWidth = 2.dp)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text("Looking for nearby devices...", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Both devices need to be on the same Wi-Fi, or one on the other's hotspot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VeyroTextSecondary,
                )
            }
        }
    }
}

/** Six digits, large and grouped `123 456`, for reading across a room. */
@Composable
internal fun GroupedPin(pin: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val grouped = if (pin.length == 6) pin.substring(0, 3) + " " + pin.substring(3) else pin
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.5.dp, VeyroViolet.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .padding(vertical = 20.dp)
            .semantics { contentDescription = "Pairing code ${pin.toCharArray().joinToString(" ")}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = grouped,
            style = VeyroPinStyle.copy(fontSize = 44.sp, letterSpacing = 4.sp),
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}
