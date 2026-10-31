package com.veyro.core.designsystem.components.preference

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay

/**
 * The frame of one settings row: an optional leading [icon], a column with the [title] and an
 * optional [subcomponent], and an optional trailing [widget]. The whole row is clickable when
 * [onClick] is set.
 *
 * If [LocalPreferenceHighlighted] is true the row flashes once, shortly after it first appears.
 */
@Composable
public fun BasePreferenceWidget(
    modifier: Modifier = Modifier,
    title: String? = null,
    subcomponent: @Composable (ColumnScope.() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    widget: @Composable (() -> Unit)? = null,
) {
    val flashStrength = rememberFlashStrength(requested = LocalPreferenceHighlighted.current)
    val flashColor = MaterialTheme.colorScheme.surfaceTint

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LocalPreferenceMinHeight.current)
            .drawBehind {
                // Read here, in the draw phase, so each animation frame only redraws.
                val strength = flashStrength.value
                if (strength > 0f) drawRect(flashColor.copy(alpha = FLASH_PEAK_ALPHA * strength))
            }
            .tappable(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.padding(
                    start = PreferenceMetrics.sideInset,
                    end = PreferenceMetrics.iconEndGap,
                ),
            ) {
                icon()
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = PreferenceMetrics.verticalInset),
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    modifier = Modifier.padding(horizontal = PreferenceMetrics.sideInset),
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = PreferenceMetrics.titleSize,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            subcomponent?.invoke(this)
        }

        if (widget != null) {
            Box(modifier = Modifier.padding(end = PreferenceMetrics.sideInset)) {
                widget()
            }
        }
    }
}

private fun Modifier.tappable(action: (() -> Unit)?): Modifier =
    if (action == null) this else clickable(onClick = action)

/**
 * Drives the one-off attention flash: 0 = off, 1 = full tint. Pulses twice after a short delay,
 * stays lit, then fades out, about three seconds in total. Only runs when [requested] was true
 * when the row first entered the composition.
 */
@Composable
private fun rememberFlashStrength(requested: Boolean): State<Float> {
    val strength = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!requested) return@LaunchedEffect
        delay(FLASH_LEAD_IN_MS)
        repeat(FLASH_PULSES) {
            strength.animateTo(1f, tween(FLASH_STEP_MS))
            strength.animateTo(0f, tween(FLASH_STEP_MS))
        }
        strength.animateTo(1f, tween(FLASH_STEP_MS))
        delay(FLASH_HOLD_MS)
        strength.animateTo(0f, tween(FLASH_STEP_MS))
    }
    return strength.asState()
}

private const val FLASH_PEAK_ALPHA = 0.12f
private const val FLASH_LEAD_IN_MS = 600L
private const val FLASH_HOLD_MS = 1400L
private const val FLASH_STEP_MS = 200
private const val FLASH_PULSES = 2
