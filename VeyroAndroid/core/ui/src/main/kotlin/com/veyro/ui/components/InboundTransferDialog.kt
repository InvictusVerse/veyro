package com.veyro.ui.components

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroButton
import com.veyro.core.designsystem.components.VeyroOutlineButton
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanContainer
import com.veyro.core.designsystem.theme.VeyroCyanGlow
import com.veyro.core.designsystem.theme.VeyroCyanMuted
import com.veyro.core.designsystem.theme.VeyroGlassBorder
import com.veyro.core.designsystem.theme.VeyroMetricStyle
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.link.TransferCoordinator
import com.veyro.ui.format.formatBytes
import kotlinx.coroutines.launch

/**
 * Top-level modal dialog shown on the RECEIVING device when a peer initiates a file transfer.
 *
 * Redesigned: glassmorphic with a cyan glow halo, pulsing download icon, and file summary card.
 * Mounted globally in `MainActivity` so it appears on any screen (REQ-T02 explicit user consent).
 */
@Composable
public fun InboundTransferDialog(
    transferCoordinator: TransferCoordinator,
) {
    val pending by transferCoordinator.pendingOffer.collectAsStateWithLifecycle()
    val offer = pending ?: return
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = {
            // Silence is not consent: user must explicitly tap Accept or Decline.
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box {
            // Outer glow halo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .height(4.dp)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                VeyroCyan.copy(alpha = 0.6f),
                                VeyroCyanGlow,
                                VeyroCyan.copy(alpha = 0.6f),
                            ),
                        ),
                    ),
            )

            GlassmorphicDialogSurface {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .widthIn(min = 300.dp, max = 360.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Pulsing download icon
                    PulsingDownloadIcon()

                    Spacer(modifier = Modifier.height(18.dp))

                    // Title
                    Text(
                        text = "Incoming Transfer",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${offer.peerName} wants to share with you",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VeyroTextSecondary,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // File summary card
                    FileSummaryCard(
                        summary = offer.summary(),
                        totalBytes = offer.totalBytes,
                        fileCount = offer.fileCount,
                        topLevelNames = offer.topLevelNames,
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Accept / Decline buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        VeyroOutlineButton(
                            text = "Decline",
                            onClick = {
                                scope.launch {
                                    transferCoordinator.respondToOffer(offer.transferId, false)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        VeyroButton(
                            text = "Accept",
                            onClick = {
                                scope.launch {
                                    transferCoordinator.respondToOffer(offer.transferId, true)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsingDownloadIcon() {
    val infiniteTransition = rememberInfiniteTransition(label = "downloadIcon")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "downloadAlpha",
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "downloadScale",
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        VeyroCyan.copy(alpha = alpha * 0.15f),
                        VeyroCyanContainer,
                    ),
                ),
            )
            .border(1.5.dp, VeyroCyan.copy(alpha = alpha * 0.6f), CircleShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = null,
            tint = VeyroCyan.copy(alpha = alpha),
            modifier = Modifier
                .size((40 * scale).dp),
        )
    }
}

@Composable
private fun FileSummaryCard(
    summary: String,
    totalBytes: Long,
    fileCount: Int,
    topLevelNames: List<String>,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(0.5.dp, VeyroGlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Column {
            // Summary line
            Text(
                text = summary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Size chip
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(VeyroCyanContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = formatBytes(totalBytes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = VeyroCyan,
                )
            }

            // File list
            if (topLevelNames.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                topLevelNames.take(3).forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(VeyroCyanMuted),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            color = VeyroTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (fileCount > topLevelNames.size) {
                    Text(
                        text = "and ${fileCount - topLevelNames.size} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = VeyroTextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
