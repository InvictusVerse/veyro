package com.veyro.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.model.Platform
import com.veyro.ui.components.platformIcon
import com.veyro.ui.home.ConnectionStatus
import com.veyro.ui.home.PairedDeviceUi
import com.veyro.ui.home.relativeTime

/** How a card reads a device's state: the words, and which colour family carries them. */
internal enum class DeviceTone { CONNECTED, BUSY, NEARBY, AWAY, HELD }

internal data class DeviceCardStatus(val label: String, val detail: String?, val tone: DeviceTone)

/**
 * The status line and the line under it, in words that stand on their own (colour only repeats
 * them). [now] is passed in so "Last connected 5 min ago" can be tested and moves on with a clock.
 */
internal fun deviceCardStatus(device: PairedDeviceUi, now: Long): DeviceCardStatus {
    val lastSeen = when {
        device.lastConnectedEpochMillis > 0 -> "Last connected ${relativeTime(device.lastConnectedEpochMillis, now)}"
        device.pairedAtEpochMillis > 0 -> "Paired ${relativeTime(device.pairedAtEpochMillis, now)}, not connected yet"
        else -> null
    }
    return when {
        device.status == ConnectionStatus.CONNECTED -> DeviceCardStatus("Connected", null, DeviceTone.CONNECTED)
        device.status == ConnectionStatus.CONNECTING -> DeviceCardStatus("Connecting...", null, DeviceTone.BUSY)
        device.disconnectedByUser -> DeviceCardStatus("Disconnected by you", "It stays off until you connect it.", DeviceTone.HELD)
        device.status == ConnectionStatus.NEARBY && device.connectFailed ->
            DeviceCardStatus("Nearby, could not connect", lastSeen, DeviceTone.NEARBY)
        device.status == ConnectionStatus.NEARBY -> DeviceCardStatus("Nearby", lastSeen, DeviceTone.NEARBY)
        device.connectFailed -> DeviceCardStatus("Not found on this network", lastSeen, DeviceTone.AWAY)
        else -> DeviceCardStatus("Not nearby", lastSeen, DeviceTone.AWAY)
    }
}

private data class ToneColors(val plate: Color, val onPlate: Color, val dot: Color, val text: Color)

@Composable
private fun toneColors(tone: DeviceTone): ToneColors {
    val c = MaterialTheme.colorScheme
    return when (tone) {
        DeviceTone.CONNECTED -> ToneColors(c.primaryContainer, c.onPrimaryContainer, c.primary, c.primary)
        DeviceTone.BUSY -> ToneColors(c.secondaryContainer, c.onSecondaryContainer, c.secondary, c.secondary)
        DeviceTone.NEARBY -> ToneColors(c.tertiaryContainer, c.onTertiaryContainer, c.tertiary, c.tertiary)
        DeviceTone.AWAY -> ToneColors(c.surfaceContainerHighest, c.onSurfaceVariant, c.outline, c.onSurfaceVariant)
        DeviceTone.HELD -> ToneColors(c.surfaceContainerHighest, c.onSurfaceVariant, c.error, c.onSurfaceVariant)
    }
}

private val CardShape = RoundedCornerShape(26.dp)
private val PlateShape = RoundedCornerShape(20.dp)

/**
 * One paired device on Devices, with every action for it on the card itself: connect or
 * disconnect, send files, lock (a connected PC that supports it), settings, rename and remove.
 *
 * The plate on the left is the card's one loud element: the device's glyph on a tile coloured by
 * its state, with a status dot. The two big buttons swap emphasis with the state: Connect leads
 * while it is not connected, Send files once it is. Everything below the divider is secondary.
 */
@Composable
internal fun PairedDeviceCard(
    device: PairedDeviceUi,
    fingerprint: String,
    now: Long,
    canLock: Boolean,
    onOpenSettings: () -> Unit,
    onToggleConnection: () -> Unit,
    onSendFiles: () -> Unit,
    onLock: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    /** Send folder (ADR-064); null when this device cannot receive folders. */
    onSendFolder: (() -> Unit)? = null,
) {
    val status = deviceCardStatus(device, now)
    val tone = toneColors(status.tone)
    val colors = MaterialTheme.colorScheme

    Surface(
        shape = CardShape,
        color = colors.surfaceContainerLow,
        border = if (device.isConnected) {
            BorderStroke(1.5.dp, colors.primary.copy(alpha = 0.55f))
        } else {
            BorderStroke(1.dp, colors.outlineVariant)
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // Header: the plate, name, state. Tapping it opens the device's settings.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Open settings for ${device.name}", onClick = onOpenSettings)
                    .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
            ) {
                DevicePlate(device = device, tone = tone, ringColor = colors.surfaceContainerLow)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = status.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = tone.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (device.isConnected) {
                        LiveReadings(device)
                    } else if (status.detail != null) {
                        Text(
                            text = status.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        text = fingerprint,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.outline,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .semantics { contentDescription = "Fingerprint $fingerprint" },
                    )
                }
            }

            // Primary actions: whichever is the natural next step gets the filled button.
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
            ) {
                ConnectionButton(device = device, onClick = onToggleConnection, modifier = Modifier.weight(1f))
                if (device.isConnected) {
                    Button(onClick = onSendFiles, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        ButtonContent(VeyroIcons.Upload, "Send files")
                    }
                } else {
                    OutlinedButton(onClick = onSendFiles, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        ButtonContent(VeyroIcons.Upload, "Send files")
                    }
                }
            }

            HorizontalDivider(
                color = colors.outlineVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp),
            )

            // Secondary actions, each an icon over its name.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                if (canLock) {
                    CardAction(VeyroIcons.Lock, "Lock", colors.onSurfaceVariant, onLock, Modifier.weight(1f))
                }
                if (onSendFolder != null) {
                    CardAction(VeyroIcons.Folder, "Send folder", colors.onSurfaceVariant, onSendFolder, Modifier.weight(1f))
                }
                CardAction(VeyroIcons.SettingsFill, "Settings", colors.onSurfaceVariant, onOpenSettings, Modifier.weight(1f))
                CardAction(VeyroIcons.Edit, "Rename", colors.onSurfaceVariant, onRename, Modifier.weight(1f))
                CardAction(VeyroIcons.Delete, "Remove", colors.error, onRemove, Modifier.weight(1f))
            }
        }
    }
}

/** The glyph tile with its status dot, ringed in the card colour so it reads as cut out. */
@Composable
private fun DevicePlate(device: PairedDeviceUi, tone: ToneColors, ringColor: Color) {
    Box(modifier = Modifier.size(68.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(PlateShape)
                .background(tone.plate),
        ) {
            Icon(
                imageVector = platformIcon(device.platform),
                contentDescription = if (device.platform == Platform.ANDROID) "Phone" else "PC",
                tint = tone.onPlate,
                modifier = Modifier.size(30.dp),
            )
        }
        val corner = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 2.dp, y = 2.dp)
        if (device.status == ConnectionStatus.CONNECTING) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = corner.size(22.dp).clip(CircleShape).background(ringColor),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = tone.dot)
            }
        } else {
            Box(
                modifier = corner
                    .size(18.dp)
                    .border(3.dp, ringColor, CircleShape)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(tone.dot),
            )
        }
    }
}

/** Battery and Wi-Fi, as the device itself reports them while connected. */
@Composable
private fun LiveReadings(device: PairedDeviceUi) {
    val battery = device.battery
    val onWifi = device.wifi?.let { it >= 0 } == true
    if (battery == null && !onWifi) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        if (battery != null) {
            Reading(
                icon = if (device.charging == true) VeyroIcons.BatteryCharging else VeyroIcons.Battery,
                text = if (device.charging == true) "$battery%, charging" else "$battery%",
            )
        }
        if (onWifi) Reading(icon = VeyroIcons.Wifi, text = "Wi-Fi")
    }
}

@Composable
private fun Reading(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Connect, Disconnect, or a spinner while a connect runs. Never offers what cannot work. */
@Composable
private fun ConnectionButton(device: PairedDeviceUi, onClick: () -> Unit, modifier: Modifier) {
    val sized = modifier.heightIn(min = 48.dp)
    when {
        device.status == ConnectionStatus.CONNECTING -> OutlinedButton(onClick = {}, enabled = false, modifier = sized) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Connecting", maxLines = 1)
        }
        device.isConnected -> OutlinedButton(onClick = onClick, modifier = sized) {
            ButtonContent(VeyroIcons.SyncDisabled, "Disconnect")
        }
        else -> Button(onClick = onClick, enabled = device.canToggleConnection, modifier = sized) {
            ButtonContent(VeyroIcons.Sync, "Connect")
        }
    }
}

@Composable
private fun ButtonContent(icon: ImageVector, label: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CardAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            // Two lines, so "Send folder" fits beside four other actions on a narrow phone.
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
