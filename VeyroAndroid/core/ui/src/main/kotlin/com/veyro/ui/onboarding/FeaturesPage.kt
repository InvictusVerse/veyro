package com.veyro.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons

/**
 * Third onboarding step: the optional companion features, all off until the user switches them on.
 *
 * Each row is a feature rather than a permission. Switching one on stores the choice and then
 * asks for exactly what that feature still lacks: runtime permissions first, then the one system
 * screen it needs. A feature left off asks for nothing, so skipping is simply not touching it.
 * Access is shown under an enabled row and re-read by the host whenever the app resumes.
 */
@Composable
internal fun FeaturesPage(
    features: List<FeatureState>,
    onToggle: (OnboardingFeature, Boolean) -> Unit,
    onAccessChanged: () -> Unit,
) {
    val context = LocalContext.current
    val requester = rememberAccessRequester(onAccessChanged)
    // Android 13+ blocks some special access for apps installed outside Google Play.
    val showRestrictedHint = remember(context) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isSideloaded(context)
    }

    // Photos on the PC is on by default. Ask for photo access once, when this step first shows,
    // so the default works; every other feature is asked about only when switched on.
    var askedForDefaults by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(features) {
        if (askedForDefaults) return@LaunchedEffect
        val photos = features.firstOrNull { it.feature == OnboardingFeature.PHOTOS }
            ?: return@LaunchedEffect
        askedForDefaults = true
        if (photos.enabled) requester.requestPermissionsOnly(photos.access)
    }

    OnboardingPage(
        icon = VeyroIcons.SettingsAlert,
        title = "Choose what to share",
        subtitle = "All optional. Turn on what you want now, or change it later on the device's settings.",
    ) {
        Text(
            text = "Optional",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                features.forEachIndexed { position, featureState ->
                    if (position > 0) HorizontalDivider()
                    FeatureRow(
                        state = featureState,
                        showRestrictedHint = showRestrictedHint,
                        onSwitch = { enabled ->
                            onToggle(featureState.feature, enabled)
                            if (enabled) requester.request(featureState.access)
                        },
                        onGrant = { requester.request(featureState.access) },
                        onOpenAppInfo = { openAppInfo(context) },
                    )
                }
            }
        }
    }
}

/**
 * Runs the two-step request a feature can need. [askPermissions] shows the system permission
 * dialog; [afterDialog] records which settings screen to open once that dialog has closed.
 */
private class AccessRequester(
    private val context: Context,
    private val askPermissions: (List<String>) -> Unit,
    private val afterDialog: (SpecialAccess?) -> Unit,
) {
    fun request(access: FeatureAccess) {
        val screen = access.specialAccess?.takeIf { !access.specialGranted }
        if (access.missingPermissions.isNotEmpty()) {
            afterDialog(screen)
            askPermissions(access.missingPermissions)
        } else if (screen != null) {
            openSpecialAccess(context, screen)
        }
    }

    fun requestPermissionsOnly(access: FeatureAccess) {
        if (access.missingPermissions.isNotEmpty()) askPermissions(access.missingPermissions)
    }
}

@Composable
private fun rememberAccessRequester(onAccessChanged: () -> Unit): AccessRequester {
    val context = LocalContext.current
    val latestOnAccessChanged by rememberUpdatedState(onAccessChanged)
    // Saveable because the permission dialog can outlive a rotation.
    var screenAfterDialog by rememberSaveable { mutableStateOf<SpecialAccess?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        latestOnAccessChanged()
        screenAfterDialog?.let { openSpecialAccess(context, it) }
        screenAfterDialog = null
    }

    return remember(context, launcher) {
        AccessRequester(
            context = context,
            askPermissions = { names -> launcher.launch(names.toTypedArray()) },
            afterDialog = { screenAfterDialog = it },
        )
    }
}

private class FeatureInfo(val icon: ImageVector, val title: String, val why: String)

@Composable
private fun infoFor(feature: OnboardingFeature): FeatureInfo = when (feature) {
    OnboardingFeature.NOTIFICATIONS -> FeatureInfo(
        icon = VeyroIcons.Notifications,
        title = "Notifications",
        why = "See your phone's notifications on the PC and reply from there.",
    )
    OnboardingFeature.MEDIA -> FeatureInfo(
        icon = VeyroIcons.PlayCircle,
        title = "Media",
        why = "Control what is playing on your phone from the PC.",
    )
    OnboardingFeature.MESSAGES_AND_CALLS -> FeatureInfo(
        icon = VeyroIcons.Chat,
        title = "Messages and calls",
        why = "Read and send texts and handle calls from the PC.",
    )
    OnboardingFeature.PHOTOS -> FeatureInfo(
        icon = VeyroIcons.Image,
        title = "Photos",
        why = "See recent camera photos and screenshots on the PC and open or save them there.",
    )
    OnboardingFeature.WALLPAPER -> FeatureInfo(
        icon = VeyroIcons.Mobile,
        title = "Wallpaper on PC",
        why = "The PC shows this phone with your exact wallpaper. Android only allows that with All files " +
            "access; without it the PC uses your wallpaper's colours.",
    )
    OnboardingFeature.RING_AND_SOUND -> FeatureInfo(
        icon = VeyroIcons.VolumeUp,
        title = "Ring and sound mode",
        why = "Ring a lost phone and switch silent or Do Not Disturb from the PC.",
    )
}

private fun SpecialAccess.screenName(): String = when (this) {
    SpecialAccess.NOTIFICATION_LISTENER -> "Notification access"
    SpecialAccess.NOTIFICATION_POLICY -> "Do Not Disturb access"
    SpecialAccess.ALL_FILES -> "All files access"
}

@Composable
private fun FeatureRow(
    state: FeatureState,
    showRestrictedHint: Boolean,
    onSwitch: (Boolean) -> Unit,
    onGrant: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val info = infoFor(state.feature)
    ListItem(
        // The whole row is the touch target; the Switch only shows the state.
        modifier = Modifier.toggleable(value = state.enabled, role = Role.Switch, onValueChange = onSwitch),
        leadingContent = {
            Icon(info.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(info.title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                text = info.why,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { Switch(checked = state.enabled, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    if (state.enabled) {
        AccessNote(
            access = state.access,
            showRestrictedHint = showRestrictedHint,
            onGrant = onGrant,
            onOpenAppInfo = onOpenAppInfo,
        )
    }
}

/** Under an enabled feature: either "Access granted" or what is missing, with a Grant button. */
@Composable
private fun AccessNote(
    access: FeatureAccess,
    showRestrictedHint: Boolean,
    onGrant: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val noteModifier = Modifier
        .fillMaxWidth()
        .padding(start = 56.dp, end = 16.dp, bottom = 12.dp)

    if (access.granted) {
        Row(
            modifier = noteModifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = VeyroIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Access granted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        return
    }

    val pendingScreen = access.specialAccess?.takeIf { !access.specialGranted }
    val missing = access.missingLabels + listOfNotNull(pendingScreen?.screenName())
    Column(modifier = noteModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Needs: ${missing.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onGrant) { Text("Grant") }
        }
        if (showRestrictedHint && pendingScreen != null) {
            Text(
                text = "Installed from outside an app store? If Android says the setting is " +
                    "restricted, open App info, tap the three-dot menu, choose \"Allow restricted " +
                    "settings\", then tap Grant again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenAppInfo) { Text("App info") }
        }
    }
}

private fun openSpecialAccess(context: Context, access: SpecialAccess) {
    val intent = when (access) {
        SpecialAccess.NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        SpecialAccess.NOTIFICATION_POLICY -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        // Open Veyro's own switch instead of the list of every app.
        SpecialAccess.ALL_FILES -> Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some builds hide the screen. Fall back to App info; the row still says what is missing.
        openAppInfo(context)
    }
}

private fun openAppInfo(context: Context) {
    val details = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(details)
    } catch (_: ActivityNotFoundException) {
        // Nothing else to offer; the hint text already explains the path.
    }
}

/**
 * True unless the app was installed by Google Play. An unknown installer counts as sideloaded,
 * because that is the case where Android 13+ hides notification-listener access behind
 * "Allow restricted settings".
 */
private fun isSideloaded(context: Context): Boolean {
    val installer = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    } catch (_: Exception) {
        return true
    }
    return installer != "com.android.vending"
}
