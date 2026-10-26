package com.veyro.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroButton
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.components.VeyroGlassCard
import com.veyro.core.designsystem.components.VeyroOutlineButton
import com.veyro.core.designsystem.components.VeyroProgressBar
import com.veyro.core.designsystem.components.VeyroSectionHeader
import com.veyro.core.designsystem.components.VeyroTextButton
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanContainer
import com.veyro.core.designsystem.theme.VeyroCyanGlow
import com.veyro.core.designsystem.theme.VeyroExtras
import com.veyro.core.designsystem.theme.VeyroGlassBorder
import com.veyro.core.designsystem.theme.VeyroMetricStyle
import com.veyro.core.designsystem.theme.VeyroMetricSubStyle
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.link.OutgoingFile
import com.veyro.core.model.DeviceId
import com.veyro.core.model.fingerprint
import com.veyro.ui.format.formatBytes

/**
 * Send screen — redesigned with a futuristic aesthetic.
 *
 * Layout:
 *   [File picker card]     ← add files, show selection
 *   [Send to section]      ← device list
 *   [Progress card]         ← shows during transfer
 *   [Outcome card]          ← shows result after transfer
 *   [Send CTA]             ← primary button
 */
@Composable
public fun SendScreen(
    viewModel: SendViewModel,
    onPickFiles: () -> Unit,
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
        // File picker card
        item {
            FilePickerCard(
                selected = state.selected,
                totalBytes = state.totalBytes,
                onPickFiles = onPickFiles,
                onRemoveFile = viewModel::removeFile,
                onClearFiles = viewModel::clearFiles,
            )
        }

        // Target section header
        item {
            VeyroSectionHeader("Send to")
        }

        if (state.devices.isEmpty()) {
            item {
                VeyroGlassCard {
                    Text(
                        text = "No paired devices found. Pair a device first from the Home or Devices tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VeyroTextSecondary,
                    )
                }
            }
        } else {
            items(state.devices, key = { it.deviceId.value }) { device ->
                val isSelected = state.target == device.deviceId
                DeviceTargetCard(
                    name = device.name,
                    fingerprint = device.deviceId.fingerprint(),
                    isSelected = isSelected,
                    onClick = { viewModel.selectTarget(device.deviceId) },
                )
            }
        }

        // Progress card
        state.progress?.let { progress ->
            item {
                ProgressCard(
                    currentItemName = progress.currentItemName,
                    bytesTransferred = progress.bytesTransferred,
                    totalBytes = progress.totalBytes,
                    bytesPerSecond = progress.bytesPerSecond,
                )
            }
        }

        // Outcome card
        state.outcome?.let { message ->
            item {
                OutcomeCard(message = message)
            }
        }

        // Bottom CTA
        item {
            Spacer(modifier = Modifier.height(4.dp))
            if (state.isSending) {
                VeyroOutlineButton(
                    text = "Cancel transfer",
                    onClick = viewModel::cancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                VeyroButton(
                    text = when {
                        state.target == null -> "Choose a device"
                        state.selected.isEmpty() -> "Choose files"
                        else -> "Send ${formatBytes(state.totalBytes)}"
                    },
                    onClick = viewModel::send,
                    enabled = state.canSend,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Sub-cards
// ---------------------------------------------------------------------------

@Composable
private fun FilePickerCard(
    selected: List<OutgoingFile>,
    totalBytes: Long,
    onPickFiles: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onClearFiles: () -> Unit,
) {
    VeyroCard(glow = selected.isNotEmpty()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // File icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(VeyroCyanContainer)
                        .border(1.dp, VeyroCyan.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = VeyroCyan,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Files",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (selected.isEmpty()) "None selected"
                        else "${selected.size} file(s) · ${formatBytes(totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected.isEmpty()) VeyroTextSecondary else VeyroCyan,
                    )
                }

                if (selected.isNotEmpty()) {
                    VeyroTextButton(
                        text = "Clear",
                        onClick = onClearFiles,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // File list
            if (selected.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                selected.take(4).forEachIndexed { index, file ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = file.effectiveName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onRemoveFile(index) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = VeyroTextSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                if (selected.size > 4) {
                    Text(
                        text = "and ${selected.size - 4} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = VeyroTextSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            VeyroOutlineButton(
                text = if (selected.isEmpty()) "Choose files" else "Add more files",
                onClick = onPickFiles,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DeviceTargetCard(
    name: String,
    fingerprint: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    VeyroCard(
        selected = isSelected,
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Selection indicator
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) VeyroCyan else VeyroGlassBorder,
                    )
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, VeyroCyanGlow, CircleShape)
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = fingerprint,
                    style = MaterialTheme.typography.labelSmall,
                    color = VeyroTextSecondary,
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = VeyroCyan,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(
    currentItemName: String,
    bytesTransferred: Long,
    totalBytes: Long,
    bytesPerSecond: Long,
) {
    VeyroCard(glow = true) {
        Column {
            Text(
                text = "Sending",
                style = MaterialTheme.typography.labelMedium,
                color = VeyroCyan,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = currentItemName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(12.dp))
            VeyroProgressBar(
                progress = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f,
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
                Spacer(modifier = Modifier.weight(1f))
                if (bytesPerSecond > 0) {
                    Text(
                        text = "${formatBytes(bytesPerSecond)}/s",
                        style = VeyroMetricSubStyle,
                        color = VeyroTextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutcomeCard(message: String) {
    VeyroGlassCard {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
