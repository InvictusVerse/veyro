package com.veyro.ui.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.ui.components.DeviceAvatar
import com.veyro.ui.format.formatBytes

/**
 * Receive: the receiver's state, live progress and the last outcome, restyled as Material 3 cards
 * to match the other screens.
 *
 * The consent prompt for an incoming transfer is global (`InboundTransferDialog` in MainActivity),
 * not inline here, so it appears whichever screen is open.
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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    DeviceAvatar(platform = com.veyro.core.model.Platform.ANDROID, size = 56)
                    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(
                            text = if (state.isReceiving) "Receiving files" else "Ready to receive",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (state.isReceiving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = state.destination?.let { "Saving to ${it.absolutePath}" }
                                ?: "Files are saved to this device's storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        state.progress?.let { progress ->
            item {
                val fraction = if (progress.totalBytes > 0) {
                    (progress.bytesTransferred.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    progress.currentItemName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    progress.state.name.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' '),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                        }
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${formatBytes(progress.bytesTransferred)} / ${formatBytes(progress.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        state.lastOutcome?.let { message ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(modifier = Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(VeyroIcons.Check, contentDescription = null)
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 16.dp),
                        )
                        TextButton(onClick = viewModel::dismissOutcome) { Text("OK") }
                    }
                }
            }
        }

        if (!state.isReceiving && state.lastOutcome == null && state.progress == null) {
            item {
                Column(modifier = Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tips", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Keep both devices on the same Wi-Fi, or connect one to the other's hotspot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "On the PC, choose this phone in Veyro and send. Unless auto-accept is on for that PC, " +
                            "you are asked before anything is saved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
