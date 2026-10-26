package com.veyro.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.components.VeyroGlassCard
import com.veyro.core.designsystem.components.VeyroOutlineButton
import com.veyro.core.designsystem.components.VeyroSectionHeader
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanDark
import com.veyro.core.designsystem.theme.VeyroCyanGlow
import com.veyro.core.designsystem.theme.VeyroCyanMuted
import com.veyro.core.designsystem.theme.VeyroExtras
import com.veyro.core.designsystem.theme.VeyroRed
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.designsystem.theme.VeyroVoid
import com.veyro.core.link.LinkMessage
import com.veyro.core.model.PeerDevice
import com.veyro.core.network.DiagnosisAdvice

/**
 * The hub: nearby and trusted devices — redesigned with a futuristic aesthetic.
 *
 * Layout:
 *   [Hero Network Banner]  ← always visible
 *   [Your devices]        ← paired, trusted
 *   [Nearby]             ← discovered but not paired
 *   [Empty state]         ← when nothing found
 *   [Pair new device CTA]
 */
@Composable
public fun HomeScreen(
    viewModel: HomeViewModel,
    onDeviceClick: (PeerDevice) -> Unit = {},
    onPairClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) {
        viewModel.startDiscovery()
        onStopOrDispose { viewModel.stopDiscovery() }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Hero network status banner
        item {
            NetworkBannerCard(state)
        }

        // What is playing on the connected PC (ADR-026)
        state.pcMediaSession?.let { session ->
            item(key = "pc-media") {
                PcMediaCard(
                    session = session,
                    volume = state.pcMedia?.volume,
                    onCommand = viewModel::mediaCommand,
                )
            }
        }

        // Trusted devices section
        if (state.trustedDevices.isNotEmpty()) {
            item {
                VeyroSectionHeader("Your devices")
            }
            items(state.trustedDevices, key = { it.deviceId.value }) { device ->
                DeviceCard(
                    device,
                    isTrusted = true,
                    onClick = { onDeviceClick(device) },
                    status = state.statusFor(device),
                )
            }
        }

        // Nearby devices section
        if (state.otherDevices.isNotEmpty()) {
            item {
                VeyroSectionHeader("Nearby")
            }
            items(state.otherDevices, key = { it.deviceId.value }) { device ->
                DeviceCard(device, isTrusted = false, onClick = { onDeviceClick(device) })
            }
        }

        // Empty / searching state
        if (state.devices.isEmpty()) {
            item {
                if (state.shouldExplainEmptiness) {
                    EmptyWithDiagnosis(state, onPairClick)
                } else {
                    SearchingState()
                }
            }
        }

        // Bottom CTA
        item {
            Spacer(modifier = Modifier.height(8.dp))
            VeyroOutlineButton(
                text = "Pair a new device",
                onClick = onPairClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item { Spacer(modifier = Modifier.height(80.dp)) } // Nav bar padding
    }
}

// ---------------------------------------------------------------------------
// Device cards
// ---------------------------------------------------------------------------

@Composable
private fun DeviceCard(
    device: PeerDevice,
    isTrusted: Boolean,
    onClick: () -> Unit,
    status: LinkMessage.DeviceStatus? = null,
) {
    VeyroCard(
        onClick = onClick,
        glow = false,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isTrusted) VeyroTeal else VeyroCyanMuted),
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (isTrusted) "Trusted · ${device.platform.name.lowercase().replaceFirstChar { it.uppercase() }}"
                    else "Tap to pair · ${device.platform.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isTrusted) VeyroTeal.copy(alpha = 0.8f) else VeyroTextSecondary,
                )
                status?.battery?.let { battery ->
                    Text(
                        text = "Connected \u00b7 Battery $battery%" + if (status.charging == true) ", charging" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = VeyroTextSecondary,
                    )
                }
            }

            Icon(
                imageVector = if (isTrusted) Icons.Default.CheckCircle else Icons.Default.Star,
                contentDescription = null,
                tint = if (isTrusted) VeyroTeal else VeyroTextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Network banner
// ---------------------------------------------------------------------------

@Composable
private fun NetworkBannerCard(state: HomeUiState) {
    val networkColor = when {
        state.isVpnActive || !state.hasUsableNetwork -> VeyroRed
        state.isHotspotActive -> VeyroTeal
        else -> VeyroCyan
    }

    val networkLabel = when {
        state.isHotspotActive -> "Hotspot active"
        !state.hasUsableNetwork -> "No usable network"
        else -> state.transport.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    val address = state.localAddresses.firstOrNull() ?: ""

    VeyroCard(glow = true) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pulsing radar icon
            PulsingIcon(
                icon = when {
                    !state.hasUsableNetwork -> Icons.Default.Warning
                    state.isVpnActive -> Icons.Default.Star
                    else -> Icons.Default.Refresh
                },
                color = networkColor,
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = networkLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = networkColor,
                )
                if (address.isNotEmpty()) {
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodySmall,
                        color = VeyroTextSecondary,
                    )
                }
                if (state.isVpnActive) {
                    Text(
                        text = "VPN active — transfers may be slower",
                        style = MaterialTheme.typography.labelSmall,
                        color = VeyroRed.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Empty states
// ---------------------------------------------------------------------------

@Composable
private fun SearchingState() {
    VeyroGlassCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Pulsing radar
                PulsingIcon(
                icon = Icons.Default.Star,
                color = VeyroCyan,
                size = 40.dp,
            )

            Text(
                text = "Looking for devices",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Make sure Veyro is open on the other device.",
                style = MaterialTheme.typography.bodySmall,
                color = VeyroTextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EmptyWithDiagnosis(state: HomeUiState, onPairClick: () -> Unit) {
    val advice = DiagnosisAdvice.forDiagnosis(state.diagnosis)

    VeyroGlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.diagnosis.isNetworkProblem) Icons.Default.Warning else Icons.Default.Home,
                    contentDescription = null,
                    tint = if (state.diagnosis.isNetworkProblem) VeyroRed else VeyroCyanMuted,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "No devices found",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(
                text = advice.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            advice.steps.forEach { step ->
                Row {
                    Text(
                        text = "\u2022 ",
                        style = MaterialTheme.typography.bodySmall,
                        color = VeyroCyan,
                    )
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodySmall,
                        color = VeyroTextSecondary,
                    )
                }
            }

            if (advice.steps.isEmpty()) {
                Text(
                    text = "If this is the first time, the devices need to be paired.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VeyroTextSecondary,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            VeyroOutlineButton(
                text = "Pair a device",
                onClick = onPairClick,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

@Composable
private fun PulsingIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    size: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Box(
        modifier = Modifier
            .size(size + 8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha * 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color.copy(alpha = alpha),
            modifier = Modifier.size(size),
        )
    }
}
