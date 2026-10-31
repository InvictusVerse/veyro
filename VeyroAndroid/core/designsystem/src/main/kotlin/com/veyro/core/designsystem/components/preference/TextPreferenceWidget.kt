package com.veyro.core.designsystem.components.preference

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import com.veyro.core.designsystem.components.secondaryItemAlpha

/**
 * A settings row with a [title] and a dimmed [subtitle]. Pass a monospace [subtitleStyle] to show
 * a fingerprint. A trailing [widget] and a click handler are both optional.
 */
@Composable
public fun TextPreferenceWidget(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    subtitleStyle: TextStyle = MaterialTheme.typography.bodySmall,
    widget: @Composable (() -> Unit)? = null,
    onPreferenceClick: (() -> Unit)? = null,
) {
    val subtitleSlot: (@Composable ColumnScope.() -> Unit)? =
        if (subtitle.isNullOrBlank()) {
            null
        } else {
            { SubtitleLine(text = subtitle, style = subtitleStyle) }
        }
    val iconSlot: (@Composable () -> Unit)? =
        if (icon == null) {
            null
        } else {
            { Icon(imageVector = icon, contentDescription = null, tint = iconTint) }
        }

    BasePreferenceWidget(
        modifier = modifier,
        title = title,
        subcomponent = subtitleSlot,
        icon = iconSlot,
        onClick = onPreferenceClick,
        widget = widget,
    )
}

@Composable
private fun SubtitleLine(text: String, style: TextStyle) {
    Text(
        text = text,
        modifier = Modifier
            .padding(horizontal = PreferenceMetrics.sideInset)
            .secondaryItemAlpha(),
        style = style,
        maxLines = SUBTITLE_MAX_LINES,
    )
}

private const val SUBTITLE_MAX_LINES = 10
