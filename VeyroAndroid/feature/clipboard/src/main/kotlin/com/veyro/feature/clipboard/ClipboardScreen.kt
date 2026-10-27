package com.veyro.feature.clipboard

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.icons.VeyroIcons

/**
 * The phone's virtual clipboard (ADR-043): what the PCs copied, newest first, with Copy; the
 * "Send clipboard to PC" button; and the "Auto-fetch PC clipboard" switch.
 *
 * Sending text or a link the other way needs no screen: the user shares it, or selects it and
 * picks "Send to PC", from any app. Android lets no app read the clipboard in the background, so
 * this screen's button is the only way the phone's own clipboard reaches the PC.
 */
@Composable
public fun ClipboardScreen(viewModel: ClipboardViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.messageShown()
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "send") {
                SendCard(sending = state.sending, onSend = viewModel::sendClipboardToPc)
            }
            item(key = "auto") {
                AutoFetchRow(checked = state.autoFetch, onChange = viewModel::setAutoFetchEnabled)
            }
            item(key = "header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        "From your PC",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.rows.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearAll) { Text("Clear all") }
                    }
                }
            }
            if (state.rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (state.autoFetch) {
                            "Copy something on your PC and it shows up here, ready to paste."
                        } else {
                            "Auto-fetch is off, so nothing from your PC is kept here."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.rows, key = { "${it.source}|${it.id}" }) { row ->
                ClipCard(row = row, onCopy = { viewModel.copy(row) }, onRemove = { viewModel.remove(row) })
            }
            item(key = "hint") {
                Text(
                    "To send text or a link to your PC, select it or tap Share in any app and choose " +
                        "\"Send to PC\". It lands on the PC's clipboard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

@Composable
private fun SendCard(sending: Boolean, onSend: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Send this phone's clipboard", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sends what you copied here to your PC's Veyro clipboard list, without replacing what the PC has copied.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onSend, enabled = !sending) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Send clipboard to PC")
            }
        }
    }
}

@Composable
private fun AutoFetchRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .semantics { stateDescription = if (checked) "On" else "Off" },
    ) {
        Column(Modifier.weight(1f)) {
            Text("Auto-fetch PC clipboard", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Keep what your PC copies here, with a notification to copy it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // The row carries the semantics; the switch is decorative.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ClipCard(row: ClipRow, onCopy: () -> Unit, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Copy", onClick = onCopy),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val thumbnail = row.thumbnail
                when {
                    thumbnail != null -> Image(
                        bitmap = thumbnail,
                        contentDescription = "Image from ${row.source}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.heightIn(max = 160.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    row.text != null -> Text(
                        row.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else -> Text("Image", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "${row.source} - ${DateUtils.getRelativeTimeSpanString(row.atMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCopy) { Icon(VeyroIcons.ContentCopy, contentDescription = "Copy") }
            IconButton(onClick = onRemove) { Icon(VeyroIcons.Delete, contentDescription = "Remove") }
        }
    }
}
