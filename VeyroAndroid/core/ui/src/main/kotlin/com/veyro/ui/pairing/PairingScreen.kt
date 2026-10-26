package com.veyro.ui.pairing

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroButton
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.components.VeyroChip
import com.veyro.core.designsystem.components.VeyroGlassCard
import com.veyro.core.designsystem.components.VeyroOutlineButton
import com.veyro.core.designsystem.components.VeyroSectionHeader
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanContainer
import com.veyro.core.designsystem.theme.VeyroCyanDark
import com.veyro.core.designsystem.theme.VeyroCyanGlow
import com.veyro.core.designsystem.theme.VeyroExtras
import com.veyro.core.designsystem.theme.VeyroGlassBorder
import com.veyro.core.designsystem.theme.VeyroPinStyle
import com.veyro.core.designsystem.theme.VeyroRed
import com.veyro.core.designsystem.theme.VeyroRedContainer
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTealContainer
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.designsystem.theme.VeyroViolet
import com.veyro.core.designsystem.theme.VeyroVioletContainer
import com.veyro.core.designsystem.theme.VeyroVioletGlow

/**
 * Pairing screen — redesigned with a futuristic aesthetic.
 *
 * Two faces: the **target** sees a PIN to read aloud; the **initiator** sees a PIN entry field.
 * The inbound PIN card appears on either face when a pairing request arrives.
 */
@Composable
public fun PairingScreen(
    viewModel: PairingViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header card
        item {
            PairingHeaderCard(
                isSearching = state.isSearching,
                secondsRemaining = state.secondsRemaining,
                onFindDevices = viewModel::startSearching,
                onStop = viewModel::stopSearching,
            )
        }

        // Inbound PIN — shown when this device is the target of a pairing
        state.inboundPin?.let { pin ->
            item {
                InboundPinCard(pin = pin, peerName = state.inboundPeerName)
            }
        }

        // Status / error
        state.status?.let { msg ->
            item { StatusCard(message = msg, isError = false, onDismiss = viewModel::clearStatus) }
        }
        state.error?.let { msg ->
            item { StatusCard(message = msg, isError = true, onDismiss = viewModel::clearStatus) }
        }

        // Candidates
        if (state.isSearching) {
            if (state.candidates.isNotEmpty()) {
                item { VeyroSectionHeader("Nearby devices") }
                items(state.candidates) { candidate ->
                    CandidateCard(
                        candidate = candidate,
                        onPair = { viewModel.pairWith(candidate) },
                    )
                }
            } else {
                item { SearchingPlaceholder() }
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    // Initiator PIN entry dialog
    if (state.awaitingPinEntry) {
        PinEntryDialog(
            peerName = state.outboundPeerName,
            onSubmit = viewModel::submitPin,
            onCancel = { viewModel.submitPin(null) },
        )
    }
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

@Composable
private fun PairingHeaderCard(
    isSearching: Boolean,
    secondsRemaining: Int,
    onFindDevices: () -> Unit,
    onStop: () -> Unit,
) {
    VeyroCard(glow = isSearching) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Animated radar icon
            PulsingRadarIcon(isSearching = isSearching)

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pair a device",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (isSearching) {
                    Spacer(modifier = Modifier.height(4.dp))
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
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Open Veyro on the other device and tap Find devices there too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VeyroTextSecondary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isSearching) {
            VeyroOutlineButton(
                text = "Stop",
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            VeyroButton(
                text = "Find devices",
                onClick = onFindDevices,
                modifier = Modifier.fillMaxWidth(),
            )
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
            // Label
            Text(
                text = peerName?.let { "$it is pairing with this device" }
                    ?: "A device is pairing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            Text(
                text = "Read this code on the other device:",
                style = MaterialTheme.typography.bodySmall,
                color = VeyroTextSecondary,
                textAlign = TextAlign.Center,
            )

            // PIN display with violet glow
            PinDisplay(pin = pin)

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
private fun PinDisplay(pin: String) {
    val grouped = pin.chunked(3).joinToString("  ")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(VeyroVioletContainer)
            .border(1.dp, VeyroViolet.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = grouped,
            style = VeyroPinStyle,
            color = VeyroVioletGlow,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CandidateCard(
    candidate: PairingViewModel.Candidate,
    onPair: () -> Unit,
) {
    VeyroCard(
        selected = candidate.isAlreadyPaired,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Device icon
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
                    imageVector = Icons.Default.Home,
                    contentDescription = null,
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
                    text = if (candidate.isAlreadyPaired) "Already paired"
                    else candidate.platform.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (candidate.isAlreadyPaired) VeyroTeal else VeyroTextSecondary,
                )
            }

            if (candidate.isAlreadyPaired) {
                VeyroChip(text = "Paired", color = VeyroTeal)
            } else if (candidate.endpoints.isNotEmpty()) {
                VeyroOutlineButton(
                    text = "Pair",
                    onClick = onPair,
                    height = 36.dp,
                )
            }
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
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

@Composable
private fun SearchingPlaceholder() {
    VeyroGlassCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PulsingRadarIcon(isSearching = true, size = 36.dp)
            Text(
                text = "Searching for nearby devices...",
                style = MaterialTheme.typography.bodyMedium,
                color = VeyroTextSecondary,
            )
            Text(
                text = "Both devices need to be on the same Wi-Fi or one on the other's hotspot.",
                style = MaterialTheme.typography.bodySmall,
                color = VeyroTextSecondary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// PIN entry dialog
// ---------------------------------------------------------------------------

@Composable
private fun PinEntryDialog(
    peerName: String?,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Enter the code",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = peerName?.let { "$it is showing a 6-digit code. Type it here." }
                        ?: "Type the 6-digit code shown on the other device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VeyroTextSecondary,
                )

                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = VeyroPinStyle.copy(fontSize = 32.sp),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(pin) },
                enabled = pin.length == 6,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = VeyroViolet,
                ),
            ) {
                Text("Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = VeyroTextSecondary)
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

@Composable
private fun PulsingRadarIcon(
    isSearching: Boolean,
    size: androidx.compose.ui.unit.Dp = 56.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val alpha by infiniteTransition.animateFloat(
        initialValue = if (isSearching) 0.5f else 0.3f,
        targetValue = if (isSearching) 1f else 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSearching) 800 else 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "radarAlpha",
    )

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSearching) VeyroCyanContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "Pairing",
            tint = if (isSearching) VeyroCyan.copy(alpha = alpha) else VeyroTextSecondary.copy(alpha = 0.7f),
            modifier = Modifier.size((size.value * 0.5f).dp),
        )
    }
}
