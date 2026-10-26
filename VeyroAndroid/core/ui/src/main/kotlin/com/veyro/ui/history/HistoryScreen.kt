package com.veyro.ui.history

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroButton
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.components.VeyroChip
import com.veyro.core.designsystem.components.VeyroGlassCard
import com.veyro.core.designsystem.components.VeyroSectionHeader
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanContainer
import com.veyro.core.designsystem.theme.VeyroCyanMuted
import com.veyro.core.designsystem.theme.VeyroExtras
import com.veyro.core.designsystem.theme.VeyroGlassBorder
import com.veyro.core.designsystem.theme.VeyroMetricStyle
import com.veyro.core.designsystem.theme.VeyroMetricSubStyle
import com.veyro.core.designsystem.theme.VeyroRed
import com.veyro.core.designsystem.theme.VeyroRedContainer
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTealContainer
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.model.Transfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferState
import com.veyro.ui.format.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Transfer history — redesigned with a futuristic aesthetic.
 *
 * Shows verified transfer records with filter chips, search, and direct file viewer integration.
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
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = VeyroCyan) }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Search bar
        item {
            SearchBar(
                query = state.searchQuery,
                onQueryChange = viewModel::setSearchQuery,
                placeholder = "Search by file or device...",
            )
        }

        // Filter chips
        item {
            FilterRow(
                current = state.filter,
                totalCount = state.transfers.size,
                onFilterChange = viewModel::setFilter,
            )
        }

        val itemsToShow = state.filteredTransfers

        if (itemsToShow.isEmpty()) {
            item {
                EmptyHistoryCard(
                    isSearchEmpty = state.searchQuery.isNotEmpty(),
                    hasAnyTransfers = state.transfers.isNotEmpty(),
                )
            }
        } else {
            items(itemsToShow, key = { it.id }) { transfer ->
                HistoryItemCard(
                    transfer = transfer,
                    description = viewModel.describe(transfer),
                    isProblem = viewModel.isProblem(transfer),
                    onOpenFile = if (transfer.destinationUri != null) {
                        { openFile(transfer.destinationUri!!) }
                    } else null,
                )
            }
        }

        // Clear history
        if (state.transfers.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = viewModel::clear,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Clear history",
                        color = VeyroTextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                placeholder,
                color = VeyroTextSecondary,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = VeyroTextSecondary,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = VeyroTextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VeyroCyan.copy(alpha = 0.5f),
            unfocusedBorderColor = VeyroGlassBorder,
            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FilterRow(
    current: HistoryFilter,
    totalCount: Int,
    onFilterChange: (HistoryFilter) -> Unit,
) {
    val filters = listOf(
        HistoryFilter.ALL to "All",
        HistoryFilter.SENT to "Sent",
        HistoryFilter.RECEIVED to "Received",
        HistoryFilter.VERIFIED to "Verified",
    )

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filters.size) { index ->
            val (filter, label) = filters[index]
            val count = when (filter) {
                HistoryFilter.ALL -> totalCount
                HistoryFilter.SENT -> 0
                HistoryFilter.RECEIVED -> 0
                HistoryFilter.VERIFIED -> 0
            }
            val displayLabel = if (filter == HistoryFilter.ALL && count > 0) "$label ($count)" else label

            FilterChip(
                selected = current == filter,
                onClick = { onFilterChange(filter) },
                label = { Text(displayLabel) },
                shape = RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = VeyroCyanContainer,
                    selectedLabelColor = VeyroCyan,
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = VeyroTextSecondary,
                ),
            )
        }
    }
}

@Composable
private fun HistoryItemCard(
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
        isProblem -> VeyroRed
        isComplete -> VeyroTeal
        else -> VeyroTextSecondary
    }

    val statusBg = when {
        isProblem -> VeyroRedContainer
        isComplete -> VeyroTealContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }

    VeyroCard(
        glow = isComplete && isIncoming,
    ) {
        Column {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Direction icon
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isIncoming) VeyroCyanContainer else VeyroTealContainer,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isIncoming) Icons.Default.ArrowBack else Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = if (isIncoming) VeyroCyan else VeyroTeal,
                        modifier = Modifier.size(22.dp),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Name + peer + timestamp
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (transfer.itemCount > 1) "$firstItemName (+${transfer.itemCount - 1})"
                        else firstItemName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${if (isIncoming) "From" else "To"} ${transfer.peerDisplayName ?: "Device"} · ${timestamp(transfer.startedAtEpochMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = VeyroTextSecondary,
                    )
                }

                // Size
                Text(
                    text = formatBytes(transfer.totalBytes),
                    style = VeyroMetricStyle,
                    color = if (isComplete) VeyroTeal else VeyroCyanMuted,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom row: status chip + open file button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Status chip
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusBg)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when {
                                isProblem -> Icons.Default.Warning
                                isComplete -> Icons.Default.CheckCircle
                                else -> Icons.Default.Info
                            },
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isComplete) "SHA-256 VERIFIED" else description.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                        )
                    }
                }

                // Open file button
                if (onOpenFile != null) {
                    TextButton(onClick = onOpenFile) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = VeyroCyan,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Open file",
                            color = VeyroCyan,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryCard(
    isSearchEmpty: Boolean,
    hasAnyTransfers: Boolean,
) {
    VeyroGlassCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = null,
                    tint = VeyroTextSecondary,
                    modifier = Modifier.size(32.dp),
                )
            }

            Text(
                text = if (isSearchEmpty) "No transfers yet" else "No matching records",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (isSearchEmpty) {
                    "Transfers you send or receive will appear here."
                } else {
                    "Try searching with a different term."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = VeyroTextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun timestamp(millis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
