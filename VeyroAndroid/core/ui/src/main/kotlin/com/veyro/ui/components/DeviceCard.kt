// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/common/components/DeviceCard.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.model.Platform
import com.veyro.ui.home.ConnectionStatus
import com.veyro.ui.home.PairedDeviceUi

/**
 * A paired device: avatar, name, connection state, battery while connected, and the sync button
 * that connects or disconnects it.
 *
 * Veyro changes: a platform icon instead of a profile picture (Veyro does not exchange avatars),
 * a "Nearby" / "Not nearby" state from discovery, a spinner while a connect runs, and the button
 * is disabled when the device cannot be reached so it never promises something it cannot do.
 */
@Composable
public fun DeviceCard(
    device: PairedDeviceUi,
    onClick: () -> Unit,
    onSyncAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(),
    ) {
        Box(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                DeviceAvatar(platform = device.platform, size = 56)

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusText(device),
                        color = if (device.isConnected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (device.isConnected && device.battery != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Icon(
                                imageVector = if (device.charging == true) VeyroIcons.BatteryCharging else VeyroIcons.Battery,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${device.battery}%" + if (device.charging == true) ", charging" else "",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                if (device.status == ConnectionStatus.CONNECTING) {
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    FilledIconToggleButton(
                        checked = device.isConnected,
                        onCheckedChange = { onSyncAction() },
                        enabled = device.canToggleConnection,
                        colors = IconButtonDefaults.filledIconToggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Icon(
                            imageVector = if (device.isConnected) VeyroIcons.SyncDisabled else VeyroIcons.Sync,
                            contentDescription = if (device.isConnected) "Disconnect" else "Connect",
                        )
                    }
                }
            }
        }
    }
}

/** Platform icon in a tinted circle; Veyro's stand-in for Sefirah's device avatar. */
@Composable
public fun DeviceAvatar(platform: Platform, size: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = platformIcon(platform),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((size / 2).dp),
        )
    }
}

@Composable
internal fun platformIcon(platform: Platform): ImageVector = when (platform) {
    Platform.ANDROID -> VeyroIcons.Mobile
    Platform.WINDOWS, Platform.UNKNOWN -> VeyroIcons.Desktop
}

private fun statusText(device: PairedDeviceUi): String = when (device.status) {
    ConnectionStatus.CONNECTED -> "Connected"
    ConnectionStatus.CONNECTING -> "Connecting..."
    ConnectionStatus.NEARBY -> if (device.connectFailed) "Could not connect" else "Nearby, not connected"
    ConnectionStatus.OFFLINE -> if (device.connectFailed) "Not found on this network" else "Not nearby"
}
