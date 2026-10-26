package com.veyro.ui.history

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.EmptyScreen
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.model.Transfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferState
import com.veyro.ui.format.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Transfer history: search, filter chips and one card per transfer with its real verification
 * outcome (REQ-T04), restyled as Material 3 list items to match the Sefirah-style screens.
 *
 * No Receive button: receiving is always on (ADR-038).
 */
@Composable
public fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val openFile: (String) -> Unit = { uriStr ->
        runCatching {
            val uri = Uri.parse(uriStr)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open file with..."))
        }
    }

    if (state.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            placeholder = { Text("Search by file or device") },
            leadingIcon = { Icon(VeyroIcons.Search, contentDescription = null) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(VeyroIcons.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )

        val filters = listOf(
            HistoryFilter.ALL to "All",
            HistoryFilter.SENT to "Sent",
            HistoryFilter.RECEIVED to "Received",
            HistoryFilter.VERIFIED to "Verified",
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filters) { (filter, label) ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    label = { Text(if (filter == HistoryFilter.ALL && state.transfers.isNotEmpty()) "$label (${state.transfers.size})" else label) },
                )
            }
        }

        val itemsToShow = state.filteredTransfers
        if (itemsToShow.isEmpty()) {
            EmptyScreen(
                message = when {
                    state.transfers.isEmpty() -> "No transfers yet. Files you send or receive appear here."
                    else -> "No matching transfers. Try a different search or filter."
                },
                icon = VeyroIcons.History,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(itemsToShow, key = { it.id }) { transfer ->
                    HistoryItem(
                        transfer = transfer,
                        description = viewModel.describe(transfer),
                        isProblem = viewModel.isProblem(transfer),
                        onOpenFile = transfer.destinationUri?.let { uri -> { openFile(uri) } },
                    )
                }
                item {
                    TextButton(onClick = viewModel::clear, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear history")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(
    transfer: Transfer,
    description: String,
    isProblem: Boolean,
    onOpenFile: (() -> Unit)?,
) {
    val isComplete = transfer.state == TransferState.COMPLETED
    val isIncoming = transfer.direction == TransferDirection.RECEIVE
    val firstItemName = transfer.items.firstOrNull()?.fileName
        ?: (if (isIncoming) "Received files" else "Sent files")
    val statusColor = when {
        isProblem -> MaterialTheme.colorScheme.error
        isComplete -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = if (isIncoming) VeyroIcons.Download else VeyroIcons.Upload,
                    contentDescription = if (isIncoming) "Received" else "Sent",
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            headlineContent = {
                Text(
                    text = if (transfer.itemCount > 1) "$firstItemName (+${transfer.itemCount - 1})" else firstItemName,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            },
            supportingContent = {
                Column {
                    Text(
                        text = "${if (isIncoming) "From" else "To"} ${transfer.peerDisplayName ?: "device"}, " +
                            "${timestamp(transfer.startedAtEpochMillis)}, ${formatBytes(transfer.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (isComplete) "Completed, SHA-256 verified" else description,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                    )
                }
            },
            trailingContent = onOpenFile?.let { open ->
                { TextButton(onClick = open) { Text("Open") } }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

private fun timestamp(millis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
