// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/devices/SmsSettingsScreen.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.devices

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroDetailScaffold
import com.veyro.core.designsystem.components.preference.SwitchPreferenceWidget
import com.veyro.core.designsystem.components.preference.TextPreferenceWidget
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.ui.settings.SettingsViewModel

/**
 * Detail screen behind "Messages and calls on PC" (ADR-027).
 *
 * Veyro changes: Sefirah's MMS conversion options do not exist in Veyro. The screen holds the
 * switch, which runtime permissions are still missing (asked for on tap), and the App info route
 * for permissions that were denied permanently or are blocked as restricted settings.
 */
@Composable
public fun MessagesSettingsScreen(
    settingsViewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onRequestMessagingPermissions: () -> Unit,
) {
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        settingsViewModel.refreshNotificationAccess()
        onPauseOrDispose { }
    }

    val missing = settings.missingMessagingPermissions

    VeyroDetailScaffold(title = "Messages and calls", onNavigateBack = onNavigateBack) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
                SwitchPreferenceWidget(
                    title = "Messages and calls on PC",
                    subtitle = "Lets a connected PC read and send this phone's SMS, see incoming calls, " +
                        "answer, decline or place them, and read the call log. Call audio stays on the phone.",
                    checkedIcon = VeyroIcons.ChatFill,
                    uncheckedIcon = VeyroIcons.Chat,
                    checked = settings.messagesAndCallsEnabled,
                    onCheckedChanged = { on ->
                        settingsViewModel.setMessagesAndCallsEnabled(on)
                        if (on && missing.isNotEmpty()) onRequestMessagingPermissions()
                    },
                )
            }

            item {
                TextPreferenceWidget(
                    title = if (missing.isEmpty()) "All permissions granted" else "Permissions missing",
                    subtitle = if (missing.isEmpty()) {
                        "SMS, phone, contacts and call log access are granted."
                    } else {
                        "Missing: ${missing.joinToString(", ")}. Tap to grant them."
                    },
                    icon = if (missing.isEmpty()) VeyroIcons.Check else VeyroIcons.Warning,
                    iconTint = if (missing.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    onPreferenceClick = if (missing.isEmpty()) null else onRequestMessagingPermissions,
                )
            }

            item {
                TextPreferenceWidget(
                    title = "App permissions",
                    subtitle = "If Android no longer asks, grant them in App info. On Android 13 and later, " +
                        "an app installed outside an app store may first need \"Allow restricted settings\" " +
                        "from the App info menu.",
                    icon = VeyroIcons.SettingsAlert,
                    onPreferenceClick = { openAppInfo(context) },
                )
            }
        }
    }
}

internal fun openAppInfo(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        // Nothing else to offer; the row text still explains the path.
    }
}
