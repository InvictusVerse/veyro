package com.veyro.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.common.Diagnostics
import com.veyro.core.data.SettingsStore
import com.veyro.core.designsystem.components.preference.SwitchPreferenceWidget
import com.veyro.core.designsystem.components.preference.TextPreferenceWidget
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.ui.devices.NotificationAccessRow
import com.veyro.ui.devices.ProblemBanner
import com.veyro.ui.devices.RenameDialog
import com.veyro.ui.devices.openAppInfo
import com.veyro.ui.devices.openPolicyAccess

/** Horizontal inset shared by section titles and the cards between the rows. */
private val ContentInset = 20.dp

/**
 * The Settings tab: the app logo, then rows in groups. Identity and pairing facts come first,
 * followed by where received files go, background behaviour, notification clean-up, the system
 * grants the companion features lean on, identity reset and diagnostics. The per-feature
 * companion switches live on each device's own settings screen.
 */
@Composable
public fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    /** Opens the Devices tab from the paired-device count row. */
    onOpenDevices: () -> Unit = {},
    /** Asks the Activity to request the messaging runtime permissions (ADR-027). */
    onRequestMessagingPermissions: () -> Unit = {},
    /** The "Keep Veyro connected" checklist (ADR-061). */
    onOpenKeepConnected: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var renamingPhone by rememberSaveable { mutableStateOf(false) }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    var pickingAutoClear by rememberSaveable { mutableStateOf(false) }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }

    // ADR-072: the system folder picker. The grant outlives the process; the grant it replaces is
    // handed back. Nothing else on the phone becomes readable to Veyro.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null || !ReceiveFolderGrants.take(context, tree)) return@rememberLauncherForActivityResult
        val replaced = state.receiveFolderUri
        if (replaced != null && replaced != tree.toString()) ReceiveFolderGrants.release(context, replaced)
        viewModel.setReceiveFolder(tree.toString(), ReceiveFolderGrants.nameOf(context, tree))
    }

    // System grants change in system screens, so look again each time the user comes back.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshNotificationAccess()
        onPauseOrDispose { }
    }

    LaunchedEffect(diagnosticsOpen) {
        if (diagnosticsOpen) viewModel.refreshDiagnostics()
    }

    if (state.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item { LogoHeader() }

        loadProblems(state)

        phoneSection(
            state = state,
            onRename = { renamingPhone = true },
            onOpenDevices = onOpenDevices,
            onAlwaysDiscoverable = viewModel::setAlwaysDiscoverable,
        )

        receivedFilesSection(
            state = state,
            onChooseFolder = { folderPicker.launch(null) },
            onUseDefaultFolder = {
                ReceiveFolderGrants.release(context, state.receiveFolderUri)
                viewModel.useDefaultReceiveFolder()
            },
        )

        backgroundSection(
            state = state,
            onOpenKeepConnected = onOpenKeepConnected,
            onRunInBackground = viewModel::setRunInBackground,
            onStartOnBoot = viewModel::setStartOnBoot,
            onBatteryExemption = {
                openBatteryExemption(context, alreadyExempt = state.batteryOptimizationIgnored)
            },
        )

        notificationCleanupSection(
            state = state,
            onAutoClear = viewModel::setNotificationAutoClear,
            onPickDelay = { pickingAutoClear = true },
        )

        grantsSection(
            state = state,
            onPolicyAccess = { openPolicyAccess(context) },
            onMessagingPermissions = {
                if (state.missingMessagingPermissions.isEmpty()) openAppInfo(context) else onRequestMessagingPermissions()
            },
        )

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        maintenanceSection(
            state = state,
            diagnosticsOpen = diagnosticsOpen,
            onToggleDiagnostics = { diagnosticsOpen = it },
            onReset = { confirmingReset = true },
            onClearLog = viewModel::clearLog,
            onRefreshDiagnostics = viewModel::refreshDiagnostics,
        )
    }

    if (renamingPhone) {
        RenameDialog(
            currentName = state.deviceName,
            title = "Rename this phone",
            supportingText = "Shown to other devices. Max 32 bytes.",
            onDismiss = { renamingPhone = false },
            onRename = {
                viewModel.setDeviceName(it)
                renamingPhone = false
            },
        )
    }

    if (pickingAutoClear) {
        AutoClearDelayDialog(
            selectedMinutes = state.notificationAutoClearMinutes,
            onDismiss = { pickingAutoClear = false },
            onPick = {
                pickingAutoClear = false
                viewModel.setNotificationAutoClearMinutes(it)
            },
        )
    }

    if (confirmingReset) {
        ResetIdentityDialog(
            pairedCount = state.pairedDeviceCount,
            onDismiss = { confirmingReset = false },
            onConfirm = {
                confirmingReset = false
                viewModel.resetIdentity()
            },
        )
    }
}

// --- Sections -------------------------------------------------------------------------------

/** Identity and trust-store failures come first: they explain an otherwise empty-looking screen. */
private fun LazyListScope.loadProblems(state: SettingsUiState) {
    state.identityError?.let { item { ProblemBanner("Identity unavailable", it) } }
    state.trustStoreError?.let { item { ProblemBanner("Paired devices could not be verified", it) } }
}

private fun LazyListScope.phoneSection(
    state: SettingsUiState,
    onRename: () -> Unit,
    onOpenDevices: () -> Unit,
    onAlwaysDiscoverable: (Boolean) -> Unit,
) {
    item {
        TextPreferenceWidget(
            title = "Device name",
            subtitle = state.deviceName,
            icon = VeyroIcons.Mobile,
            onPreferenceClick = onRename,
        )
    }
    item {
        TextPreferenceWidget(
            title = "Device fingerprint",
            subtitle = state.fingerprint + "\nID: " + state.deviceId,
            subtitleStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            icon = VeyroIcons.Lock,
        )
    }
    item {
        val count = state.pairedDeviceCount
        TextPreferenceWidget(
            title = "Paired devices",
            subtitle = if (count == 1) "1 paired device" else "$count paired devices",
            icon = VeyroIcons.Desktop,
            onPreferenceClick = onOpenDevices,
        )
    }
    item {
        SwitchPreferenceWidget(
            title = "Always discoverable",
            subtitle = "Off by default. When off, Veyro only sends packets while the app is open or a transfer is running.",
            icon = VeyroIcons.Wifi,
            checked = state.alwaysDiscoverable,
            onCheckedChanged = onAlwaysDiscoverable,
        )
    }
}

/** ADR-072: Download/Veyro unless the user picks a folder. */
private fun LazyListScope.receivedFilesSection(
    state: SettingsUiState,
    onChooseFolder: () -> Unit,
    onUseDefaultFolder: () -> Unit,
) {
    item { SectionTitle("Received files") }
    state.receiveFolderNotice?.let { item { ProblemBanner("Folder not available", it) } }
    item {
        TextPreferenceWidget(
            title = "Save received files to",
            subtitle = state.receiveFolderLabel,
            icon = VeyroIcons.Folder,
            onPreferenceClick = onChooseFolder,
            widget = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.receiveFolderUri != null) {
                        TextButton(onClick = onUseDefaultFolder) { Text("Use default") }
                    }
                    TextButton(onClick = onChooseFolder) { Text("Change") }
                }
            },
        )
    }
}

private fun LazyListScope.backgroundSection(
    state: SettingsUiState,
    onOpenKeepConnected: () -> Unit,
    onRunInBackground: (Boolean) -> Unit,
    onStartOnBoot: (Boolean) -> Unit,
    onBatteryExemption: () -> Unit,
) {
    item { SectionTitle("Background") }
    item {
        val issues = state.keepConnectedIssues
        GrantStatusRow(
            ok = issues == 0,
            title = "Keep Veyro connected",
            titleWhenOk = "Keep Veyro connected",
            summary = when (issues) {
                1 -> "1 setting can close the connection in the background. Tap to fix it."
                else -> "$issues settings can close the connection in the background. Tap to fix them."
            },
            summaryWhenOk = "Battery, autostart and permission settings that keep the connection open in the background.",
            onClick = onOpenKeepConnected,
        )
    }
    item {
        SwitchPreferenceWidget(
            title = "Run in background",
            subtitle = "Keeps the connection to your PC open after you close Veyro, so " +
                "notifications, photos and files keep working. A silent notification shows while it runs.",
            icon = VeyroIcons.Sync,
            checked = state.runInBackground,
            onCheckedChanged = onRunInBackground,
        )
    }
    item {
        SwitchPreferenceWidget(
            title = "Start on boot",
            subtitle = "Reconnects to your PC after the phone restarts, without opening Veyro.",
            icon = VeyroIcons.RestartAlt,
            checked = state.startOnBoot && state.runInBackground,
            enabled = state.runInBackground,
            onCheckedChanged = onStartOnBoot,
        )
    }
    item {
        val exempt = state.batteryOptimizationIgnored
        TextPreferenceWidget(
            title = if (exempt) "Battery optimisation is off for Veyro" else "Stay connected with the screen off",
            subtitle = if (exempt) {
                "Android lets Veyro keep the PC connection open. Tap to review it."
            } else {
                "Some phones close background connections to save battery. Tap to let Veyro " +
                    "keep the PC connection open. Veyro does not poll; it only reconnects when the network changes."
            },
            icon = if (exempt) VeyroIcons.Check else VeyroIcons.Battery,
            onPreferenceClick = onBatteryExemption,
        )
    }
}

private fun LazyListScope.notificationCleanupSection(
    state: SettingsUiState,
    onAutoClear: (Boolean) -> Unit,
    onPickDelay: () -> Unit,
) {
    item { SectionTitle("Notifications") }
    item {
        SwitchPreferenceWidget(
            title = "Clear notifications automatically",
            subtitle = "Clipboard, sent and received file notifications disappear on their own. " +
                "Progress stays while a transfer runs.",
            icon = VeyroIcons.Notifications,
            checked = state.notificationAutoClear,
            onCheckedChanged = onAutoClear,
        )
    }
    item {
        TextPreferenceWidget(
            title = "Clear after",
            subtitle = if (state.notificationAutoClear) {
                delayLabel(state.notificationAutoClearMinutes)
            } else {
                "Off: they stay until you dismiss them"
            },
            icon = VeyroIcons.AccessTime,
            onPreferenceClick = if (state.notificationAutoClear) onPickDelay else null,
        )
    }
}

private fun LazyListScope.grantsSection(
    state: SettingsUiState,
    onPolicyAccess: () -> Unit,
    onMessagingPermissions: () -> Unit,
) {
    item { SectionTitle("Permissions") }
    item { NotificationAccessRow(granted = state.notificationAccessGranted) }
    item {
        GrantStatusRow(
            ok = state.policyAccessGranted,
            title = "Do Not Disturb access needed",
            titleWhenOk = "Do Not Disturb access granted",
            summary = "Ringing works without it. Changing the ringer mode or Do Not Disturb from the PC needs it.",
            summaryWhenOk = "Tap to review it in system settings.",
            onClick = onPolicyAccess,
        )
    }
    item {
        val missing = state.missingMessagingPermissions
        GrantStatusRow(
            ok = missing.isEmpty(),
            title = "Messages and calls permissions missing",
            titleWhenOk = "Messages and calls permissions granted",
            summary = "Missing: ${missing.joinToString(", ")}. Tap to grant them.",
            summaryWhenOk = "Tap to review them in App info.",
            onClick = onMessagingPermissions,
        )
    }
}

private fun LazyListScope.maintenanceSection(
    state: SettingsUiState,
    diagnosticsOpen: Boolean,
    onToggleDiagnostics: (Boolean) -> Unit,
    onReset: () -> Unit,
    onClearLog: () -> Unit,
    onRefreshDiagnostics: () -> Unit,
) {
    item {
        TextPreferenceWidget(
            title = if (state.resetInProgress) "Resetting identity..." else "Reset identity",
            subtitle = "Creates a new key pair. Every paired device stops recognising this phone " +
                "and must be paired again. This cannot be undone.",
            icon = VeyroIcons.RestartAlt,
            iconTint = MaterialTheme.colorScheme.error,
            onPreferenceClick = if (state.resetInProgress) null else onReset,
        )
    }
    item {
        SwitchPreferenceWidget(
            title = "Show diagnostics",
            subtitle = "Build facts and recent log lines",
            icon = VeyroIcons.BugReport,
            checked = diagnosticsOpen,
            onCheckedChanged = onToggleDiagnostics,
        )
    }
    if (diagnosticsOpen) {
        item {
            DiagnosticsCard(
                diagnostics = state.diagnostics,
                logDump = state.logDump,
                onClearLog = onClearLog,
                onRefresh = onRefreshDiagnostics,
            )
        }
    }
}

// --- Shared pieces --------------------------------------------------------------------------

/** A group title in the accent colour; announced as a heading by TalkBack. */
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .semantics { heading() }
            .padding(start = ContentInset, end = ContentInset, top = 16.dp, bottom = 8.dp),
    )
}

/**
 * A row that reports whether something is in order: a check mark in the accent colour when [ok],
 * a warning in the error colour otherwise, each with its own wording.
 */
@Composable
internal fun GrantStatusRow(
    ok: Boolean,
    title: String,
    titleWhenOk: String,
    summary: String,
    summaryWhenOk: String,
    onClick: () -> Unit,
) {
    TextPreferenceWidget(
        title = if (ok) titleWhenOk else title,
        subtitle = if (ok) summaryWhenOk else summary,
        icon = if (ok) VeyroIcons.Check else VeyroIcons.Warning,
        iconTint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        onPreferenceClick = onClick,
    )
}

/** "After 1 minute", "After 5 minutes", "After 2 hours". */
private fun delayLabel(minutes: Int): String = when {
    minutes == 1 -> "After 1 minute"
    minutes == 60 -> "After 1 hour"
    minutes > 60 && minutes % 60 == 0 -> "After ${minutes / 60} hours"
    else -> "After $minutes minutes"
}

/** The "Clear after" choice. Each row is one radio option for TalkBack. */
@Composable
private fun AutoClearDelayDialog(selectedMinutes: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear notifications after") },
        text = {
            Column(Modifier.selectableGroup()) {
                SettingsStore.AUTO_CLEAR_CHOICES.forEach { minutes ->
                    val chosen = minutes == selectedMinutes
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = chosen, onClick = { onPick(minutes) }, role = Role.RadioButton)
                            .padding(horizontal = 4.dp),
                    ) {
                        // The whole row carries the semantics, so the button itself is inert.
                        RadioButton(selected = chosen, onClick = null)
                        Text(delayLabel(minutes).removePrefix("After "), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ResetIdentityDialog(pairedCount: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset identity?") },
        text = {
            Text(
                "This device will get a new key pair. All $pairedCount paired device(s) " +
                    "will stop recognising it and must be paired again.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Reset identity", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Asks Android to exempt Veyro from battery optimisation, only ever from this explicit row
 * (ADR-036). When already exempt, or on a ROM without the direct request, it opens the system
 * list instead.
 */
private fun openBatteryExemption(context: Context, alreadyExempt: Boolean) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    val systemList = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val attempts = if (alreadyExempt) listOf(systemList) else listOf(direct, systemList)
    for (intent in attempts) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Fall through to the next intent.
        } catch (_: SecurityException) {
            // Same: some ROMs refuse the direct request.
        }
    }
}

/** Build facts, then the log tail, under the "Show diagnostics" switch. */
@Composable
private fun DiagnosticsCard(
    diagnostics: Diagnostics?,
    logDump: String,
    onClearLog: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ContentInset, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            diagnostics?.sections?.forEach { section ->
                Text(section.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                section.facts.forEach { fact ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            text = fact.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.4f),
                        )
                        Text(
                            text = fact.value,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (fact.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(0.6f),
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearLog) { Text("Clear") }
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
            Text(
                text = logDump.ifEmpty { "(no log lines recorded)" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
