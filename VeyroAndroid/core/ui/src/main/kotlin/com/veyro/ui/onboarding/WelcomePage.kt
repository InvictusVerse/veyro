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
import com.veyro.core.designsystem.icons.VeyroIcons

/**
 * Frame shared by every onboarding page, including the pages in `OnboardingPages.kt`: a large
 * accent icon beside the title, an optional explanatory line, then the page body. The frame
 * scrolls so that landscape and large font sizes still reach the bottom of the content, and the
 * title is a heading for screen readers.
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
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .size(48.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(16.dp))
    }
}

/** One short promise shown on the welcome card. */
private class Promise(val icon: ImageVector, val headline: String, val detail: String)

/**
 * First page: what Veyro is and three promises about how it works. Veyro for Windows is not in a
 * store yet, so the page ends with a note instead of a download button.
 */
@Composable
internal fun WelcomePage() {
    val promises = listOf(
        Promise(
            icon = VeyroIcons.Wifi,
            headline = "Stays on your network",
            detail = "Devices talk to each other directly over your Wi-Fi. Nothing goes " +
                "through the internet or a server.",
        ),
        Promise(
            icon = VeyroIcons.Lock,
            headline = "Encrypted end to end",
            detail = "Every connection is encrypted, and only devices you paired can connect.",
        ),
        Promise(
            icon = VeyroIcons.QrCodeScanner,
            headline = "No account",
            detail = "No sign-up and no cloud. Pairing is done once, by scanning a code.",
        ),
    )

    OnboardingPage(icon = VeyroIcons.RocketLaunch, title = "Welcome to Veyro") {
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
                promises.forEach { PromiseRow(it) }
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
private fun PromiseRow(promise: Promise) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            imageVector = promise.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Column {
            Text(
                text = promise.headline,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = promise.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
