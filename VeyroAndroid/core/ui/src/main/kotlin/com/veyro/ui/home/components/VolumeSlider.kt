// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/home/components/VolumeSlider.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Label
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons
import kotlin.math.round

/**
 * Volume 0..100 with a mute button and a pill thumb that shows the percentage while dragged.
 *
 * Veyro change: [onVolumeChange] fires when the drag ends rather than on every step, so a drag
 * sends one `mediaCmd` over the link instead of up to a hundred.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VolumeSlider(
    volume: Float,
    isMuted: Boolean,
    onVolumeChange: (Float) -> Unit,
    toggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderPosition by remember { mutableFloatStateOf(volume) }
    val interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }

    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isInteracting = isPressed || isDragged

    LaunchedEffect(volume) {
        if (!isInteracting) sliderPosition = volume
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        IconButton(onClick = toggleMute) {
            Icon(
                imageVector = if (isMuted) VeyroIcons.VolumeOff else VeyroIcons.VolumeUp,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = MaterialTheme.colorScheme.surfaceTint,
                modifier = Modifier.size(24.dp),
            )
        }
        Slider(
            value = sliderPosition,
            onValueChange = { newValue -> sliderPosition = round(newValue) },
            onValueChangeFinished = { onVolumeChange(sliderPosition) },
            interactionSource = interactionSource,
            valueRange = 0f..100f,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = "Volume" },
            thumb = {
                val density = LocalDensity.current
                val thumbWidthDp = 6.dp
                val thumbHeightDp = 28.dp
                val thumbWidthPx = with(density) { thumbWidthDp.toPx() }
                val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
                val primaryColor = MaterialTheme.colorScheme.primary

                Label(
                    label = {
                        PlainTooltip(modifier = Modifier.sizeIn(45.dp, 25.dp).wrapContentWidth()) {
                            Text("${sliderPosition.toInt()}%")
                        }
                    },
                    interactionSource = interactionSource,
                    isPersistent = isInteracting,
                ) {
                    Canvas(modifier = Modifier.size(thumbWidthDp, thumbHeightDp)) {
                        drawRoundRect(
                            color = primaryColor,
                            size = Size(thumbWidthPx, thumbHeightPx),
                            cornerRadius = CornerRadius(thumbWidthPx / 2f),
                        )
                    }
                }
            },
        )
    }
}
