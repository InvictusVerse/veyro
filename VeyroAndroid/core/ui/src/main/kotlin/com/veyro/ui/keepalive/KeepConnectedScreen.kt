package com.veyro.ui.keepalive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.icons.VeyroIcons

/**
 * "Keep Veyro connected" (ADR-061): each setting that can close the background connection, what
 * it is set to now, and a button to the system screen that changes it. Reached from Settings and
 * from Home's notice. Every fact is read again when the user comes back from a system screen.
 */
@Composable
public fun KeepConnectedScreen(
    viewModel: KeepConnectedViewModel,
    /** The local network permission needs the Activity (a dialog, or App info). */
    onFixLocalNetwork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
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
            item(key = "intro") {
                Text(
                    text = "Android and your phone's maker can close apps in the background to save battery. " +
                        "Each item shows how it is set now; the buttons open the screen that changes it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.rows, key = { it.id.name }) { row ->
                CheckCard(
                    row = row,
                    onAction = { target ->
                        if (target == KeepAliveTarget.LOCAL_NETWORK) onFixLocalNetwork() else viewModel.onAction(target)
                    },
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckCard(row: CheckRow, onAction: (KeepAliveTarget) -> Unit) {
    val needsAction = row.status == CheckStatus.NEEDS_ACTION
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (needsAction) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            contentColor = if (needsAction) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (row.status) {
                        CheckStatus.OK -> VeyroIcons.Check
                        CheckStatus.NEEDS_ACTION -> VeyroIcons.Warning
                        CheckStatus.INFO, CheckStatus.CHECK -> VeyroIcons.Info
                    },
                    // The status is also in words below, never in the icon alone.
                    contentDescription = null,
                    tint = when (row.status) {
                        CheckStatus.OK -> MaterialTheme.colorScheme.primary
                        CheckStatus.NEEDS_ACTION -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(row.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    text = statusLabel(row.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (needsAction) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(row.summary, style = MaterialTheme.typography.bodyMedium)
            row.steps.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
            }
            if (row.actions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.actions.forEachIndexed { index, action ->
                        // The first button is the fix; the rest (App info) are fallbacks.
                        if (index == 0 && row.status != CheckStatus.OK) {
                            FilledTonalButton(onClick = { onAction(action.target) }) { Text(action.label) }
                        } else {
                            OutlinedButton(onClick = { onAction(action.target) }) { Text(action.label) }
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: CheckStatus): String = when (status) {
    CheckStatus.OK -> "OK"
    CheckStatus.NEEDS_ACTION -> "Needs a change"
    CheckStatus.INFO -> "Information"
    CheckStatus.CHECK -> "Check it"
}
