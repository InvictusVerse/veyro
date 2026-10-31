package com.veyro.ui.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons
import kotlin.math.round

/**
 * A mute button next to a 0..100 slider whose thumb is a thin pill. While the finger is down the
 * pill carries a small label with the current percentage.
 *
 * [onLevelSet] is called once, when the finger lifts, not for every step along the way: a drag
 * then costs one `mediaCmd` on the link instead of up to a hundred.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VolumeSlider(
    level: Float,
    muted: Boolean,
    onLevelSet: (Float) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var handleLevel by remember { mutableFloatStateOf(level) }
    val touches = remember { MutableInteractionSource() }
    val pressed by touches.collectIsPressedAsState()
    val dragged by touches.collectIsDraggedAsState()
    val touching = pressed || dragged

    // Follow the level the PC reports, but never move the thumb away from the finger.
    LaunchedEffect(level) {
        if (!touching) handleLevel = level
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleMute) {
            Icon(
                imageVector = if (muted) VeyroIcons.VolumeOff else VeyroIcons.VolumeUp,
                contentDescription = if (muted) "Unmute" else "Mute",
                tint = MaterialTheme.colorScheme.surfaceTint,
                modifier = Modifier.size(24.dp),
            )
        }
        Slider(
            value = handleLevel,
            onValueChange = { handleLevel = round(it) },
            onValueChangeFinished = { onLevelSet(handleLevel) },
            valueRange = 0f..100f,
            interactionSource = touches,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = "Volume" },
            thumb = {
                PercentThumb(percent = handleLevel.toInt(), touches = touches, labelPinned = touching)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PercentThumb(percent: Int, touches: MutableInteractionSource, labelPinned: Boolean) {
    Label(
        label = {
            PlainTooltip(modifier = Modifier.sizeIn(45.dp, 25.dp).wrapContentWidth()) {
                Text("$percent%")
            }
        },
        interactionSource = touches,
        isPersistent = labelPinned,
    ) {
        Box(
            Modifier
                .size(width = 6.dp, height = 28.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}
