// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/devices/DeviceSettingsScreen.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.devices

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroDetailScaffold
import com.veyro.core.designsystem.components.padding
import com.veyro.core.designsystem.components.preference.SwitchPreferenceWidget
import com.veyro.core.designsystem.components.preference.TextPreferenceWidget
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.model.Platform
import com.veyro.core.model.fingerprint
import com.veyro.ui.components.DeviceAvatar
import com.veyro.ui.home.ConnectionStatus
import com.veyro.ui.home.HomeViewModel
import com.veyro.ui.home.PairedDeviceUi
import com.veyro.ui.settings.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One paired device: header with its state, then its settings.
 *
 * Veyro changes: rows map to Veyro's stores. Rename, fingerprint and auto-accept are per device
 * (trust store). Notifications, messages and calls, media and ring are the phone's companion
 * switches (settings store) and apply to every paired PC, which the section label says. Another
 * phone (ADR-052) gets only "Share my media", since files and media are all that work between
 * phones. Turning
 * one on asks for exactly what it still lacks, then the switch reflects the real grant, as in
 * Sefirah. Remove asks for confirmation before revoking trust.
 */
@Composable
public fun DeviceSettingsScreen(
    deviceId: String,
    homeViewModel: HomeViewModel,
    devicesViewModel: DevicesViewModel,
    settingsViewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onSendFiles: () -> Unit,
    onOpenMediaSettings: () -> Unit,
    onOpenMessagesSettings: () -> Unit,
    /** Asks the Activity to request the messaging runtime permissions (ADR-027). */
    onRequestMessagingPermissions: () -> Unit,
    /** Asks the Activity to request the photo read permission (ADR-034). */
    onRequestPhotosPermission: () -> Unit = {},
) {
    val trust by devicesViewModel.state.collectAsStateWithLifecycle()
    val home by homeViewModel.state.collectAsStateWithLifecycle()
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val peer = trust.devices.firstOrNull { it.deviceId.value == deviceId }
    val device = home.pairedDevices.firstOrNull { it.deviceId.value == deviceId }

    // Grants happen in system screens; re-read on every return.
    LifecycleResumeEffect(Unit) {
        settingsViewModel.refreshNotificationAccess()
        onPauseOrDispose { }
    }

    // Discovery while this screen is started, so the header's Connect has fresh endpoints.
    LifecycleStartEffect(Unit) {
        homeViewModel.acquireDiscovery()
        onStopOrDispose { homeViewModel.releaseDiscovery() }
    }

    // Revoked (here or elsewhere): nothing left to show.
    LaunchedEffect(trust.isLoading, peer) {
        if (!trust.isLoading && peer == null) onNavigateBack()
    }

    var showRename by rememberSaveable { mutableStateOf(false) }
    var showRemove by rememberSaveable { mutableStateOf(false) }

    VeyroDetailScaffold(
        title = "Device settings",
        onNavigateBack = onNavigateBack,
        actions = {
            IconButton(onClick = { showRemove = true }, enabled = peer != null) {
                Icon(VeyroIcons.Delete, contentDescription = "Remove device")
            }
        },
    ) { padding ->
        if (peer == null) return@VeyroDetailScaffold
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top,
        ) {
            item {
                DeviceHeader(
                    name = peer.name,
                    device = device,
                    platform = peer.platform,
                    pairedAt = peer.pairedAtEpochMillis,
                    lastConnected = peer.lastConnectedEpochMillis,
                    onToggleConnection = { homeViewModel.toggleConnection(peer.deviceId) },
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item {
                TextPreferenceWidget(
                    title = "Send files",
                    subtitle = "Pick files on this phone and send them to ${peer.name}.",
                    icon = VeyroIcons.Upload,
                    onPreferenceClick = onSendFiles,
                )
            }

            item {
                TextPreferenceWidget(
                    title = "Name",
                    subtitle = peer.name,
                    icon = VeyroIcons.Edit,
                    onPreferenceClick = { showRename = true },
                )
            }

            item {
                TextPreferenceWidget(
                    title = "Fingerprint",
                    subtitle = peer.deviceId.fingerprint(),
                    subtitleStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    icon = VeyroIcons.Lock,
                )
            }

            item {
                SwitchPreferenceWidget(
                    title = "Auto-accept files",
                    subtitle = "Files from this device are saved without asking. Turned on when you accept " +
                        "with \"Always accept\" ticked.",
                    icon = VeyroIcons.Download,
                    checked = peer.autoAcceptTransfers,
                    onCheckedChanged = { devicesViewModel.setAutoAccept(peer.deviceId, it) },
                )
            }

            if (peer.platform == Platform.ANDROID) {
                // ADR-052: between phones only files and media work, so only media has a switch.
                item { SectionLabel("Between phones") }

                item {
                    SwitchPreferenceWidget(
                        title = "Share my media",
                        subtitle = "Let ${peer.name} see what is playing on this phone and play, pause, skip, " +
                            "seek and set the volume. Applies to your PCs too." +
                            if (settings.notificationAccessGranted) "" else " Needs notification access.",
                        checkedIcon = VeyroIcons.PlayCircleFill,
                        uncheckedIcon = VeyroIcons.PlayCircle,
                        checked = settings.mediaShareEnabled && settings.notificationAccessGranted,
                        onCheckedChanged = { on ->
                            settingsViewModel.setMediaShareEnabled(on)
                            if (on && !settings.notificationAccessGranted) openNotificationAccess(context)
                        },
                        onContentClick = onOpenMediaSettings,
                    )
                }

                item {
                    Text(
                        text = "To control ${peer.name}'s media from here, turn on Share my media on ${peer.name} " +
                            "too. Notifications, messages, photos and the clipboard work with PCs only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            } else {
                item { SectionLabel("Phone features (all paired PCs)") }

                item {
                    SwitchPreferenceWidget(
                        title = "Notifications on PC",
                        subtitle = "Show this phone's notifications on a connected PC, where you can reply, " +
                            "run their actions or dismiss them." +
                            if (settings.notificationAccessGranted) "" else " Needs notification access.",
                        checkedIcon = VeyroIcons.NotificationsFill,
                        uncheckedIcon = VeyroIcons.Notifications,
                        checked = settings.notificationMirrorEnabled && settings.notificationAccessGranted,
                        onCheckedChanged = { on ->
                            settingsViewModel.setNotificationMirrorEnabled(on)
                            if (on && !settings.notificationAccessGranted) openNotificationAccess(context)
                        },
                    )
                }

                item {
                    val allGranted = settings.missingMessagingPermissions.isEmpty()
                    SwitchPreferenceWidget(
                        title = "Messages and calls on PC",
                        subtitle = "Read and send SMS, see and handle calls and read the call log from the PC. " +
                            "Call audio stays on the phone.",
                        checkedIcon = VeyroIcons.ChatFill,
                        uncheckedIcon = VeyroIcons.Chat,
                        checked = settings.messagesAndCallsEnabled && allGranted,
                        onCheckedChanged = { on ->
                            settingsViewModel.setMessagesAndCallsEnabled(on)
                            if (on && !allGranted) onRequestMessagingPermissions()
                        },
                        onContentClick = onOpenMessagesSettings,
                    )
                }

                item {
                    val granted = settings.photosPermissionGranted
                    SwitchPreferenceWidget(
                        title = "Photos on PC",
                        subtitle = "Show this phone's recent camera photos and screenshots on the PC, which " +
                            "can open, copy or save them." + if (granted) "" else " Needs photo access.",
                        icon = VeyroIcons.Image,
                        checked = settings.photosEnabled && granted,
                        onCheckedChanged = { on ->
                            settingsViewModel.setPhotosEnabled(on)
                            if (on && !granted) onRequestPhotosPermission()
                        },
                    )
                }

                item {
                    // ADR-038. On by default. Android 14+ gives the wallpaper image only to apps with
                    // "All files access"; without it the PC gets a picture in the wallpaper's colours.
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val exact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
                        android.os.Environment.isExternalStorageManager()
                    SwitchPreferenceWidget(
                        title = "Show my wallpaper on PC",
                        subtitle = if (exact) {
                            "The PC draws this phone with your wallpaper."
                        } else {
                            "The PC shows your wallpaper's colours. Android only lets apps read the picture " +
                                "itself with All files access: tap \"Show the exact wallpaper\" below."
                        },
                        icon = VeyroIcons.Mobile,
                        checked = settings.wallpaperShareEnabled,
                        onCheckedChanged = { on -> settingsViewModel.setWallpaperShareEnabled(on) },
                    )
                    if (!exact && settings.wallpaperShareEnabled) {
                        TextButton(
                            onClick = {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.fromParts("package", context.packageName, null),
                                )
                                runCatching { context.startActivity(intent) }
                            },
                            modifier = Modifier.padding(start = 56.dp),
                        ) { Text("Show the exact wallpaper") }
                    }
                }

                item {
                    SwitchPreferenceWidget(
                        title = "Share media with PC",
                        subtitle = "Show what is playing on this phone on the PC, which can then play, pause, " +
                            "skip, seek and set the volume.",
                        checkedIcon = VeyroIcons.PlayCircleFill,
                        uncheckedIcon = VeyroIcons.PlayCircle,
                        checked = settings.mediaShareEnabled && settings.notificationAccessGranted,
                        onCheckedChanged = { on ->
                            settingsViewModel.setMediaShareEnabled(on)
                            if (on && !settings.notificationAccessGranted) openNotificationAccess(context)
                        },
                        onContentClick = onOpenMediaSettings,
                    )
                }

                item {
                    SwitchPreferenceWidget(
                        title = "Ring and sound mode",
                        subtitle = "Let the PC ring this phone to find it, and switch its ringer mode or " +
                            "Do Not Disturb." +
                            if (settings.policyAccessGranted) "" else " Changing the sound mode needs Do Not Disturb access; tap to grant it.",
                        checkedIcon = VeyroIcons.VolumeUp,
                        uncheckedIcon = VeyroIcons.VolumeOff,
                        checked = settings.deviceControlAllowed,
                        onCheckedChanged = settingsViewModel::setDeviceControlAllowed,
                        onContentClick = { openPolicyAccess(context) },
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showRename && peer != null) {
        RenameDialog(
            currentName = peer.name,
            onDismiss = { showRename = false },
            onRename = {
                devicesViewModel.rename(peer.deviceId, it)
                showRename = false
            },
        )
    }

    if (showRemove && peer != null) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text("Remove ${peer.name}?") },
            text = {
                Text(
                    "This device will no longer be recognised and will need to be paired again. " +
                        "Transfers in progress are not affected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemove = false
                        devicesViewModel.revoke(peer.deviceId)
                        onNavigateBack()
                    },
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRemove = false }) { Text("Cancel") }
            },
        )
    }
}

/** A section heading in the primary colour, like the settings screens use. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = 20.dp,
            end = 20.dp,
            top = MaterialTheme.padding.medium,
            bottom = MaterialTheme.padding.small,
        ),
    )
}

@Composable
private fun DeviceHeader(
    name: String,
    device: PairedDeviceUi?,
    platform: com.veyro.core.model.Platform,
    pairedAt: Long,
    lastConnected: Long,
    onToggleConnection: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DeviceAvatar(
                platform = platform,
                size = 100,
                modifier = Modifier.border(width = 2.dp, color = MaterialTheme.colorScheme.primary, shape = CircleShape),
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            val status = device?.status ?: ConnectionStatus.OFFLINE
            when {
                status == ConnectionStatus.CONNECTED -> {
                    DeviceStatusInfo(
                        icon = VeyroIcons.Wifi,
                        text = "Connected" + (device?.battery?.let { " - battery $it%" + if (device.charging == true) ", charging" else "" } ?: ""),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                lastConnected > 0 -> DeviceStatusInfo(
                    icon = VeyroIcons.AccessTime,
                    text = "Last connected " + convertTimestampToDate(lastConnected),
                    color = MaterialTheme.colorScheme.outline,
                )
                else -> DeviceStatusInfo(
                    icon = VeyroIcons.AccessTime,
                    text = "Paired " + convertTimestampToDate(pairedAt),
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            if (device != null && device.status != ConnectionStatus.CONNECTING) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onToggleConnection, enabled = device.canToggleConnection) {
                    Text(
                        when {
                            device.isConnected -> "Disconnect"
                            device.canToggleConnection -> "Connect"
                            else -> "Not nearby"
                        },
                    )
                }
                if (device.disconnectedByUser) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "You disconnected it. It stays off until you connect it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceStatusInfo(
    icon: ImageVector,
    text: String,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Sefirah's rename dialog, shared by this screen and Settings. */
@Composable
internal fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    title: String = "Rename device",
    supportingText: String? = null,
) {
    var text by rememberSaveable(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                label = { Text("Device name") },
                onValueChange = { text = it },
                singleLine = true,
                supportingText = supportingText?.let { { Text(it) } },
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && text.trim() != currentName,
                onClick = { onRename(text.trim()) },
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

internal fun openNotificationAccess(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        // Some builds hide the screen; the row's subtitle still says what is missing.
    }
}

internal fun openPolicyAccess(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        // Some builds hide the screen; the row's subtitle still says what is missing.
    }
}

private fun convertTimestampToDate(timestamp: Long): String =
    SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date(timestamp))
