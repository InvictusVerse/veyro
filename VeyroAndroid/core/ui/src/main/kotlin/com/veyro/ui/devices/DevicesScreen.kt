// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/devices/DeviceScreen.kt, GPL-3.0.
// Modified for Veyro.
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
import com.veyro.ui.home.HomeViewModel

/**
 * Paired devices, one card each with every action for that device on the card: connect or
 * disconnect, send files, lock a PC, settings, rename and remove. Filtered by the top bar's
 * search (name or fingerprint). The "Add device" button is the host's floating action button.
 *
 * A trust store that failed its HMAC is reported above the list (REQ-I09). Discovery runs while
 * the screen is started so Connect has endpoints.
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
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LifecycleStartEffect(Unit) {
        homeViewModel.acquireDiscovery()
        onStopOrDispose { homeViewModel.releaseDiscovery() }
    }

    // Lock reports back through Home's one-shot message.
    LaunchedEffect(home.message) {
        val message = home.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        homeViewModel.messageShown()
    }

    // "Last connected 5 min ago" moves on while the screen is open.
    val now = rememberTickingClock(periodMillis = 30_000)

    if (trust.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val devices = home.pairedDevices
    val filteredDevices = remember(devices, searchQuery) {
        if (searchQuery.isBlank()) {
            devices
        } else {
            val q = searchQuery.trim()
            devices.filter { device ->
                device.name.contains(q, ignoreCase = true) ||
                    device.deviceId.fingerprint().contains(q, ignoreCase = true)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            trust.loadError?.let { message ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Paired devices could not be verified", style = MaterialTheme.typography.titleMedium)
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (filteredDevices.isEmpty()) {
                EmptyScreen(
                    message = if (devices.isEmpty()) {
                        "No paired devices yet. Pair a PC or a phone once and it connects by itself from then on."
                    } else {
                        "No paired device matches \"$searchQuery\"."
                    },
                    icon = VeyroIcons.Desktop,
                    action = if (devices.isEmpty()) {
                        { Button(onClick = onAddDevice) { Text("Pair a device") } }
                    } else {
                        null
                    },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Bottom room so the floating action button never covers the last card.
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(items = filteredDevices, key = { it.deviceId.value }) { device ->
                        PairedDeviceCard(
                            device = device,
                            fingerprint = device.deviceId.fingerprint(),
                            now = now,
                            canLock = home.canLock(device),
                            onOpenSettings = { onDeviceClick(device.deviceId) },
                            onToggleConnection = { homeViewModel.toggleConnection(device.deviceId) },
                            onSendFiles = { onSendFiles(device.deviceId) },
                            onLock = { homeViewModel.lock(device.deviceId) },
                            onRename = { renamingId = device.deviceId.value },
                            onRemove = { removingId = device.deviceId.value },
                            modifier = Modifier.animateItem(),
                            onSendFolder = onSendFolder
                                ?.takeIf { home.canSendFolder(device) }
                                ?.let { send -> { send(device.deviceId) } },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    val renaming = devices.firstOrNull { it.deviceId.value == renamingId }
    if (renaming != null) {
        // ADR-059: a rename here is this phone's nickname; the device keeps its own name.
        val record = trust.devices.firstOrNull { it.deviceId == renaming.deviceId }
        RenameDialog(
            currentName = renaming.name,
            onDismiss = { renamingId = null },
            onRename = {
                devicesViewModel.rename(renaming.deviceId, it)
                renamingId = null
            },
            supportingText = "Only this phone uses this name.",
            ownName = record?.name,
            onUseOwnName = if (record?.nickname != null) {
                {
                    devicesViewModel.useOwnName(renaming.deviceId)
                    renamingId = null
                }
            } else {
                null
            },
        )
    }

    // ADR-042 and ADR-057: the other device forgets this phone too, now or when it next connects.
    val removing = devices.firstOrNull { it.deviceId.value == removingId }
    if (removing != null) {
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text("Remove ${removing.name}?") },
            text = {
                Text(
                    "This phone and ${removing.name} forget each other. If ${removing.name} is not " +
                        "connected now, it is told the next time it connects. To use it again, pair it again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    devicesViewModel.revoke(removing.deviceId)
                    removingId = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removingId = null }) { Text("Cancel") }
            },
        )
    }
}
