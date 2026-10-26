package com.veyro.ui.send

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroSectionHeader
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.link.OutgoingFile
import com.veyro.core.model.DeviceId
import com.veyro.core.model.fingerprint
import com.veyro.ui.format.formatBytes

/**
 * Send: pick files, pick a paired device, send - three steps (REQ-U01), restyled as Material 3
 * cards and list items to match the Sefirah-style screens.
 *
 * [initialTarget] preselects a device when Send is opened from a device card or its settings.
 */
@Composable
public fun SendScreen(
    viewModel: SendViewModel,
    onPickFiles: () -> Unit,
    modifier: Modifier = Modifier,
    initialTarget: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) {
        viewModel.startDiscovery()
        onStopOrDispose { viewModel.stopDiscovery() }
    }

    LaunchedEffect(initialTarget) {
        DeviceId.parseOrNull(initialTarget)?.let(viewModel::selectTarget)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FilePickerCard(
                selected = state.selected,
                totalBytes = state.totalBytes,
                onPickFiles = onPickFiles,
                onRemoveFile = viewModel::removeFile,
                onClearFiles = viewModel::clearFiles,
            )
        }

        item { VeyroSectionHeader("Send to") }

        if (state.devices.isEmpty()) {
            item {
                Text(
                    text = "No paired devices. Pair a device first from Home or Devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        state.devices.forEach { device ->
                            val isSelected = state.target == device.deviceId
                            ListItem(
                                modifier = Modifier.selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = { viewModel.selectTarget(device.deviceId) },
                                ),
                                leadingContent = { RadioButton(selected = isSelected, onClick = null) },
                                headlineContent = { Text(device.name) },
                                supportingContent = {
                                    Text(
                                        text = device.deviceId.fingerprint(),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }
        }

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

        state.outcome?.let { message ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            if (state.isSending) {
                OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel transfer")
                }
            } else {
                Button(
                    onClick = viewModel::send,
                    enabled = state.canSend,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            state.target == null -> "Choose a device"
                            state.selected.isEmpty() -> "Choose files"
                            else -> "Send ${formatBytes(state.totalBytes)}"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilePickerCard(
    selected: List<OutgoingFile>,
    totalBytes: Long,
    onPickFiles: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onClearFiles: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(VeyroIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                    Text("Files", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (selected.isEmpty()) "None selected" else "${selected.size} file(s), ${formatBytes(totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selected.isNotEmpty()) {
                    TextButton(onClick = onClearFiles) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                }
            }

            if (selected.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                selected.take(4).forEachIndexed { index, file ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = file.effectiveName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRemoveFile(index) }) {
                            Icon(VeyroIcons.Close, contentDescription = "Remove ${file.effectiveName}", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (selected.size > 4) {
                    Text(
                        text = "and ${selected.size - 4} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onPickFiles, modifier = Modifier.fillMaxWidth()) {
                Text(if (selected.isEmpty()) "Choose files" else "Add more files")
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sending", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(currentItemName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(
                progress = { if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${formatBytes(bytesTransferred)} / ${formatBytes(totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (bytesPerSecond > 0) {
                    Text("${formatBytes(bytesPerSecond)}/s", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
