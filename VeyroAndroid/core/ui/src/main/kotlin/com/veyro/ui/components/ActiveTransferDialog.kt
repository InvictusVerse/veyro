package com.veyro.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veyro.core.designsystem.theme.VeyroMetricStyle
import com.veyro.core.designsystem.theme.VeyroMetricSubStyle
import com.veyro.core.model.ActiveTransfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferStage
import com.veyro.ui.format.formatBytes
import com.veyro.ui.format.formatEta
import com.veyro.ui.format.formatSpeed

/**
 * Detailed Modal Experience for an Active or Terminal Transfer.
 *
 * Displays full metrics: percentage, rolling throughput, remaining time,
 * active stage, and SHA-256 verification indicator.
 */
@Composable
public fun ActiveTransferDialog(
    activeTransfer: ActiveTransfer?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onViewFile: ((String) -> Unit)? = null,
) {
    if (activeTransfer == null) return

    val isComplete = activeTransfer.stage == TransferStage.COMPLETED
    val isFailed = activeTransfer.stage == TransferStage.FAILED
    val isCancelled = activeTransfer.stage == TransferStage.CANCELLED

    AlertDialog(
        onDismissRequest = {
            if (activeTransfer.isTerminal) onDismiss()
        },
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = when {
                        isComplete -> MaterialTheme.colorScheme.tertiary
                        isFailed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(36.dp),
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
                            .padding(6.dp)
                            .size(24.dp),
                    )
                }

                Column {
                    Text(
                        text = if (activeTransfer.direction == TransferDirection.SEND) "Sending" else "Receiving",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${activeTransfer.peerDisplayName} (${activeTransfer.totalFiles} item${if (activeTransfer.totalFiles > 1) "s" else ""})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                // Filename
                Text(
                    text = activeTransfer.currentFileName.ifEmpty { "Transfer" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Big percentage / speed metric
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val percent = (activeTransfer.overallFraction * 100).toInt().coerceIn(0, 100)
                    Text(
                        text = if (isComplete) "100%" else "$percent%",
                        style = VeyroMetricStyle,
                        color = when {
                            isComplete -> MaterialTheme.colorScheme.tertiary
                            isFailed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )

                    if (activeTransfer.bytesPerSecond > 0 && !activeTransfer.isTerminal) {
                        Text(
                            text = formatSpeed(activeTransfer.bytesPerSecond),
                            style = VeyroMetricSubStyle,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Progress Bar
                if (activeTransfer.isTerminal) {
                    LinearProgressIndicator(
                        progress = { if (isComplete) 1f else activeTransfer.overallFraction },
                        color = if (isComplete) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                } else if (activeTransfer.stage == TransferStage.TRANSFERRING || activeTransfer.stage == TransferStage.VERIFYING || activeTransfer.stage == TransferStage.PUBLISHING) {
                    LinearProgressIndicator(
                        progress = { activeTransfer.overallFraction },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                } else {
                    LinearProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Transferred bytes & ETA row
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "${formatBytes(activeTransfer.bytesTransferred)} of ${formatBytes(activeTransfer.totalBytes)}",
                        style = VeyroMetricSubStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val eta = formatEta(activeTransfer.etaSeconds)
                    if (eta != null && !activeTransfer.isTerminal) {
                        Text(
                            text = eta,
                            style = VeyroMetricSubStyle,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Current Stage pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "STATUS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = activeTransfer.statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (activeTransfer.isVerified) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "SHA-256 Digest Verified Byte-for-Byte",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        if (activeTransfer.errorMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Error: ${activeTransfer.errorMessage}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (activeTransfer.isTerminal) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val destUri = activeTransfer.destinationUri
                    if (isComplete && destUri != null && onViewFile != null) {
                        Button(
                            onClick = { onViewFile(destUri) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Text("Open File")
                        }
                    }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    ) {
                        Text("Done")
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Cancel Transfer")
                }
            }
        },
    )
}
