package com.veyro.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.EmptyScreen
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.model.DeviceId
import com.veyro.core.model.fingerprint
import com.veyro.ui.components.rememberTickingClock
import com.veyro.ui.home.HomeUiState
import com.veyro.ui.home.HomeViewModel
import com.veyro.ui.home.PairedDeviceUi

/**
 * The Devices tab: every paired device as one card with all of its actions (connect or
 * disconnect, send files, lock a PC, settings, rename, remove). The top bar's search narrows the
 * list by name or fingerprint, and "Add device" is the host's floating action button.
 *
 * A trust store that failed its HMAC is reported above the list (REQ-I09). Discovery runs while
 * the screen is started, so Connect has endpoints to use.
 */
@Composable
public fun DevicesScreen(
    homeViewModel: HomeViewModel,
    devicesViewModel: DevicesViewModel,
    searchQuery: String,
    onDeviceClick: (DeviceId) -> Unit,
    onAddDevice: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the system file picker and sends the result to this device (ADR-038). */
    onSendFiles: (DeviceId) -> Unit = {},
    /** Opens the system folder picker and sends the folder to this device (ADR-064); null hides it. */
    onSendFolder: ((DeviceId) -> Unit)? = null,
) {
    val home by homeViewModel.state.collectAsStateWithLifecycle()
    val trust by devicesViewModel.state.collectAsStateWithLifecycle()
    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var removeTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHost = remember { SnackbarHostState() }

    LifecycleStartEffect(Unit) {
        homeViewModel.acquireDiscovery()
        onStopOrDispose { homeViewModel.releaseDiscovery() }
    }

    // The result of "lock" arrives as Home's one-shot message; show it, then clear it.
    LaunchedEffect(home.message) {
        val text = home.message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(text)
        homeViewModel.messageShown()
    }

    // Keeps "Last connected 5 min ago" current while the screen stays open.
    val now = rememberTickingClock(periodMillis = 30_000)

    if (trust.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val paired = home.pairedDevices
    val visible = remember(paired, searchQuery) { paired.matching(searchQuery) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            trust.loadError?.let { problem ->
                ProblemBanner(
                    title = "Paired devices could not be verified",
                    message = problem,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (visible.isEmpty()) {
                EmptyScreen(
                    message = if (paired.isEmpty()) {
                        "No paired devices yet. Pair a PC or a phone once and it connects by itself from then on."
                    } else {
                        "No paired device matches \"$searchQuery\"."
                    },
                    icon = VeyroIcons.Desktop,
                    action = if (paired.isEmpty()) {
                        { Button(onClick = onAddDevice) { Text("Pair a device") } }
                    } else {
                        null
                    },
                )
            } else {
                PairedDeviceList(
                    devices = visible,
                    home = home,
                    now = now,
                    onOpenSettings = onDeviceClick,
                    onToggleConnection = homeViewModel::toggleConnection,
                    onLock = homeViewModel::lock,
                    onSendFiles = onSendFiles,
                    onSendFolder = onSendFolder,
                    onRename = { renameTarget = it.value },
                    onRemove = { removeTarget = it.value },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    val toRename = paired.firstOrNull { it.deviceId.value == renameTarget }
    if (toRename != null) {
        // ADR-059: renaming here sets this phone's nickname; the device keeps its own name.
        val record = trust.devices.firstOrNull { it.deviceId == toRename.deviceId }
        RenameDialog(
            currentName = toRename.name,
            onDismiss = { renameTarget = null },
            onRename = {
                devicesViewModel.rename(toRename.deviceId, it)
                renameTarget = null
            },
            supportingText = "Only this phone uses this name.",
            ownName = record?.name,
            onUseOwnName = if (record?.nickname != null) {
                {
                    devicesViewModel.useOwnName(toRename.deviceId)
                    renameTarget = null
                }
            } else {
                null
            },
        )
    }

    val toRemove = paired.firstOrNull { it.deviceId.value == removeTarget }
    if (toRemove != null) {
        RemoveDeviceDialog(
            name = toRemove.name,
            onDismiss = { removeTarget = null },
            onConfirm = {
                devicesViewModel.revoke(toRemove.deviceId)
                removeTarget = null
            },
        )
    }
}

/** Devices whose name or fingerprint contains [query]; everything when the query is blank. */
private fun List<PairedDeviceUi>.matching(query: String): List<PairedDeviceUi> {
    val needle = query.trim()
    if (needle.isEmpty()) return this
    return filter {
        it.name.contains(needle, ignoreCase = true) || it.deviceId.fingerprint().contains(needle, ignoreCase = true)
    }
}

@Composable
private fun PairedDeviceList(
    devices: List<PairedDeviceUi>,
    home: HomeUiState,
    now: Long,
    onOpenSettings: (DeviceId) -> Unit,
    onToggleConnection: (DeviceId) -> Unit,
    onLock: (DeviceId) -> Unit,
    onSendFiles: (DeviceId) -> Unit,
    onSendFolder: ((DeviceId) -> Unit)?,
    onRename: (DeviceId) -> Unit,
    onRemove: (DeviceId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Room under the last card so the floating action button never covers it.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items = devices, key = { it.deviceId.value }) { device ->
            val id = device.deviceId
            PairedDeviceCard(
                device = device,
                fingerprint = id.fingerprint(),
                now = now,
                canLock = home.canLock(device),
                onOpenSettings = { onOpenSettings(id) },
                onToggleConnection = { onToggleConnection(id) },
                onSendFiles = { onSendFiles(id) },
                onLock = { onLock(id) },
                onRename = { onRename(id) },
                onRemove = { onRemove(id) },
                modifier = Modifier.animateItem(),
                onSendFolder = if (onSendFolder != null && home.canSendFolder(device)) {
                    { onSendFolder(id) }
                } else {
                    null
                },
            )
        }
    }
}

/**
 * The confirmation before trust is revoked (ADR-042, ADR-057): the other device forgets this
 * phone too, now if it is connected and otherwise the next time it connects.
 */
@Composable
internal fun RemoveDeviceDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove $name?") },
        text = {
            Text(
                "This phone and $name forget each other. If $name is not connected now, " +
                    "it is told the next time it connects. To use it again, pair it again.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** An error-coloured card for a problem the user must see above a list. */
@Composable
internal fun ProblemBanner(title: String, message: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
