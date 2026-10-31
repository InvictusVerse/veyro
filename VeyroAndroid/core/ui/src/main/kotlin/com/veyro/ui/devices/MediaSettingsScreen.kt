package com.veyro.ui.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.veyro.ui.settings.GrantStatusRow
import com.veyro.ui.settings.SettingsViewModel

/**
 * The page behind "Share media with PC". It holds the media switch, the notification access that
 * the phone's list of media sessions depends on (with the route for sideloaded installs), and a
 * note on where the PC's own media shows up.
 */
@Composable
public fun MediaSettingsScreen(
    settingsViewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
) {
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Notification access is granted in system settings; look again on return.
    LifecycleResumeEffect(Unit) {
        settingsViewModel.refreshNotificationAccess()
        onPauseOrDispose { }
    }

    VeyroDetailScaffold(title = "Share media with PC", onNavigateBack = onNavigateBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SwitchPreferenceWidget(
                title = "Share media with PC",
                subtitle = "Show what is playing on this phone on a connected PC, which can then play, " +
                    "pause, skip, seek and set the volume.",
                checkedIcon = VeyroIcons.PlayCircleFill,
                uncheckedIcon = VeyroIcons.PlayCircle,
                checked = settings.mediaShareEnabled,
                onCheckedChanged = { on ->
                    settingsViewModel.setMediaShareEnabled(on)
                    if (on && !settings.notificationAccessGranted) openNotificationAccess(context)
                },
            )

            NotificationAccessRow(granted = settings.notificationAccessGranted)

            TextPreferenceWidget(
                title = "Media from the PC",
                subtitle = "While a PC is connected, what it is playing and its volume appear on Home.",
                icon = VeyroIcons.ComputerSound,
            )
        }
    }
}

/** The notification access status row, shared with Settings. Opens the system screen on tap. */
@Composable
internal fun NotificationAccessRow(granted: Boolean) {
    val context = LocalContext.current
    GrantStatusRow(
        ok = granted,
        title = "Notification access needed",
        titleWhenOk = "Notification access granted",
        summary = "Tap to allow Veyro in system settings. On Android 13 and later, an app installed " +
            "outside an app store may first need \"Allow restricted settings\" from its App info menu.",
        summaryWhenOk = "Tap to review it in system settings.",
        onClick = { openNotificationAccess(context) },
    )
}
