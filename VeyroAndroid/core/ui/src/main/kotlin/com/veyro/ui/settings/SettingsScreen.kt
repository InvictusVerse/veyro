package com.veyro.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroButton
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.components.VeyroDangerButton
import com.veyro.core.designsystem.components.VeyroGlassCard
import com.veyro.core.designsystem.components.VeyroOutlineButton
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanContainer
import com.veyro.core.designsystem.theme.VeyroCyanGlow
import com.veyro.core.designsystem.theme.VeyroCyanMuted
import com.veyro.core.designsystem.theme.VeyroExtras
import com.veyro.core.designsystem.theme.VeyroGlassBorder
import com.veyro.core.designsystem.theme.VeyroMetricSubStyle
import com.veyro.core.designsystem.theme.VeyroRed
import com.veyro.core.designsystem.theme.VeyroRedContainer
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTealContainer
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.designsystem.theme.VeyroViolet
import com.veyro.core.designsystem.theme.VeyroVioletContainer

/**
 * Settings screen — redesigned with a futuristic aesthetic.
 *
 * Sections:
 *   [Problem cards]     ← shown when identity/trust errors exist
 *   [Identity hero]     ← fingerprint display with dramatic presentation
 *   [Device name]       ← editable name
 *   [Discovery]         ← always-discoverable toggle
 *   [Notifications]     ← mirror-to-PC toggle and notification access row
 *   [Danger zone]       ← identity reset
 *   [Debug panel]       ← diagnostics + log tail
 */
@Composable
public fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showResetConfirm by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Notification access is granted in system settings, so re-check whenever the user returns.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshNotificationAccess()
        onPauseOrDispose { }
    }

    LaunchedEffect(showDebug) {
        if (showDebug) viewModel.refreshDiagnostics()
    }

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
        // Error cards
        state.identityError?.let { message ->
            item { ProblemCard(title = "Identity unavailable", message = message, icon = Icons.Default.Lock) }
        }
        state.trustStoreError?.let { message ->
            item { ProblemCard(title = "Paired devices could not be verified", message = message, icon = Icons.Default.Warning) }
        }

        // Identity hero card
        item {
            IdentityHeroCard(
                fingerprint = state.fingerprint,
                deviceId = state.deviceId,
                pairedCount = state.pairedDeviceCount,
            )
        }

        // Device name
        item {
            NameSection(
                currentName = state.deviceName,
                onSave = viewModel::setDeviceName,
            )
        }

        // Discovery toggle
        item {
            DiscoverySection(
                alwaysDiscoverable = state.alwaysDiscoverable,
                onToggle = viewModel::setAlwaysDiscoverable,
            )
        }

        // Notification mirroring (ADR-025)
        item {
            NotificationMirrorSection(
                enabled = state.notificationMirrorEnabled,
                accessGranted = state.notificationAccessGranted,
                onToggle = viewModel::setNotificationMirrorEnabled,
                onOpenAccessSettings = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (_: ActivityNotFoundException) {
                        // Some builds hide the screen; the row's subtitle still says what is missing.
                    }
                },
            )
        }

        // Danger zone
        item {
            DangerZoneSection(
                pairedCount = state.pairedDeviceCount,
                resetInProgress = state.resetInProgress,
                onResetClick = { showResetConfirm = true },
            )
        }

        // Debug panel
        item {
            DebugSection(
                showDebug = showDebug,
                onToggleDebug = { showDebug = it },
                diagnostics = state.diagnostics,
                logDump = state.logDump,
                onClearLog = viewModel::clearLog,
                onRefresh = viewModel::refreshDiagnostics,
            )
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    // Reset confirmation dialog
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Reset identity?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    "This device will get a new key pair. All ${state.pairedDeviceCount} paired device(s) will stop recognising it and must be paired again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VeyroTextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        viewModel.resetIdentity()
                    },
                ) {
                    Text("Reset identity", color = VeyroRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel", color = VeyroTextSecondary)
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

@Composable
private fun IdentityHeroCard(
    fingerprint: String,
    deviceId: String,
    pairedCount: Int,
) {
    VeyroCard(glow = true) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Shield icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(VeyroVioletContainer)
                    .border(1.dp, VeyroViolet.copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = VeyroViolet,
                    modifier = Modifier.size(36.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Device fingerprint",
                style = MaterialTheme.typography.labelMedium,
                color = VeyroTextSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Fingerprint display
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .border(0.5.dp, VeyroGlassBorder, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = fingerprint,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
                    ),
                    color = VeyroViolet,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Device ID
            Text(
                text = "ID: $deviceId",
                style = MaterialTheme.typography.labelSmall,
                color = VeyroTextSecondary,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = VeyroGlassBorder)
            Spacer(modifier = Modifier.height(12.dp))

            // Paired count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = VeyroTeal,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$pairedCount paired device${if (pairedCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = VeyroTeal,
                )
            }
        }
    }
}

@Composable
private fun NameSection(
    currentName: String,
    onSave: (String) -> Unit,
) {
    var editing by remember(currentName) { mutableStateOf(currentName) }
    val hasChanged = editing.isNotBlank() && editing != currentName

    VeyroGlassCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = VeyroCyanMuted,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "This device",
                    style = MaterialTheme.typography.labelMedium,
                    color = VeyroCyanMuted,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = editing,
                onValueChange = { editing = it },
                label = { Text("Device name") },
                supportingText = {
                    Text("Shown to other devices. Max 32 bytes.")
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VeyroCyan.copy(alpha = 0.6f),
                    unfocusedBorderColor = VeyroGlassBorder,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(10.dp))

            VeyroButton(
                text = "Save name",
                onClick = { onSave(editing) },
                enabled = hasChanged,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DiscoverySection(
    alwaysDiscoverable: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    VeyroGlassCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Always discoverable",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Off by default. When off, Veyro only sends packets while the app is open or a transfer is running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VeyroTextSecondary,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            ToggleSwitch(
                checked = alwaysDiscoverable,
                onCheckedChange = onToggle,
            )
        }
    }
}

@Composable
private fun NotificationMirrorSection(
    enabled: Boolean,
    accessGranted: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenAccessSettings: () -> Unit,
) {
    VeyroGlassCard {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Mirror notifications to PC",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Off by default. Shows this phone's notifications on a connected PC, " +
                            "where you can reply, run their actions or dismiss them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VeyroTextSecondary,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                ToggleSwitch(checked = enabled, onCheckedChange = onToggle)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = VeyroGlassBorder)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        onClickLabel = "Open notification access settings",
                        role = Role.Button,
                        onClick = onOpenAccessSettings,
                    )
                    .padding(vertical = 12.dp),
            ) {
                Icon(
                    imageVector = if (accessGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (accessGranted) VeyroTeal else VeyroRed.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (accessGranted) "Notification access granted" else "Notification access needed",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (accessGranted) {
                            "Tap to review it in system settings."
                        } else {
                            "Tap to allow Veyro in system settings. On Android 13 and later, an app " +
                                "installed outside an app store may first need \"Allow restricted " +
                                "settings\" from its App info menu."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = VeyroTextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DangerZoneSection(
    pairedCount: Int,
    resetInProgress: Boolean,
    onResetClick: () -> Unit,
) {
    VeyroGlassCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = VeyroRed.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Danger zone",
                    style = MaterialTheme.typography.labelMedium,
                    color = VeyroRed.copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Resetting your identity creates a new key pair. Every paired device will stop recognising this one and must be paired again. This cannot be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = VeyroTextSecondary,
            )

            Spacer(modifier = Modifier.height(12.dp))

            VeyroDangerButton(
                text = if (resetInProgress) "Resetting..." else "Reset identity",
                onClick = onResetClick,
                enabled = !resetInProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DebugSection(
    showDebug: Boolean,
    onToggleDebug: (Boolean) -> Unit,
    diagnostics: com.veyro.core.common.Diagnostics?,
    logDump: String,
    onClearLog: () -> Unit,
    onRefresh: () -> Unit,
) {
    VeyroGlassCard {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Build facts and recent log lines",
                        style = MaterialTheme.typography.bodySmall,
                        color = VeyroTextSecondary,
                    )
                }
                ToggleSwitch(checked = showDebug, onCheckedChange = onToggleDebug)
            }

            if (showDebug && diagnostics != null) {
                Spacer(modifier = Modifier.height(16.dp))

                diagnostics.sections.forEach { section ->
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    section.facts.forEach { fact ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = fact.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = VeyroTextSecondary,
                                modifier = Modifier.weight(0.4f),
                            )
                            Text(
                                text = fact.value,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (fact.isProblem) VeyroRed else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(0.6f),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                HorizontalDivider(color = VeyroGlassBorder)
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Recent log",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearLog) {
                        Text("Clear", color = VeyroTextSecondary)
                    }
                    TextButton(onClick = onRefresh) {
                        Text("Refresh", color = VeyroCyan)
                    }
                }

                Text(
                    text = logDump.ifEmpty { "(no log lines recorded)" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = VeyroTextSecondary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Problem card
// ---------------------------------------------------------------------------

@Composable
private fun ProblemCard(
    title: String,
    message: String,
    icon: ImageVector,
) {
    VeyroCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(VeyroRedContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = VeyroRed,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = VeyroTextSecondary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Toggle switch
// ---------------------------------------------------------------------------

@Composable
private fun ToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = VeyroCyan,
            checkedTrackColor = VeyroCyanContainer,
            uncheckedThumbColor = VeyroTextSecondary,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    )
}
