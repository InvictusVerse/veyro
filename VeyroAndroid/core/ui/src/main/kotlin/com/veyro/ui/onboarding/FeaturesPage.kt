// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/onboarding/PermissionStep.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * Page (c): the optional companion features, each off until switched on.
 *
 * Kept from Sefirah: the elevated card of list items with a granted check or a Grant action,
 * the "optional" group label, re-reading state on resume, and detecting a sideloaded install to
 * explain Android 13's "restricted settings".
 *
 * Veyro changes: rows are features, not permissions. Turning a switch on stores the choice and
 * then asks for exactly what that feature still lacks: the runtime permissions first, then the
 * one system screen it needs. Nothing is asked for a feature left off, and every feature works
 * as "skip" by simply not touching it.
 */
@Composable
internal fun FeaturesPage(
    features: List<FeatureState>,
    onToggle: (OnboardingFeature, Boolean) -> Unit,
    onAccessChanged: () -> Unit,
) {
    val context = LocalContext.current
    val sideloaded = remember { isSideloaded(context) }

    // The system screen to open once the runtime dialog closes. Saveable: the dialog can outlive
    // a rotation.
    var pendingSpecial by rememberSaveable { mutableStateOf<SpecialAccess?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        onAccessChanged()
        pendingSpecial?.let { openSpecialAccess(context, it) }
        pendingSpecial = null
    }

    fun requestWhatIsMissing(access: FeatureAccess) {
        val special = access.specialAccess?.takeIf { !access.specialGranted }
        if (access.missingPermissions.isNotEmpty()) {
            pendingSpecial = special
            permissionLauncher.launch(access.missingPermissions.toTypedArray())
        } else if (special != null) {
            openSpecialAccess(context, special)
        }
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
                features.forEachIndexed { index, state ->
                    if (index > 0) HorizontalDivider()
                    FeatureItem(
                        state = state,
                        showRestrictedHint = sideloaded && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                        onToggle = { on ->
                            onToggle(state.feature, on)
                            if (on) requestWhatIsMissing(state.access)
                        },
                        onGrant = { requestWhatIsMissing(state.access) },
                        onOpenAppInfo = { openAppInfo(context) },
                    )
                }
            }
        }
    }
}

private data class FeatureCopy(val title: String, val why: String)

private fun OnboardingFeature.text(): FeatureCopy = when (this) {
    OnboardingFeature.NOTIFICATIONS -> FeatureCopy(
        "Notifications",
        "See your phone's notifications on the PC and reply from there.",
    )
    OnboardingFeature.MEDIA -> FeatureCopy(
        "Media",
        "Control what is playing on your phone from the PC.",
    )
    OnboardingFeature.MESSAGES_AND_CALLS -> FeatureCopy(
        "Messages and calls",
        "Read and send texts and handle calls from the PC.",
    )
    OnboardingFeature.PHOTOS -> FeatureCopy(
        "Photos",
        "See recent camera photos and screenshots on the PC and open or save them there.",
    )
    OnboardingFeature.RING_AND_SOUND -> FeatureCopy(
        "Ring and sound mode",
        "Ring a lost phone and switch silent or Do Not Disturb from the PC.",
    )
}

@Composable
private fun OnboardingFeature.icon(): ImageVector = when (this) {
    OnboardingFeature.NOTIFICATIONS -> VeyroIcons.Notifications
    OnboardingFeature.MEDIA -> VeyroIcons.PlayCircle
    OnboardingFeature.MESSAGES_AND_CALLS -> VeyroIcons.Chat
    OnboardingFeature.PHOTOS -> VeyroIcons.Image
    OnboardingFeature.RING_AND_SOUND -> VeyroIcons.VolumeUp
}

private fun SpecialAccess.label(): String = when (this) {
    SpecialAccess.NOTIFICATION_LISTENER -> "Notification access"
    SpecialAccess.NOTIFICATION_POLICY -> "Do Not Disturb access"
}

@Composable
private fun FeatureItem(
    state: FeatureState,
    showRestrictedHint: Boolean,
    onToggle: (Boolean) -> Unit,
    onGrant: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val copy = state.feature.text()
    ListItem(
        modifier = Modifier.toggleable(value = state.enabled, role = Role.Switch, onValueChange = onToggle),
        leadingContent = {
            Icon(state.feature.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(text = copy.title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                text = copy.why,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        // The row is the toggle target; the Switch only renders the state.
        trailingContent = { Switch(checked = state.enabled, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )

    if (state.enabled) {
        AccessStatus(
            access = state.access,
            showRestrictedHint = showRestrictedHint,
            onGrant = onGrant,
            onOpenAppInfo = onOpenAppInfo,
        )
    }
}

@Composable
private fun AccessStatus(
    access: FeatureAccess,
    showRestrictedHint: Boolean,
    onGrant: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(start = 56.dp, end = 16.dp, bottom = 12.dp)
    if (access.granted) {
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Icon(VeyroIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Access granted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        return
    }
    val missing = buildList {
        addAll(access.missingLabels)
        access.specialAccess?.takeIf { !access.specialGranted }?.let { add(it.label()) }
    }
    Column(modifier = rowModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Needs: ${missing.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onGrant) { Text("Grant") }
        }
        val needsSpecial = access.specialAccess != null && !access.specialGranted
        if (showRestrictedHint && needsSpecial) {
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
    val action = when (access) {
        SpecialAccess.NOTIFICATION_LISTENER -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        SpecialAccess.NOTIFICATION_POLICY -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
    }
    try {
        context.startActivity(Intent(action))
    } catch (_: ActivityNotFoundException) {
        // Some builds hide the screen; the card keeps saying what is missing.
        openAppInfo(context)
    }
}

private fun openAppInfo(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        // Nothing else to offer; the guidance text still explains the path.
    }
}

/**
 * Whether this install came from somewhere other than Google Play. Android 13+ blocks
 * notification-listener access for such installs until "Allow restricted settings" is chosen.
 */
private fun isSideloaded(context: Context): Boolean = runCatching {
    val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
    }
    installer != "com.android.vending"
}.getOrDefault(true)
