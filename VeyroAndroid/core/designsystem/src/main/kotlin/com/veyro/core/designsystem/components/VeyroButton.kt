package com.veyro.core.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanDark
import com.veyro.core.designsystem.theme.VeyroCyanGlow
import com.veyro.core.designsystem.theme.VeyroGlassBorder
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTextOnPrimary

private val VeyroButtonShape = RoundedCornerShape(14.dp)

/**
 * Primary CTA button — cyan gradient fill with a subtle glow halo.
 * Use for the main action on each screen (Send, Accept, Pair, etc.)
 */
@Composable
public fun VeyroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 52.dp,
) {
    val gradient = Brush.horizontalGradient(
        colors = if (enabled) {
            listOf(VeyroCyan, VeyroCyanGlow)
        } else {
            listOf(VeyroCyanDark.copy(alpha = 0.4f), VeyroCyan.copy(alpha = 0.4f))
        },
    )

    val glowAlpha by animateGlowAlpha(enabled)

    Box(
        modifier = modifier
            .height(height)
            .clip(VeyroButtonShape)
            .then(
                if (enabled) {
                    Modifier
                        .background(brush = gradient, shape = VeyroButtonShape)
                        .padding(horizontal = 2.dp, vertical = 2.dp)
                        .clip(VeyroButtonShape)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    VeyroCyan.copy(alpha = glowAlpha),
                                    VeyroCyanDark.copy(alpha = glowAlpha),
                                ),
                            ),
                            shape = VeyroButtonShape,
                        )
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    VeyroCyanDark.copy(alpha = 0.2f),
                                    VeyroCyan.copy(alpha = 0.2f),
                                ),
                            ),
                            shape = VeyroButtonShape,
                        )
                        .border(0.5.dp, VeyroGlassBorder, VeyroButtonShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) VeyroTextOnPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Secondary outline button — glass border, no fill.
 * Use for secondary actions: Cancel, Decline, Clear.
 */
@Composable
public fun VeyroOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .height(height)
            .clip(VeyroButtonShape)
            .border(1.dp, VeyroGlassBorder, VeyroButtonShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = contentColor,
        )
    }
}

/**
 * Ghost text button — no border, just text + ripple. Used for inline actions.
 */
@Composable
public fun VeyroTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Danger outline button — red-tinted border. Use for "Forget device" / "Reset identity".
 */
@Composable
public fun VeyroDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val dangerColor = MaterialTheme.colorScheme.error

    Box(
        modifier = modifier
            .height(height)
            .clip(VeyroButtonShape)
            .border(1.dp, dangerColor.copy(alpha = if (enabled) 0.5f else 0.2f), VeyroButtonShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled) dangerColor else dangerColor.copy(alpha = 0.4f),
        )
    }
}

/**
 * Inline chip/badge for trust state, file type, etc.
 */
@Composable
public fun VeyroChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

/**
 * Animated glow alpha — gently pulses to suggest the button is alive/active.
 */
@Composable
private fun animateGlowAlpha(enabled: Boolean): androidx.compose.runtime.State<Float> {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    return infiniteTransition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )
}
