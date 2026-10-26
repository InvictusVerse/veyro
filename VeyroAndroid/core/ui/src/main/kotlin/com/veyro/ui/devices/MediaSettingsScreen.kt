// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/devices/MediaSessionSettingsScreen.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.devices

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
 * Detail screen behind "Share media with PC".
 *
 * Veyro changes: Sefirah's media-notification and remote-volume switches have no Veyro
 * counterpart; this screen instead holds the media switch itself and the notification access
 * the phone's media session list depends on, with the sideload "restricted settings" route.
 */
@Composable
public fun MediaSettingsScreen(
    settingsViewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
) {
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        settingsViewModel.refreshNotificationAccess()
        onPauseOrDispose { }
    }

    VeyroDetailScaffold(title = "Share media with PC", onNavigateBack = onNavigateBack) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
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
            }

            item {
                NotificationAccessRow(granted = settings.notificationAccessGranted)
            }

            item {
                TextPreferenceWidget(
                    title = "Media from the PC",
                    subtitle = "While a PC is connected, what it is playing and its volume appear on Home.",
                    icon = VeyroIcons.ComputerSound,
                )
            }
        }
    }
}

/** The notification access status row, shared with Settings. Opens the system screen on tap. */
@Composable
internal fun NotificationAccessRow(granted: Boolean) {
    val context = LocalContext.current
    TextPreferenceWidget(
        title = if (granted) "Notification access granted" else "Notification access needed",
        subtitle = if (granted) {
            "Tap to review it in system settings."
        } else {
            "Tap to allow Veyro in system settings. On Android 13 and later, an app installed " +
                "outside an app store may first need \"Allow restricted settings\" from its App info menu."
        },
        icon = if (granted) VeyroIcons.Check else VeyroIcons.Warning,
        iconTint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        onPreferenceClick = { openNotificationAccess(context) },
    )
}
