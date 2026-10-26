package com.veyro.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veyro.core.designsystem.theme.VeyroMetricSubStyle
import com.veyro.core.model.ActiveTransfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferRoute
import com.veyro.core.model.TransferStage
import com.veyro.ui.format.formatBytes
import com.veyro.ui.format.formatEta
import com.veyro.ui.format.formatSpeed

/**
 * Compact, persistent in-app live transfer banner.
 *
 * Placed cleanly in the root layout above screen content; never obscures navigation bars or content.
 * Derived from truthful byte counts and engine stages; never displays fake animated progress.
 */
@Composable
public fun ActiveTransferBanner(
    activeTransfer: ActiveTransfer?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = activeTransfer != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        if (activeTransfer == null) return@AnimatedVisibility

        val isComplete = activeTransfer.stage == TransferStage.COMPLETED
        val isFailed = activeTransfer.stage == TransferStage.FAILED
        val isCancelled = activeTransfer.stage == TransferStage.CANCELLED

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isComplete -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f)
                    isFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                // Top header: Peer name + Route badge + Stage icon + Dismiss/Cancel action
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when {
                                isComplete -> MaterialTheme.colorScheme.tertiary
                                isFailed -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = when {
                                    isComplete -> Icons.Default.CheckCircle
                                    isFailed -> Icons.Default.Warning
                                    else -> Icons.Default.Info
                                },
                                contentDescription = null,
                                tint = when {
                                    isComplete -> MaterialTheme.colorScheme.onTertiary
                                    isFailed -> MaterialTheme.colorScheme.onError
                                    else -> MaterialTheme.colorScheme.onPrimary
                                },
                                modifier = Modifier
                                    .padding(5.dp)
                                    .size(18.dp),
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            val role = if (activeTransfer.direction == TransferDirection.SEND) "Sending to" else "Receiving from"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "$role ${activeTransfer.peerDisplayName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (activeTransfer.route != null) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(start = 6.dp),
                                    ) {
                                        Text(
                                            text = if (activeTransfer.route == TransferRoute.WIFI_DIRECT) "P2P" else "LAN",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = activeTransfer.currentFileName.ifEmpty { "Transferring files" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    if (activeTransfer.isTerminal) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel transfer",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Progress Bar
                if (activeTransfer.isTerminal) {
                    LinearProgressIndicator(
                        progress = { if (isComplete) 1f else activeTransfer.overallFraction },
                        color = if (isComplete) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                } else if (activeTransfer.stage == TransferStage.PREPARING || activeTransfer.stage == TransferStage.CONNECTING || activeTransfer.stage == TransferStage.AWAITING_CONSENT) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { activeTransfer.overallFraction },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom row: Bytes / Metrics / Status
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = activeTransfer.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    val bytesText = "${formatBytes(activeTransfer.bytesTransferred)} / ${formatBytes(activeTransfer.totalBytes)}"
                    val speedText = if (activeTransfer.bytesPerSecond > 0 && !activeTransfer.isTerminal) " (${formatSpeed(activeTransfer.bytesPerSecond)})" else ""
                    val etaText = formatEta(activeTransfer.etaSeconds)?.let { " • $it" } ?: ""

                    Text(
                        text = "$bytesText$speedText$etaText",
                        style = VeyroMetricSubStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
