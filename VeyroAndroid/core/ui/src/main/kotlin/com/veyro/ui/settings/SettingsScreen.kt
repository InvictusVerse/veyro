// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/settings/SettingsScreen.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.common.Diagnostics
import com.veyro.core.designsystem.components.padding
import com.veyro.core.designsystem.components.preference.SwitchPreferenceWidget
import com.veyro.core.designsystem.components.preference.TextPreferenceWidget
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.ui.devices.NotificationAccessRow
import com.veyro.ui.devices.RenameDialog
import com.veyro.ui.devices.openAppInfo
import com.veyro.ui.devices.openPolicyAccess

/**
 * Settings as a Sefirah-style preference list under the app logo.
 *
 * Veyro changes: the rows are Veyro's - device name, the identity fingerprint, paired-device count,
 * always-discoverable, the three system grants the companion features depend on, identity reset
 * (confirmed) and the diagnostics panel with the in-app log tail. Storage location, recycle bin,
 * action labels and "stop service" have no Veyro counterpart. The companion feature switches
 * themselves are on each device's settings screen, as in Sefirah.
 */
@Composable
public fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    /** Opens the Devices tab from the paired-device count row. */
    onOpenDevices: () -> Unit = {},
    /** Asks the Activity to request the messaging runtime permissions (ADR-027). */
    onRequestMessagingPermissions: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showDeviceNameDialog by rememberSaveable { mutableStateOf(false) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    var showDebug by rememberSaveable { mutableStateOf(false) }

    // Grants happen in system settings, so re-check whenever the user returns.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshNotificationAccess()
        onPauseOrDispose { }
    }

    LaunchedEffect(showDebug) {
        if (showDebug) viewModel.refreshDiagnostics()
    }

    if (state.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top,
    ) {
        item { LogoHeader() }

        state.identityError?.let { message ->
            item { ProblemCard(title = "Identity unavailable", message = message) }
        }
        state.trustStoreError?.let { message ->
            item { ProblemCard(title = "Paired devices could not be verified", message = message) }
        }

        item {
            TextPreferenceWidget(
                title = "Device name",
                subtitle = state.deviceName,
                icon = VeyroIcons.Mobile,
                onPreferenceClick = { showDeviceNameDialog = true },
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
                subtitle = "$count paired device${if (count != 1) "s" else ""}",
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
                onCheckedChanged = viewModel::setAlwaysDiscoverable,
            )
        }

        item { SectionLabel("Background") }

        item {
            SwitchPreferenceWidget(
                title = "Run in background",
                subtitle = "Keeps the connection to your PC open after you close Veyro, so " +
                    "notifications, photos and files keep working. A silent notification shows while it runs.",
                icon = VeyroIcons.Sync,
                checked = state.runInBackground,
                onCheckedChanged = viewModel::setRunInBackground,
            )
        }

        item {
            SwitchPreferenceWidget(
                title = "Start on boot",
                subtitle = "Reconnects to your PC after the phone restarts, without opening Veyro.",
                icon = VeyroIcons.RestartAlt,
                checked = state.startOnBoot && state.runInBackground,
                enabled = state.runInBackground,
                onCheckedChanged = viewModel::setStartOnBoot,
            )
        }

        item {
            TextPreferenceWidget(
                title = if (state.batteryOptimizationIgnored) "Battery optimisation is off for Veyro" else "Stay connected with the screen off",
                subtitle = if (state.batteryOptimizationIgnored) {
                    "Android lets Veyro keep the PC connection open. Tap to review it."
                } else {
                    "Some phones close background connections to save battery. Tap to let Veyro " +
                        "keep the PC connection open. Veyro does not poll; it only reconnects when the network changes."
                },
                icon = if (state.batteryOptimizationIgnored) VeyroIcons.Check else VeyroIcons.Battery,
                onPreferenceClick = { requestBatteryExemption(context, alreadyExempt = state.batteryOptimizationIgnored) },
            )
        }

        item { SectionLabel("Permissions") }

        item { NotificationAccessRow(granted = state.notificationAccessGranted) }

        item {
            TextPreferenceWidget(
                title = if (state.policyAccessGranted) "Do Not Disturb access granted" else "Do Not Disturb access needed",
                subtitle = if (state.policyAccessGranted) {
                    "Tap to review it in system settings."
                } else {
                    "Ringing works without it. Changing the ringer mode or Do Not Disturb from the PC needs it."
                },
                icon = if (state.policyAccessGranted) VeyroIcons.Check else VeyroIcons.Warning,
                iconTint = if (state.policyAccessGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                onPreferenceClick = { openPolicyAccess(context) },
            )
        }

        item {
            val missing = state.missingMessagingPermissions
            TextPreferenceWidget(
                title = if (missing.isEmpty()) "Messages and calls permissions granted" else "Messages and calls permissions missing",
                subtitle = if (missing.isEmpty()) {
                    "Tap to review them in App info."
                } else {
                    "Missing: ${missing.joinToString(", ")}. Tap to grant them."
                },
                icon = if (missing.isEmpty()) VeyroIcons.Check else VeyroIcons.Warning,
                iconTint = if (missing.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                onPreferenceClick = {
                    if (missing.isEmpty()) openAppInfo(context) else onRequestMessagingPermissions()
                },
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        item {
            TextPreferenceWidget(
                title = if (state.resetInProgress) "Resetting identity..." else "Reset identity",
                subtitle = "Creates a new key pair. Every paired device stops recognising this phone " +
                    "and must be paired again. This cannot be undone.",
                icon = VeyroIcons.RestartAlt,
                iconTint = MaterialTheme.colorScheme.error,
                onPreferenceClick = if (state.resetInProgress) null else ({ showResetConfirm = true }),
            )
        }

        item {
            SwitchPreferenceWidget(
                title = "Show diagnostics",
                subtitle = "Build facts and recent log lines",
                icon = VeyroIcons.BugReport,
                checked = showDebug,
                onCheckedChanged = { showDebug = it },
            )
        }

        if (showDebug) {
            item {
                DebugPanel(
                    diagnostics = state.diagnostics,
                    logDump = state.logDump,
                    onClearLog = viewModel::clearLog,
                    onRefresh = viewModel::refreshDiagnostics,
                )
            }
        }
    }

    if (showDeviceNameDialog) {
        RenameDialog(
            currentName = state.deviceName,
            title = "Rename this phone",
            supportingText = "Shown to other devices. Max 32 bytes.",
            onDismiss = { showDeviceNameDialog = false },
            onRename = {
                viewModel.setDeviceName(it)
                showDeviceNameDialog = false
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset identity?") },
            text = {
                Text(
                    "This device will get a new key pair. All ${state.pairedDeviceCount} paired device(s) " +
                        "will stop recognising it and must be paired again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        viewModel.resetIdentity()
                    },
                ) { Text("Reset identity", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Asks Android to exempt Veyro from battery optimisation, only from this explicit row (ADR-036).
 * Already exempt, or on a ROM without the direct request, it opens the system list instead.
 */
private fun requestBatteryExemption(context: Context, alreadyExempt: Boolean) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    for (intent in if (alreadyExempt) listOf(list) else listOf(direct, list)) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        } catch (_: SecurityException) {
        }
    }
}

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
private fun ProblemCard(title: String, message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
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

/** The diagnostics facts and the log tail, shown under the "Show diagnostics" switch. */
@Composable
private fun DebugPanel(
    diagnostics: Diagnostics?,
    logDump: String,
    onClearLog: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
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
