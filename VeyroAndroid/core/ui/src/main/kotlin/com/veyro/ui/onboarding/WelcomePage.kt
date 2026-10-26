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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
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
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.components.VeyroIconBadge
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanContainer
import com.veyro.core.designsystem.theme.VeyroTextSecondary

/**
 * Shared page frame: scrollable (landscape and large fonts), an icon-and-title header, then
 * the page body. Veyro change: Sefirah's header row is reused by every page instead of only
 * the first, and the title is marked as a heading for screen readers.
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
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VeyroIconBadge(
                icon = icon,
                contentDescription = null,
                size = 52.dp,
                iconSize = 28.dp,
                backgroundColor = VeyroCyanContainer,
                iconColor = VeyroCyan,
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
        }
        subtitle?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = VeyroTextSecondary)
        }
        Spacer(modifier = Modifier.height(4.dp))
        content()
    }
}

/** Page (a): what Veyro is, in three short promises. Written for Veyro. */
@Composable
internal fun WelcomePage() {
    OnboardingPage(
        icon = Icons.Default.Share,
        title = "Welcome to Veyro",
        subtitle = "Your phone and your PC, working together: files both ways, notifications, " +
            "media, messages and calls on the PC.",
    ) {
        VeyroCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Promise(
                    icon = Icons.Default.Share,
                    title = "Stays on your network",
                    text = "Devices talk to each other directly over your Wi-Fi. Nothing goes " +
                        "through the internet or a server.",
                )
                Promise(
                    icon = Icons.Default.Lock,
                    title = "Encrypted end to end",
                    text = "Every connection is encrypted, and only devices you paired can connect.",
                )
                Promise(
                    icon = Icons.Default.Person,
                    title = "No account",
                    text = "No sign-up and no cloud. Pairing is done once, by scanning a code.",
                )
            }
        }
        Text(
            text = "You will need Veyro for Windows on your PC. It takes about a minute.",
            style = MaterialTheme.typography.bodySmall,
            color = VeyroTextSecondary,
        )
    }
}

@Composable
private fun Promise(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = VeyroCyan, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text = text, style = MaterialTheme.typography.bodySmall, color = VeyroTextSecondary)
        }
    }
}
