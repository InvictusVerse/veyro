package com.veyro.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.ui.components.rememberTickingClock

/**
 * The shared look of every pairing prompt on the phone (hardware test: the old violet "glass"
 * dialogs clashed with the app). A plain Material card on the app theme: phone and PC joined by a
 * line, a title, the code as six digit tiles, one line of instruction, a countdown bar, actions.
 */
@Composable
internal fun PairingPromptDialog(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 300.dp, max = 380.dp)
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

/** Phone and PC side by side, joined by a short line. */
@Composable
internal fun DevicePairHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeaderBadge { Icon(VeyroIcons.Mobile, contentDescription = null, modifier = Modifier.size(24.dp)) }
        Box(
            Modifier
                .padding(horizontal = 10.dp)
                .width(28.dp)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        )
        HeaderBadge { Icon(VeyroIcons.Desktop, contentDescription = null, modifier = Modifier.size(26.dp)) }
    }
}

@Composable
private fun HeaderBadge(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Six digits as tiles, grouped 3 + 3 so the code is easy to compare across two screens. */
@Composable
internal fun PairingCodeTiles(code: String, modifier: Modifier = Modifier) {
    val digits = code.filter { it.isDigit() }.padEnd(6).take(6)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Pairing code " + digits.toCharArray().joinToString(" ")
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        digits.forEachIndexed { index, digit ->
            if (index == 3) Spacer(Modifier.width(14.dp))
            else if (index > 0) Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = digit.toString(),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** A quiet bar that empties as the pairing window runs out, with the seconds next to it. */
@Composable
internal fun ExpiryBar(expiresAtMillis: Long, totalMillis: Long, modifier: Modifier = Modifier) {
    val now = rememberTickingClock(periodMillis = 500, until = expiresAtMillis)
    val leftMillis = (expiresAtMillis - now).coerceAtLeast(0)
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LinearProgressIndicator(
            progress = { (leftMillis.toFloat() / totalMillis.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
        Text(
            text = "${leftMillis / 1000} s left",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
internal fun PromptTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 20.dp),
    )
}

@Composable
internal fun PromptBody(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(top = 8.dp),
    )
}

/** Primary and secondary actions stacked full width, as on Phone Link's prompts. */
@Composable
internal fun PromptActions(
    primary: Pair<String, () -> Unit>?,
    secondary: Pair<String, () -> Unit>?,
    destructiveSecondary: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        primary?.let { (label, action) ->
            androidx.compose.material3.Button(
                onClick = action,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(label) }
        }
        secondary?.let { (label, action) ->
            androidx.compose.material3.OutlinedButton(
                onClick = action,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(
                    label,
                    color = if (destructiveSecondary) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
