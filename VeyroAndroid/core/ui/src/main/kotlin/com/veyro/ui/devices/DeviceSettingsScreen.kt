package com.veyro.ui.devices

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import androidx.compose.foundation.lazy.LazyListScope
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
import com.veyro.core.designsystem.components.preference.SwitchPreferenceWidget
import com.veyro.core.designsystem.components.preference.TextPreferenceWidget
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.model.DeviceNames
import com.veyro.core.model.Platform
import com.veyro.core.model.TrustedPeer
import com.veyro.core.model.fingerprint
import com.veyro.ui.components.DeviceAvatar
import com.veyro.ui.home.ConnectionStatus
import com.veyro.ui.home.HomeViewModel
import com.veyro.ui.home.PairedDeviceUi
import com.veyro.ui.settings.SectionTitle
import com.veyro.ui.settings.SettingsUiState
import com.veyro.ui.settings.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Indent that lines a follow-up button up with the text of the switch row above it. */
private val FollowUpButtonIndent = 56.dp

/**
 * One paired device: a header with its connection state, then its settings.
 *
 * What a switch controls depends on where it is stored. Name, fingerprint and auto-accept belong
 * to this device (trust store). The notification, message, media, ring and remote-control
 * switches belong to this phone (settings store) and apply to every paired PC, which the section
 * title says. Another phone (ADR-052) gets only "Share my media", because files and media are all
 * that work between phones. Turning a switch on asks for exactly the grant it still lacks, and
 * the switch then shows the real grant state. Removing a device asks for confirmation first.
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
    val live = home.pairedDevices.firstOrNull { it.deviceId.value == deviceId }

    // Grants are changed in system screens, so read them again on every return.
    LifecycleResumeEffect(Unit) {
        settingsViewModel.refreshNotificationAccess()
        onPauseOrDispose { }
    }

    // Discovery runs while the screen is started, so the header's Connect has fresh endpoints.
    LifecycleStartEffect(Unit) {
        homeViewModel.acquireDiscovery()
        onStopOrDispose { homeViewModel.releaseDiscovery() }
    }

    // Revoked here or elsewhere: nothing is left to show.
    LaunchedEffect(trust.isLoading, peer) {
        if (!trust.isLoading && peer == null) onNavigateBack()
    }

    var renaming by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }

    VeyroDetailScaffold(
        title = "Device settings",
        onNavigateBack = onNavigateBack,
        actions = {
            IconButton(onClick = { removing = true }, enabled = peer != null) {
                Icon(VeyroIcons.Delete, contentDescription = "Remove device")
            }
        },
    ) { padding ->
        if (peer == null) return@VeyroDetailScaffold
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                DeviceHeader(
                    peer = peer,
                    live = live,
                    onToggleConnection = { homeViewModel.toggleConnection(peer.deviceId) },
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            deviceRows(
                peer = peer,
                onSendFiles = onSendFiles,
                onRename = { renaming = true },
                onAutoAccept = { devicesViewModel.setAutoAccept(peer.deviceId, it) },
            )

            if (peer.platform == Platform.ANDROID) {
                phoneToPhoneRows(
                    peerName = peer.displayName,
                    settings = settings,
                    viewModel = settingsViewModel,
                    context = context,
                    onOpenMediaSettings = onOpenMediaSettings,
                )
            } else {
                pcFeatureRows(
                    settings = settings,
                    viewModel = settingsViewModel,
                    context = context,
                    onOpenMediaSettings = onOpenMediaSettings,
                    onOpenMessagesSettings = onOpenMessagesSettings,
                    onRequestMessagingPermissions = onRequestMessagingPermissions,
                    onRequestPhotosPermission = onRequestPhotosPermission,
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (renaming && peer != null) {
        RenameDialog(
            currentName = peer.displayName,
            onDismiss = { renaming = false },
            onRename = {
                devicesViewModel.rename(peer.deviceId, it)
                renaming = false
            },
            supportingText = "Only this phone uses this name.",
            ownName = peer.name,
            onUseOwnName = if (peer.nickname != null) {
                {
                    devicesViewModel.useOwnName(peer.deviceId)
                    renaming = false
                }
            } else {
                null
            },
        )
    }

    if (removing && peer != null) {
        RemoveDeviceDialog(
            name = peer.displayName,
            onDismiss = { removing = false },
            onConfirm = {
                removing = false
                devicesViewModel.revoke(peer.deviceId)
                onNavigateBack()
            },
        )
    }
}

// --- Rows -----------------------------------------------------------------------------------

/** Rows that describe this one device: send files, name, fingerprint, auto-accept. */
private fun LazyListScope.deviceRows(
    peer: TrustedPeer,
    onSendFiles: () -> Unit,
    onRename: () -> Unit,
    onAutoAccept: (Boolean) -> Unit,
) {
    item {
        TextPreferenceWidget(
            title = "Send files",
            subtitle = "Pick files on this phone and send them to ${peer.displayName}.",
            icon = VeyroIcons.Upload,
            onPreferenceClick = onSendFiles,
        )
    }
    item {
        TextPreferenceWidget(
            title = "Name",
            // ADR-059: say so when the name is only this phone's nickname for the device.
            subtitle = if (peer.nickname != null) "${peer.displayName} (it calls itself ${peer.name})" else peer.displayName,
            icon = VeyroIcons.Edit,
            onPreferenceClick = onRename,
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
            onCheckedChanged = onAutoAccept,
        )
    }
}

/** ADR-052: between phones only files and media work, so media is the only switch. */
private fun LazyListScope.phoneToPhoneRows(
    peerName: String,
    settings: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    onOpenMediaSettings: () -> Unit,
) {
    item { SectionTitle("Between phones") }
    item {
        MediaShareSwitch(
            title = "Share my media",
            summary = "Let $peerName see what is playing on this phone and play, pause, skip, seek " +
                "and set the volume. Applies to your PCs too.",
            settings = settings,
            viewModel = viewModel,
            context = context,
            showMissingGrant = true,
            onOpenDetails = onOpenMediaSettings,
        )
    }
    item {
        Text(
            text = "To control $peerName's media from here, turn on Share my media on $peerName " +
                "too. Text and links you share with \"Send with Veyro\" arrive on $peerName as a " +
                "notification with Copy. Notifications, messages and photos work with PCs only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

/** Everything a paired PC may ask this phone for. Each switch is stored once and applies to all PCs. */
private fun LazyListScope.pcFeatureRows(
    settings: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    onOpenMediaSettings: () -> Unit,
    onOpenMessagesSettings: () -> Unit,
    onRequestMessagingPermissions: () -> Unit,
    onRequestPhotosPermission: () -> Unit,
) {
    item { SectionTitle("Phone features (all paired PCs)") }

    item {
        GrantedSwitch(
            title = "Notifications on PC",
            summary = "Show this phone's notifications on a connected PC, where you can reply, " +
                "run their actions or dismiss them.",
            missingGrant = "Needs notification access.",
            checkedIcon = VeyroIcons.NotificationsFill,
            uncheckedIcon = VeyroIcons.Notifications,
            wanted = settings.notificationMirrorEnabled,
            granted = settings.notificationAccessGranted,
            onWantedChange = viewModel::setNotificationMirrorEnabled,
            askForGrant = { openNotificationAccess(context) },
        )
    }

    item {
        GrantedSwitch(
            title = "Messages and calls on PC",
            summary = "Read and send SMS, see and handle calls and read the call log from the PC. " +
                "Call audio stays on the phone.",
            checkedIcon = VeyroIcons.ChatFill,
            uncheckedIcon = VeyroIcons.Chat,
            wanted = settings.messagesAndCallsEnabled,
            granted = settings.missingMessagingPermissions.isEmpty(),
            onWantedChange = viewModel::setMessagesAndCallsEnabled,
            askForGrant = onRequestMessagingPermissions,
            onOpenDetails = onOpenMessagesSettings,
        )
    }

    item {
        GrantedSwitch(
            title = "Photos on PC",
            summary = "Show this phone's recent camera photos and screenshots on the PC, which " +
                "can open, copy or save them.",
            missingGrant = "Needs photo access.",
            icon = VeyroIcons.Image,
            wanted = settings.photosEnabled,
            granted = settings.photosPermissionGranted,
            onWantedChange = viewModel::setPhotosEnabled,
            askForGrant = onRequestPhotosPermission,
        )
    }

    item { WallpaperShareRows(settings, viewModel, context) }

    item {
        MediaShareSwitch(
            title = "Share media with PC",
            summary = "Show what is playing on this phone on the PC, which can then play, pause, " +
                "skip, seek and set the volume.",
            settings = settings,
            viewModel = viewModel,
            context = context,
            showMissingGrant = false,
            onOpenDetails = onOpenMediaSettings,
        )
    }

    // Plain permission switches. The PC sets quality and lens; these only allow the request.
    val allowSwitches = listOf(
        // ADR-066: Android asks again at the start of every session.
        AllowSwitch(
            title = "Let PCs mirror this screen",
            summary = "A PC can ask to show this phone's screen, and its sound if you allow it. " +
                "Android asks you each time; nothing is shared until you tap Start now.",
            icon = { VeyroIcons.Desktop },
            checked = settings.screenShareAllowed,
            onChange = viewModel::setScreenShareAllowed,
        ),
        // ADR-067: the PC picks lens and quality; the phone asks for a tap every time.
        AllowSwitch(
            title = "Let PCs use this phone's camera",
            summary = "A PC can ask to use this phone as its webcam. You tap Allow on the phone each " +
                "time, and a notification with Stop shows while it is in use.",
            icon = { VeyroIcons.Image },
            checked = settings.cameraShareAllowed,
            onChange = viewModel::setCameraShareAllowed,
        ),
        // ADR-073: the PC starts its sound; a notification with Stop shows while it plays.
        AllowSwitch(
            title = "Play PC sound on this phone",
            summary = "A PC can play its sound through this phone's speaker or headphones. " +
                "A notification with Stop shows while it plays.",
            icon = { VeyroIcons.MusicNote },
            checked = settings.pcAudioAllowed,
            onChange = viewModel::setPcAudioAllowed,
        ),
        // ADR-073: every photo or scan needs a tap on the phone.
        AllowSwitch(
            title = "Take photos and scans for PCs",
            summary = "A PC can ask for a photo or a document scan. You take it on the phone; " +
                "it goes to the PC's clipboard and Downloads.",
            icon = { VeyroIcons.Image },
            checked = settings.photoCaptureAllowed,
            onChange = viewModel::setPhotoCaptureAllowed,
        ),
    )
    items(allowSwitches.size) { index ->
        val entry = allowSwitches[index]
        SwitchPreferenceWidget(
            title = entry.title,
            subtitle = entry.summary,
            icon = entry.icon(),
            checked = entry.checked,
            onCheckedChanged = entry.onChange,
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
            onCheckedChanged = viewModel::setDeviceControlAllowed,
            onContentClick = { openPolicyAccess(context) },
        )
    }

    item {
        // ADR-060: its own switch, off by default, so allowing ring and sound mode never also
        // allows these. Brightness needs one more grant, which the follow-up button asks for.
        Column {
            SwitchPreferenceWidget(
                title = "Let PCs control flashlight, volume and brightness",
                subtitle = "A connected PC can turn the flashlight on or off and set the media volume " +
                    "and the screen brightness." +
                    if (settings.writeSettingsGranted) "" else " Brightness also needs \"Modify system settings\".",
                icon = VeyroIcons.SettingsFill,
                checked = settings.phoneControlsAllowed,
                onCheckedChanged = viewModel::setPhoneControlsAllowed,
            )
            if (settings.phoneControlsAllowed && !settings.writeSettingsGranted) {
                TextButton(
                    onClick = { openWriteSettingsAccess(context) },
                    modifier = Modifier.padding(start = FollowUpButtonIndent),
                ) { Text("Allow brightness changes") }
            }
        }
    }
}

/** A switch that only needs a title, a line of text, an icon and a stored flag. */
private class AllowSwitch(
    val title: String,
    val summary: String,
    val icon: @Composable () -> ImageVector,
    val checked: Boolean,
    val onChange: (Boolean) -> Unit,
)

/**
 * A switch for a feature that also needs a system grant. It shows on only when the user wants it
 * and the grant is held, and turning it on asks for the grant when it is missing. [missingGrant]
 * is appended to the summary while the grant is absent. With [onOpenDetails] the row splits into
 * a text part that opens a detail screen and the switch.
 */
@Composable
private fun GrantedSwitch(
    title: String,
    summary: String,
    wanted: Boolean,
    granted: Boolean,
    onWantedChange: (Boolean) -> Unit,
    askForGrant: () -> Unit,
    missingGrant: String? = null,
    icon: ImageVector? = null,
    checkedIcon: ImageVector? = null,
    uncheckedIcon: ImageVector? = null,
    onOpenDetails: (() -> Unit)? = null,
) {
    SwitchPreferenceWidget(
        title = title,
        subtitle = if (granted || missingGrant == null) summary else "$summary $missingGrant",
        icon = icon,
        checkedIcon = checkedIcon,
        uncheckedIcon = uncheckedIcon,
        checked = wanted && granted,
        onCheckedChanged = { on ->
            onWantedChange(on)
            if (on && !granted) askForGrant()
        },
        onContentClick = onOpenDetails,
    )
}

/** The media switch, which is the same stored flag whether it is shown for a PC or a phone. */
@Composable
private fun MediaShareSwitch(
    title: String,
    summary: String,
    settings: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    showMissingGrant: Boolean,
    onOpenDetails: () -> Unit,
) {
    GrantedSwitch(
        title = title,
        summary = summary,
        missingGrant = if (showMissingGrant) "Needs notification access." else null,
        checkedIcon = VeyroIcons.PlayCircleFill,
        uncheckedIcon = VeyroIcons.PlayCircle,
        wanted = settings.mediaShareEnabled,
        granted = settings.notificationAccessGranted,
        onWantedChange = viewModel::setMediaShareEnabled,
        askForGrant = { openNotificationAccess(context) },
        onOpenDetails = onOpenDetails,
    )
}

/**
 * ADR-038: on by default. From Android 11 the wallpaper image itself is only readable with "All
 * files access"; without it the PC gets a picture in the wallpaper's colours, and a follow-up
 * button offers the exact one.
 */
@Composable
private fun WallpaperShareRows(settings: SettingsUiState, viewModel: SettingsViewModel, context: Context) {
    val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    Column {
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
            onCheckedChanged = viewModel::setWallpaperShareEnabled,
        )
        if (!exact && settings.wallpaperShareEnabled) {
            TextButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", context.packageName, null),
                    )
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.padding(start = FollowUpButtonIndent),
            ) { Text("Show the exact wallpaper") }
        }
    }
}

// --- Header ---------------------------------------------------------------------------------

/** What the single status line under the device name says, and how it is drawn. */
private class StatusLine(val icon: ImageVector, val text: String, val tint: Color)

@Composable
private fun statusLineFor(peer: TrustedPeer, live: PairedDeviceUi?): StatusLine {
    val colors = MaterialTheme.colorScheme
    return when {
        live != null && live.status == ConnectionStatus.CONNECTED -> {
            val battery = live.battery?.let { level ->
                " - battery $level%" + if (live.charging == true) ", charging" else ""
            }.orEmpty()
            StatusLine(VeyroIcons.Wifi, "Connected$battery", colors.primary)
        }
        peer.lastConnectedEpochMillis > 0 ->
            StatusLine(VeyroIcons.AccessTime, "Last connected " + formatDateTime(peer.lastConnectedEpochMillis), colors.outline)
        else ->
            StatusLine(VeyroIcons.AccessTime, "Paired " + formatDateTime(peer.pairedAtEpochMillis), colors.outline)
    }
}

@Composable
private fun DeviceHeader(peer: TrustedPeer, live: PairedDeviceUi?, onToggleConnection: () -> Unit) {
    val status = statusLineFor(peer, live)
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
                platform = peer.platform,
                size = 100,
                modifier = Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = peer.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            StatusLineRow(status)

            // No button while a connection attempt is running.
            if (live != null && live.status != ConnectionStatus.CONNECTING) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onToggleConnection, enabled = live.canToggleConnection) {
                    Text(
                        when {
                            live.isConnected -> "Disconnect"
                            live.canToggleConnection -> "Connect"
                            else -> "Not nearby"
                        },
                    )
                }
                if (live.disconnectedByUser) {
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
private fun StatusLineRow(line: StatusLine) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(imageVector = line.icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = line.tint)
        Spacer(Modifier.width(4.dp))
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodySmall,
            color = line.tint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- Dialog and system-screen helpers (shared with Settings and the detail screens) ----------

/** Renames a device or this phone; also offers going back to a device's own name (ADR-059). */
@Composable
internal fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    title: String = "Rename device",
    supportingText: String? = null,
    /**
     * For another device: the name it gives itself. With [onUseOwnName] set (it has a nickname
     * here), the dialog offers to go back to that name.
     */
    ownName: String? = null,
    onUseOwnName: (() -> Unit)? = null,
) {
    var text by rememberSaveable(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    label = { Text("Device name") },
                    onValueChange = { text = it.take(DeviceNames.MAX_CHARS) },
                    singleLine = true,
                    supportingText = supportingText?.let { { Text(it) } },
                )
                if (onUseOwnName != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onUseOwnName) { Text("Use the device's own name") }
                    if (!ownName.isNullOrBlank()) {
                        Text(
                            text = "It calls itself $ownName.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = DeviceNames.clean(text) != null && text.trim() != currentName,
                onClick = { onRename(text.trim()) },
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Opens a system screen; a ROM that hides it just leaves the row's own text to explain. */
private fun startSystemScreen(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // The row's summary still says what is missing.
    }
}

internal fun openNotificationAccess(context: Context) =
    startSystemScreen(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

/** "Modify system settings" for Veyro, which brightness from the PC needs (ADR-060). */
internal fun openWriteSettingsAccess(context: Context) =
    startSystemScreen(context, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", context.packageName, null)))

internal fun openPolicyAccess(context: Context) =
    startSystemScreen(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))

private fun formatDateTime(epochMillis: Long): String =
    SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date(epochMillis))
