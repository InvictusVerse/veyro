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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.components.VeyroTextButton
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTextSecondary

/**
 * Page (c): the optional companion features, each off until switched on.
 *
 * Kept from Sefirah: one row per grant with a granted check or a Grant action, re-reading state on
 * resume, and detecting a sideloaded install to explain Android 13's "restricted settings".
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
        icon = Icons.Default.Settings,
        title = "Choose what to share",
        subtitle = "All optional. Turn on what you want now, or change it later in Settings.",
    ) {
        features.forEach { state ->
            FeatureCard(
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

private data class FeatureCopy(val icon: ImageVector, val title: String, val why: String)

private fun OnboardingFeature.text(): FeatureCopy = when (this) {
    OnboardingFeature.NOTIFICATIONS -> FeatureCopy(
        Icons.Default.Notifications,
        "Notifications",
        "See your phone's notifications on the PC and reply from there.",
    )
    OnboardingFeature.MEDIA -> FeatureCopy(
        Icons.Default.PlayArrow,
        "Media",
        "Control what is playing on your phone from the PC.",
    )
    OnboardingFeature.MESSAGES_AND_CALLS -> FeatureCopy(
        Icons.Default.Email,
        "Messages and calls",
        "Read and send texts and handle calls from the PC.",
    )
    OnboardingFeature.RING_AND_SOUND -> FeatureCopy(
        Icons.Default.Phone,
        "Ring and sound mode",
        "Ring a lost phone and switch silent or Do Not Disturb from the PC.",
    )
}

private fun SpecialAccess.label(): String = when (this) {
    SpecialAccess.NOTIFICATION_LISTENER -> "Notification access"
    SpecialAccess.NOTIFICATION_POLICY -> "Do Not Disturb access"
}

@Composable
private fun FeatureCard(
    state: FeatureState,
    showRestrictedHint: Boolean,
    onToggle: (Boolean) -> Unit,
    onGrant: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val copy = state.feature.text()
    VeyroCard(modifier = Modifier.fillMaxWidth(), selected = state.enabled) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = state.enabled, role = Role.Switch, onValueChange = onToggle),
        ) {
            Icon(copy.icon, contentDescription = null, tint = VeyroCyan, modifier = Modifier.size(26.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(copy.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(copy.why, style = MaterialTheme.typography.bodySmall, color = VeyroTextSecondary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            // The Row is the toggle target; the Switch only renders the state.
            Switch(checked = state.enabled, onCheckedChange = null)
        }

        if (state.enabled) {
            Spacer(modifier = Modifier.size(10.dp))
            AccessStatus(
                access = state.access,
                showRestrictedHint = showRestrictedHint,
                onGrant = onGrant,
                onOpenAppInfo = onOpenAppInfo,
            )
        }
    }
}

@Composable
private fun AccessStatus(
    access: FeatureAccess,
    showRestrictedHint: Boolean,
    onGrant: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    if (access.granted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = VeyroTeal, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Access granted", style = MaterialTheme.typography.bodySmall, color = VeyroTeal)
        }
        return
    }
    val missing = buildList {
        addAll(access.missingLabels)
        access.specialAccess?.takeIf { !access.specialGranted }?.let { add(it.label()) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, contentDescription = null, tint = VeyroCyan, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Needs access: ${missing.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            VeyroTextButton(text = "Grant", onClick = onGrant)
        }
        val needsSpecial = access.specialAccess != null && !access.specialGranted
        if (showRestrictedHint && needsSpecial) {
            Text(
                text = "Installed from outside an app store? If Android says the setting is " +
                    "restricted, open App info, tap the three-dot menu, choose \"Allow restricted " +
                    "settings\", then tap Grant again.",
                style = MaterialTheme.typography.bodySmall,
                color = VeyroTextSecondary,
            )
            VeyroTextButton(text = "Open App info", onClick = onOpenAppInfo)
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
