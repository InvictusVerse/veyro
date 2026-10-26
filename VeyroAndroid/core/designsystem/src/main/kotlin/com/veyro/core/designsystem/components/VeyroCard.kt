package com.veyro.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.theme.VeyroExtras
import com.veyro.core.designsystem.theme.VeyroGlassBorder

/**
 * Futuristic glassmorphic card.
 *
 * Dark-mode: semi-transparent glass with a subtle border edge-glow.
 * Light-mode: slightly elevated with a soft tint.
 *
 * @param selected  If true, shows a cyan-tinted selected state (used for device selection).
 * @param glow     If true, adds a soft cyan halo at the top — useful for active/hero cards.
 * @param contentPadding  Inner padding. Defaults to 20.dp.
 */
@Composable
public fun VeyroCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    glow: Boolean = false,
    contentPadding: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val grad = VeyroExtras.gradients

    Box(modifier = modifier) {
        // Glow halo at top — draws behind the card
        if (glow) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(brush = grad.cyanGlow)
                    .padding(top = 0.dp),
            )
        }

        // Card body
        val cardColor = when {
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else -> MaterialTheme.colorScheme.surface
        }

        val borderColor = when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            glow -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            else -> VeyroGlassBorder
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    brush = if (selected) grad.cardSelected else grad.card,
                    shape = shape,
                )
                .border(
                    width = if (selected || glow) 1.dp else 0.5.dp,
                    color = borderColor,
                    shape = shape,
                )
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else Modifier,
                )
                .padding(contentPadding),
            content = content,
        )
    }
}

/**
 * A simple VeyroCard wrapper for content that doesn't need to configure selected/glow.
 */
@Composable
public fun VeyroGlassCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    VeyroCard(modifier, selected = false, glow = false, contentPadding, onClick, content)
}

/**
 * Hero card — used for the main status card on a screen (e.g. "Receiving" on ReceiveScreen).
 * Has the top glow halo and slightly more vertical padding.
 */
@Composable
public fun VeyroHeroCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    VeyroCard(
        modifier = modifier,
        glow = true,
        contentPadding = 20.dp,
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        content()
    }
}

/**
 * Section header label — used above a group of VeyroCards.
 */
@Composable
public fun VeyroSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    )
}
