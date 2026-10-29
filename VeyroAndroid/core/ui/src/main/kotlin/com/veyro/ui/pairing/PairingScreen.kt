package com.veyro.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.model.Platform
import com.veyro.core.model.fingerprint
import com.veyro.ui.components.platformIcon
import kotlinx.coroutines.delay

/**
 * Pairing screen (Devices > Pair new device), in the shape of Phone Link's "Link to Windows":
 * one illustration, one sentence of instructions, one primary action, and the nearby PCs as a
 * single list you tap. Rebuilt after the hardware test called the previous one unusable.
 *
 * - Scan QR (ADR-030) is the primary button; the nearby list pairs by comparing a code (ADR-031).
 * - Discovery runs for as long as the screen is open: when a bounded window ends it restarts
 *   itself, so there is no countdown and no Find / Stop to manage.
 * - Status and errors are a single inline line under the list, not stacked cards.
 */
@Composable
public fun PairingScreen(
    viewModel: PairingViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scan = rememberQrScanAction(viewModel)

    LaunchedEffect(Unit) { viewModel.startSearching() }
    // Keep looking while the screen is open; pause while a pairing is in progress.
    LaunchedEffect(state.isSearching, state.pairingBusy) {
        if (!state.isSearching && !state.pairingBusy) {
            delay(1_500)
            viewModel.startSearching()
        }
    }

    // PCs and phones (ADR-052), PCs first: they are what most people pair.
    val devices = state.candidates.sortedBy { if (it.platform == Platform.ANDROID) 1 else 0 }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "hero") { Hero() }

        item(key = "scan") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = scan,
                    enabled = !state.pairingBusy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Icon(VeyroIcons.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Scan the QR code on your PC", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = "On the PC: Veyro > Settings > Pair a new phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        if (state.qr != QrPairingState.Idle) {
            item(key = "qr") {
                QrStatusCard(state = state.qr, onDismiss = viewModel::dismissQrStatus, onRetry = scan)
            }
        }

        item(key = "nearby") {
            NearbyList(
                devices = devices,
                searching = state.isSearching,
                busy = state.pairingBusy,
                onPair = viewModel::pairWith,
            )
        }

        val message = state.error ?: state.status
        if (message != null) {
            item(key = "message") {
                InlineMessage(text = message, isError = state.error != null, onDismiss = viewModel::clearStatus)
            }
        }

        item(key = "help") { Troubleshooting() }
    }

    ConfirmPairingDialog(
        state = state.confirm,
        onCancel = viewModel::cancelConfirm,
        onDismiss = viewModel::dismissConfirm,
        onRetry = viewModel::retryConfirm,
    )
}

/** A laptop and a phone with a link between them; the only illustration on the screen. */
@Composable
private fun Hero() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceBadge { Icon(VeyroIcons.Desktop, contentDescription = null, modifier = Modifier.size(34.dp)) }
            Box(
                Modifier
                    .padding(horizontal = 12.dp)
                    .width(40.dp)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            )
            DeviceBadge { Icon(VeyroIcons.Mobile, contentDescription = null, modifier = Modifier.size(30.dp)) }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Connect a PC or phone",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Open Veyro on the other device and keep both on the same Wi-Fi. Scan the PC's QR code, or pick the device below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DeviceBadge(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun NearbyList(
    devices: List<PairingViewModel.Candidate>,
    searching: Boolean,
    busy: Boolean,
    onPair: (PairingViewModel.Candidate) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Nearby devices",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        OutlinedCard(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // A thin bar instead of a countdown: it is always looking while this screen is open.
            if (searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            if (devices.isEmpty()) {
                Text(
                    text = if (searching) "Looking for PCs and phones running Veyro..." else "Nothing found yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            } else {
                devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 76.dp))
                    DeviceRow(
                        device = device,
                        enabled = !busy && !device.isAlreadyPaired && device.endpoints.isNotEmpty(),
                        onPair = { onPair(device) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: PairingViewModel.Candidate, enabled: Boolean, onPair: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onPair)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(platformIcon(device.platform), contentDescription = null, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    device.isAlreadyPaired -> "Already paired"
                    device.platform == Platform.ANDROID -> "Phone · code ${device.deviceId.fingerprint()}"
                    else -> "PC · code ${device.deviceId.fingerprint()}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (device.isAlreadyPaired) {
            Icon(VeyroIcons.Check, contentDescription = "Already paired", tint = MaterialTheme.colorScheme.primary)
        } else {
            TextButton(onClick = onPair, enabled = enabled) { Text("Connect") }
        }
    }
}

@Composable
private fun InlineMessage(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(if (isError) VeyroIcons.Warning else VeyroIcons.Info, contentDescription = null, tint = color)
        Spacer(Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

@Composable
private fun Troubleshooting() {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text = "Can't see your device?",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        listOf(
            "Veyro is open on the other device. A phone shows up while Veyro is on screen.",
            "Both devices are on the same Wi-Fi or hotspot.",
            "On the PC the network is set to Private, and Veyro was allowed through the firewall.",
        ).forEach { line ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text("\u2022", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
