package com.veyro.ui.devices

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * The page behind "Messages and calls on PC" (ADR-027). It holds the switch, the runtime
 * permissions that are still missing (a tap asks for them), and the App info route for
 * permissions that were refused for good or are blocked as restricted settings.
 */
@Composable
public fun MessagesSettingsScreen(
    settingsViewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onRequestMessagingPermissions: () -> Unit,
) {
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permissions can be changed in App info; look again on return.
    LifecycleResumeEffect(Unit) {
        settingsViewModel.refreshNotificationAccess()
        onPauseOrDispose { }
    }

    val missing = settings.missingMessagingPermissions
    val allGranted = missing.isEmpty()

    VeyroDetailScaffold(title = "Messages and calls", onNavigateBack = onNavigateBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SwitchPreferenceWidget(
                title = "Messages and calls on PC",
                subtitle = "Lets a connected PC read and send this phone's SMS, see incoming calls, " +
                    "answer, decline or place them, and read the call log. Call audio stays on the phone.",
                checkedIcon = VeyroIcons.ChatFill,
                uncheckedIcon = VeyroIcons.Chat,
                checked = settings.messagesAndCallsEnabled,
                onCheckedChanged = { on ->
                    settingsViewModel.setMessagesAndCallsEnabled(on)
                    if (on && !allGranted) onRequestMessagingPermissions()
                },
            )

            TextPreferenceWidget(
                title = if (allGranted) "All permissions granted" else "Permissions missing",
                subtitle = if (allGranted) {
                    "SMS, phone, contacts and call log access are granted."
                } else {
                    "Missing: ${missing.joinToString(", ")}. Tap to grant them."
                },
                icon = if (allGranted) VeyroIcons.Check else VeyroIcons.Warning,
                iconTint = if (allGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                // Nothing to ask for once everything is granted.
                onPreferenceClick = if (allGranted) null else onRequestMessagingPermissions,
            )

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

/** Opens Veyro's App info page, where permissions can be changed by hand. */
internal fun openAppInfo(context: Context) {
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    try {
        context.startActivity(appInfo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing else to offer; the row text still explains the path.
    }
}
