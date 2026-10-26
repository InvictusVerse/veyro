package com.veyro.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroButton
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.components.VeyroOutlineButton
import com.veyro.core.designsystem.components.VeyroSectionHeader
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroRed
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.designsystem.theme.VeyroVioletContainer
import com.veyro.core.designsystem.theme.VeyroVioletGlow

/**
 * Pairing screen (Devices > Pair new device).
 *
 * Scan-to-pair is the primary path (ADR-030): one large button at the top. Below it the live
 * nearby list remains the fallback, where tapping a PC and typing its code runs the original PIN
 * pairing. Discovery starts when the screen opens so the list is live.
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
                busy = state.qrBusy,
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

        // Inbound PIN, shown when this device is the target of a pairing.
        state.inboundPin?.let { pin ->
            item { InboundPinCard(pin = pin, peerName = state.inboundPeerName) }
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
                        enabled = !state.qrBusy,
                    )
                }
            } else {
                item { SearchingCard() }
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    if (state.awaitingPinEntry) {
        PinEntryDialog(
            peerName = state.outboundPeerName,
            onSubmit = viewModel::submitPin,
            onCancel = { viewModel.submitPin(null) },
        )
    }
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
private fun InboundPinCard(pin: String, peerName: String?) {
    VeyroCard(glow = true) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = peerName?.let { "$it is pairing with this device" } ?: "A device is pairing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(VeyroVioletContainer),
            ) {
                GroupedPin(pin = pin, color = VeyroVioletGlow)
            }
            Text(
                text = "Enter this code on the other device to complete pairing.",
                style = MaterialTheme.typography.bodySmall,
                color = VeyroTextSecondary,
                textAlign = TextAlign.Center,
            )
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
