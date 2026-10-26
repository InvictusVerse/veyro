package com.veyro.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroButton
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.components.VeyroOutlineButton
import com.veyro.core.designsystem.components.VeyroSectionHeader
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroRed
import com.veyro.core.designsystem.theme.VeyroTeal

/**
 * Pairing screen (Devices > Pair new device).
 *
 * Scan-to-pair is the primary path (ADR-030): one large button at the top. Below it the live
 * nearby list: tapping a device shows the same 6-digit code on both screens and the user accepts
 * on the other device (ADR-031). Discovery starts when the screen opens so the list is live.
 */
@Composable
public fun PairingScreen(
    viewModel: PairingViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scan = rememberQrScanAction(viewModel)

    LaunchedEffect(Unit) { viewModel.startSearching() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Pair a device",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        item {
            ScanQrCard(
                busy = state.pairingBusy,
                onScan = scan,
                onEnterCode = viewModel::showCodeEntry,
            )
        }

        if (state.qr != QrPairingState.Idle) {
            item {
                QrStatusCard(
                    state = state.qr,
                    onDismiss = viewModel::dismissQrStatus,
                    onRetry = scan,
                )
            }
        }

        if (state.showCodeEntryHint) {
            item { CodeEntryHint() }
        }

        state.status?.let { msg ->
            item { StatusCard(message = msg, isError = false, onDismiss = viewModel::clearStatus) }
        }
        state.error?.let { msg ->
            item { StatusCard(message = msg, isError = true, onDismiss = viewModel::clearStatus) }
        }

        item {
            NearbyHeader(
                isSearching = state.isSearching,
                secondsRemaining = state.secondsRemaining,
                onFindDevices = viewModel::startSearching,
                onStop = viewModel::stopSearching,
            )
        }

        if (state.isSearching) {
            if (state.candidates.isNotEmpty()) {
                items(state.candidates, key = { it.deviceId.value }) { candidate ->
                    CandidateCard(
                        candidate = candidate,
                        onPair = { viewModel.pairWith(candidate) },
                        enabled = !state.pairingBusy,
                    )
                }
            } else {
                item { SearchingCard() }
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    ConfirmPairingDialog(
        state = state.confirm,
        onCancel = viewModel::cancelConfirm,
        onDismiss = viewModel::dismissConfirm,
        onRetry = viewModel::retryConfirm,
    )
}

@Composable
private fun NearbyHeader(
    isSearching: Boolean,
    secondsRemaining: Int,
    onFindDevices: () -> Unit,
    onStop: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            VeyroSectionHeader("Nearby devices")
            if (isSearching) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = VeyroCyan,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Visible for ${secondsRemaining}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = VeyroCyan,
                    )
                }
            }
        }
        if (isSearching) {
            VeyroOutlineButton(text = "Stop", onClick = onStop, height = 36.dp)
        } else {
            VeyroButton(text = "Find devices", onClick = onFindDevices, height = 36.dp)
        }
    }
}

@Composable
private fun StatusCard(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    VeyroCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isError) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (isError) VeyroRed else VeyroTeal,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("OK", color = VeyroCyan)
            }
        }
    }
}
