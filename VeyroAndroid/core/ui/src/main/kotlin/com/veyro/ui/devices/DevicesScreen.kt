package com.veyro.ui.devices

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroButton
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.components.VeyroChip
import com.veyro.core.designsystem.components.VeyroDangerButton
import com.veyro.core.designsystem.components.VeyroGlassCard
import com.veyro.core.designsystem.components.VeyroOutlineButton
import com.veyro.core.designsystem.components.VeyroSectionHeader
import com.veyro.core.designsystem.theme.VeyroBlue
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanContainer
import com.veyro.core.designsystem.theme.VeyroCyanDark
import com.veyro.core.designsystem.theme.VeyroCyanGlow
import com.veyro.core.designsystem.theme.VeyroCyanMuted
import com.veyro.core.designsystem.theme.VeyroExtras
import com.veyro.core.designsystem.theme.VeyroGlassBorder
import com.veyro.core.designsystem.theme.VeyroRed
import com.veyro.core.designsystem.theme.VeyroRedContainer
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTealContainer
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.model.TrustedPeer

/**
 * Paired devices — redesigned with a futuristic aesthetic.
 *
 * Shows all trusted devices with fingerprint, auto-accept toggle, rename, and revoke.
 */
@Composable
public fun DevicesScreen(
    viewModel: DevicesViewModel,
    onPairNewDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingRevoke by remember { mutableStateOf<TrustedPeer?>(null) }

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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Error card
        state.loadError?.let { message ->
            item {
                ErrorCard(message = message)
            }
        }

        if (state.devices.isEmpty()) {
            item {
                EmptyDevicesCard(onPairNewDevice = onPairNewDevice)
            }
        } else {
            items(state.devices, key = { it.deviceId.value }) { peer ->
                val isRenaming = state.renaming == peer.deviceId
                PairedDeviceCard(
                    peer = peer,
                    fingerprint = viewModel.fingerprintOf(peer),
                    isRenaming = isRenaming,
                    onBeginRename = { viewModel.beginRename(peer.deviceId) },
                    onCancelRename = viewModel::cancelRename,
                    onRename = { viewModel.rename(peer.deviceId, it) },
                    onAutoAccept = { viewModel.setAutoAccept(peer.deviceId, it) },
                    onRevoke = { pendingRevoke = peer },
                )
            }
        }

        // Pair another button
        item {
            if (state.devices.isNotEmpty()) {
                VeyroOutlineButton(
                    text = "Pair another device",
                    onClick = onPairNewDevice,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    // Forget confirmation dialog
    pendingRevoke?.let { peer ->
        ForgetConfirmDialog(
            peerName = peer.name,
            onConfirm = {
                viewModel.revoke(peer.deviceId)
                pendingRevoke = null
            },
            onDismiss = { pendingRevoke = null },
        )
    }
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

@Composable
private fun PairedDeviceCard(
    peer: TrustedPeer,
    fingerprint: String,
    isRenaming: Boolean,
    onBeginRename: () -> Unit,
    onCancelRename: () -> Unit,
    onRename: (String) -> Unit,
    onAutoAccept: (Boolean) -> Unit,
    onRevoke: () -> Unit,
) {
    VeyroCard {
        Column {
            if (isRenaming) {
                RenameField(
                    currentName = peer.name,
                    onSave = onRename,
                    onCancel = onCancelRename,
                )
            } else {
                // Header: avatar + name + fingerprint
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Device avatar
                    DeviceAvatar(peer = peer)

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peer.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = peer.platform.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall,
                            color = VeyroTextSecondary,
                        )
                    }

                    VeyroChip(
                        text = "Paired",
                        color = VeyroTeal,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Fingerprint — always shown (REQ-U04)
                Text(
                    text = fingerprint,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = VeyroTextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(8.dp),
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Auto-accept toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-accept files",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Files from this device are saved without asking",
                            style = MaterialTheme.typography.bodySmall,
                            color = VeyroTextSecondary,
                        )
                    }
                    ToggleSwitch(
                        checked = peer.autoAcceptTransfers,
                        onCheckedChange = onAutoAccept,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Actions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onBeginRename,
                        modifier = Modifier.padding(0.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Rename")
                    }
                    TextButton(
                        onClick = onRevoke,
                        modifier = Modifier.padding(0.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = VeyroRed.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Forget", color = VeyroRed.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceAvatar(peer: TrustedPeer) {
    val icon: ImageVector = when (peer.platform.name) {
        "PHONE" -> Icons.Default.Phone
        "TABLET" -> Icons.Default.Home
        "DESKTOP", "LAPTOP" -> Icons.Default.Home
        else -> Icons.Default.Home
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(VeyroTealContainer)
            .border(1.dp, VeyroTeal.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = VeyroTeal,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun RenameField(
    currentName: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(currentName) { mutableStateOf(currentName) }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Device name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VeyroOutlineButton(
            text = "Cancel",
            onClick = onCancel,
        )
        VeyroButton(
            text = "Save",
            onClick = { onSave(text) },
            enabled = text.isNotBlank(),
        )
    }
}

@Composable
private fun ToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val trackColor = if (checked) VeyroCyan.copy(alpha = 0.3f) else VeyroGlassBorder
    val thumbColor = if (checked) VeyroCyan else VeyroTextSecondary

    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(trackColor)
            .then(
                if (checked) Modifier.border(1.dp, VeyroCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                else Modifier,
            )
            .clickable(onClick = { onCheckedChange(!checked) })
            .padding(3.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    if (checked) VeyroCyanGlow else VeyroTextSecondary.copy(alpha = 0.5f),
                ),
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    VeyroCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(VeyroRedContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = VeyroRed,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Trust store error",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = VeyroTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun EmptyDevicesCard(onPairNewDevice: () -> Unit) {
    VeyroGlassCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(VeyroCyanContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = VeyroCyan,
                    modifier = Modifier.size(32.dp),
                )
            }

            Text(
                text = "No paired devices",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Pair a device once and it will be recognised automatically from then on.",
                style = MaterialTheme.typography.bodyMedium,
                color = VeyroTextSecondary,
            )

            VeyroButton(
                text = "Pair a device",
                onClick = onPairNewDevice,
            )
        }
    }
}

@Composable
private fun ForgetConfirmDialog(
    peerName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Forget $peerName?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = "This device will no longer be recognised, and it will need to be paired again. Transfers in progress are not affected.",
                style = MaterialTheme.typography.bodyMedium,
                color = VeyroTextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Forget", color = VeyroRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = VeyroTextSecondary)
            }
        },
    )
}
