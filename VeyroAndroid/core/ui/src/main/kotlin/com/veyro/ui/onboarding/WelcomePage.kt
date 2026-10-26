// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/onboarding/WelcomeStep.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.components.padding
import com.veyro.core.designsystem.icons.VeyroIcons

/**
 * Shared page frame, Sefirah's step header: a 48dp primary icon next to the title, then the body.
 * Veyro changes: reused by every page (the Pair page included), scrollable for landscape and large
 * fonts, an optional subtitle, and the title is marked as a heading for screen readers.
 */
@Composable
internal fun OnboardingPage(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(bottom = MaterialTheme.padding.small)
                    .size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        content()
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Page (a): what Veyro is. Veyro changes: the store and download buttons are replaced by three
 * short promises, since Veyro for Windows is not published to a store yet.
 */
@Composable
internal fun WelcomePage() {
    OnboardingPage(
        icon = VeyroIcons.RocketLaunch,
        title = "Welcome to Veyro",
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Your phone and your PC, working together: files both ways, notifications, " +
                        "media, messages and calls on the PC.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Promise(
                    icon = VeyroIcons.Wifi,
                    title = "Stays on your network",
                    text = "Devices talk to each other directly over your Wi-Fi. Nothing goes " +
                        "through the internet or a server.",
                )
                Promise(
                    icon = VeyroIcons.Lock,
                    title = "Encrypted end to end",
                    text = "Every connection is encrypted, and only devices you paired can connect.",
                )
                Promise(
                    icon = VeyroIcons.QrCodeScanner,
                    title = "No account",
                    text = "No sign-up and no cloud. Pairing is done once, by scanning a code.",
                )
            }
        }
        Text(
            text = "You will need Veyro for Windows on your PC. It takes about a minute.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Promise(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
