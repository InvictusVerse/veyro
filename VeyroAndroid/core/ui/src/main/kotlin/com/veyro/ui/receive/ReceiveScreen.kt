package com.veyro.ui.receive

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.components.VeyroGlassCard
import com.veyro.core.designsystem.components.VeyroProgressBar
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanContainer
import com.veyro.core.designsystem.theme.VeyroCyanGlow
import com.veyro.core.designsystem.theme.VeyroCyanMuted
import com.veyro.core.designsystem.theme.VeyroMetricStyle
import com.veyro.core.designsystem.theme.VeyroMetricSubStyle
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTealContainer
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.ui.format.formatBytes

/**
 * Receive screen — redesigned with a futuristic aesthetic.
 *
 * Layout:
 *   [Hero receiving status card]  ← always visible, shows the receiver state
 *   [Progress card]                ← shown during active transfer
 *   [Outcome card]                ← shown after transfer completes
 *
 * The authorization dialog (incoming transfer consent) is handled globally by
 * `InboundTransferDialog` mounted in `MainActivity`, not inline here.
 */
@Composable
public fun ReceiveScreen(
    viewModel: ReceiveViewModel,
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
        // Hero receiving status card
        item {
            ReceivingStatusCard(
                destination = state.destination?.absolutePath,
                isReceiving = state.isReceiving,
            )
        }

        // Progress card
        state.progress?.let { progress ->
            item {
                ReceivingProgressCard(
                    currentItemName = progress.currentItemName,
                    bytesTransferred = progress.bytesTransferred,
                    totalBytes = progress.totalBytes,
                    stateName = progress.state.name,
                )
            }
        }

        // Outcome card
        state.lastOutcome?.let { message ->
            item {
                OutcomeCard(
                    message = message,
                    onDismiss = viewModel::dismissOutcome,
                )
            }
        }

        // Tip card when idle
        if (!state.isReceiving && state.lastOutcome == null && state.progress == null) {
            item {
                TipCard()
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Sub-cards
// ---------------------------------------------------------------------------

@Composable
private fun ReceivingStatusCard(
    destination: String?,
    isReceiving: Boolean,
) {
    VeyroCard(glow = isReceiving) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Animated icon
            PulsingReceiveIcon(isReceiving = isReceiving)

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        isReceiving -> "Receiving files"
                        else -> "Ready to receive"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        isReceiving -> VeyroCyan
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = destination?.let { "Saving to $it" }
                        ?: "Files are saved to this device's storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VeyroTextSecondary,
                )
                if (!isReceiving) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Keep this screen open. Incoming transfers will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VeyroCyanMuted.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceivingProgressCard(
    currentItemName: String,
    bytesTransferred: Long,
    totalBytes: Long,
    stateName: String,
) {
    val fraction = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f

    VeyroCard(glow = true) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(VeyroCyanContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = VeyroCyan,
                        modifier = Modifier.size(22.dp),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentItemName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = stateName.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = VeyroCyan,
                    )
                }

                Text(
                    text = "${(fraction * 100).toInt()}%",
                    style = VeyroMetricStyle,
                    color = VeyroCyan,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            VeyroProgressBar(
                progress = fraction,
                showShimmer = true,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatBytes(bytesTransferred),
                    style = VeyroMetricSubStyle,
                    color = VeyroCyan,
                )
                Text(
                    text = " / ${formatBytes(totalBytes)}",
                    style = VeyroMetricSubStyle,
                    color = VeyroTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun OutcomeCard(
    message: String,
    onDismiss: () -> Unit,
) {
    VeyroCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = if (message.startsWith("Declined")) Icons.Default.CheckCircle else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (message.startsWith("Declined")) VeyroTextSecondary else VeyroTeal,
                modifier = Modifier.size(24.dp),
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
private fun TipCard() {
    VeyroGlassCard {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = VeyroCyanMuted,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Tips",
                    style = MaterialTheme.typography.labelMedium,
                    color = VeyroCyanMuted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "Make sure both devices are on the same Wi-Fi or one is using the other's hotspot.",
                style = MaterialTheme.typography.bodySmall,
                color = VeyroTextSecondary,
            )
            Text(
                text = "The sender needs to select this device from their Veyro app.",
                style = MaterialTheme.typography.bodySmall,
                color = VeyroTextSecondary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

@Composable
private fun PulsingReceiveIcon(isReceiving: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "receiveIcon")
    val alpha by infiniteTransition.animateFloat(
        initialValue = if (isReceiving) 0.5f else 0.3f,
        targetValue = if (isReceiving) 1f else 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isReceiving) 800 else 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "iconAlpha",
    )

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isReceiving) VeyroCyanContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = "Receiving",
            tint = if (isReceiving) VeyroCyan.copy(alpha = alpha) else VeyroTextSecondary.copy(alpha = 0.7f),
            modifier = Modifier.size(28.dp),
        )
    }
}
