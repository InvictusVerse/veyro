package com.veyro.core.designsystem.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * A slider whose filled part turns into a travelling sine wave while something is playing, for
 * example the progress bar of a media card.
 *
 * Touch handling is a regular Material 3 [Slider] with an invisible thumb and track; this
 * composable only draws. While the user presses or drags, the wave flattens and the round thumb
 * morphs into a tall bar with a small gap on each side.
 *
 * @param value current position, within [valueRange].
 * @param onValueChange called continuously while the user drags.
 * @param valueRange range of [value].
 * @param onValueChangeFinished called once when the user lifts the finger.
 * @param interactionSource lets the caller observe press and drag state.
 * @param isPlaying shows the moving wave when true; a straight line when false.
 * @param trackHeight stroke width of the track.
 * @param thumbRadius radius of the resting, round thumb.
 * @param thumbLineHeight height of the thumb while pressed.
 * @param thumbGap clear space between the thumb and the track while pressed.
 * @param waveAmplitudeWhenPlaying how far the wave swings above and below the centre line.
 * @param waveLength distance between two wave crests.
 * @param waveAnimationDuration time in milliseconds for the wave to travel one full length.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun WavySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    isPlaying: Boolean = false,
    trackHeight: Dp = 6.dp,
    thumbRadius: Dp = 9.dp,
    thumbLineHeight: Dp = 24.dp,
    thumbGap: Dp = 4.dp,
    activeTrackColor: Color = MaterialTheme.colorScheme.primary,
    inactiveTrackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    waveAmplitudeWhenPlaying: Dp = 3.dp,
    waveLength: Dp = 40.dp,
    waveAnimationDuration: Int = 2000,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val dragged by interactionSource.collectIsDraggedAsState()
    val touching = pressed || dragged

    // 0 = resting round thumb, 1 = bar shape while touched. Animated values below are kept as
    // State and only read while drawing, so an animation frame redraws without recomposing.
    val thumbMorph = animateFloatAsState(
        targetValue = if (touching) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "WavySliderThumbMorph",
    )
    val waveWanted = isPlaying && !touching && waveAmplitudeWhenPlaying > 0.dp
    val waveAmplitude = animateDpAsState(
        targetValue = if (waveWanted) waveAmplitudeWhenPlaying else 0.dp,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "WavySliderWaveAmplitude",
    )

    // How far the wave has travelled, as a fraction of one cycle. Advanced by hand so that no
    // frame is requested once the wave is off and has faded out.
    val wavePhase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(waveWanted, waveAnimationDuration) {
        if (!waveWanted && waveAmplitude.value <= 0.dp) return@LaunchedEffect
        val cycleNanos = waveAnimationDuration.coerceAtLeast(1) * 1_000_000f
        var previous = withFrameNanos { it }
        while (waveWanted || waveAmplitude.value > 0.dp) {
            val now = withFrameNanos { it }
            wavePhase.floatValue = (wavePhase.floatValue + (now - previous) / cycleNanos) % 1f
            previous = now
        }
    }

    val density = LocalDensity.current
    val metrics = remember(density, trackHeight, thumbRadius, thumbLineHeight, thumbGap, waveLength) {
        with(density) {
            SliderMetrics(
                trackHeight = trackHeight.toPx(),
                thumbRadius = thumbRadius.toPx(),
                thumbBarHeight = thumbLineHeight.toPx(),
                thumbGap = thumbGap.toPx(),
                waveLength = waveLength.toPx(),
            )
        }
    }
    val palette = SliderPalette(activeTrackColor, inactiveTrackColor, thumbColor)
    val wavePath = remember { Path() }
    val sliderHeight = maxOf(trackHeight * 2, maxOf(thumbRadius * 2, thumbLineHeight) + 8.dp)

    Box(
        modifier = modifier
            .clipToBounds()
            .drawBehind {
                drawSlider(
                    fraction = fractionOf(value, valueRange),
                    thumbMorph = thumbMorph.value,
                    waveAmplitude = waveAmplitude.value.toPx(),
                    waveRadians = wavePhase.floatValue * TWO_PI,
                    metrics = metrics,
                    palette = palette,
                    wavePath = wavePath,
                )
            },
    ) {
        // The thumb is an invisible spacer exactly as wide as the drawn thumb, so the position
        // Material computes from a touch matches where the thumb is drawn.
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(sliderHeight),
            enabled = enabled,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
            interactionSource = interactionSource,
            thumb = { Spacer(Modifier.size(thumbRadius * 2)) },
            track = { Spacer(Modifier.fillMaxWidth()) },
        )
    }
}

private val TWO_PI: Float = (2.0 * PI).toFloat()

/** Segments per wave cycle when the wave is drawn as a polyline. */
private const val WAVE_SEGMENTS_PER_CYCLE = 32f

/** Below this amplitude in pixels the wave is drawn as a straight line. */
private const val MIN_VISIBLE_AMPLITUDE = 0.01f

private class SliderMetrics(
    val trackHeight: Float,
    val thumbRadius: Float,
    val thumbBarHeight: Float,
    val thumbGap: Float,
    val waveLength: Float,
)

private class SliderPalette(val active: Color, val inactive: Color, val thumb: Color)

private fun fractionOf(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    return if (span == 0f) 0f else ((value - range.start) / span).coerceIn(0f, 1f)
}

private fun mix(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

private fun DrawScope.drawSlider(
    fraction: Float,
    thumbMorph: Float,
    waveAmplitude: Float,
    waveRadians: Float,
    metrics: SliderMetrics,
    palette: SliderPalette,
    wavePath: Path,
) {
    val centreY = size.height / 2f
    val trackStart = metrics.thumbRadius
    val trackEnd = size.width - metrics.thumbRadius
    val thumbX = trackStart + (trackEnd - trackStart).coerceAtLeast(0f) * fraction

    // While touched, the track keeps its distance from the thumb on both sides.
    val clearance = (metrics.thumbGap + metrics.thumbRadius) * thumbMorph

    val inactiveStart = thumbX + clearance
    if (inactiveStart < trackEnd) {
        drawLine(
            color = palette.inactive,
            start = Offset(inactiveStart, centreY),
            end = Offset(trackEnd, centreY),
            strokeWidth = metrics.trackHeight,
            cap = StrokeCap.Round,
        )
    }

    val activeEnd = thumbX - clearance
    if (fraction > 0f && activeEnd > trackStart) {
        if (waveAmplitude > MIN_VISIBLE_AMPLITUDE && metrics.waveLength > 0f) {
            drawWave(
                path = wavePath,
                from = trackStart,
                to = activeEnd,
                centreY = centreY,
                amplitude = waveAmplitude,
                radians = waveRadians,
                metrics = metrics,
                color = palette.active,
            )
        } else {
            drawLine(
                color = palette.active,
                start = Offset(trackStart, centreY),
                end = Offset(activeEnd, centreY),
                strokeWidth = metrics.trackHeight,
                cap = StrokeCap.Round,
            )
        }
    }

    val thumbWidth = mix(metrics.thumbRadius * 2f, metrics.trackHeight * 1.2f, thumbMorph)
    val thumbHeight = mix(metrics.thumbRadius * 2f, metrics.thumbBarHeight, thumbMorph)
    drawRoundRect(
        color = palette.thumb,
        topLeft = Offset(thumbX - thumbWidth / 2f, centreY - thumbHeight / 2f),
        size = Size(thumbWidth, thumbHeight),
        cornerRadius = CornerRadius(thumbWidth / 2f),
    )
}

/** Strokes a sine curve between [from] and [to] using short straight segments. */
private fun DrawScope.drawWave(
    path: Path,
    from: Float,
    to: Float,
    centreY: Float,
    amplitude: Float,
    radians: Float,
    metrics: SliderMetrics,
    color: Color,
) {
    val angularStep = TWO_PI / metrics.waveLength
    val segment = metrics.waveLength / WAVE_SEGMENTS_PER_CYCLE
    fun yAt(x: Float): Float = centreY + amplitude * sin(angularStep * x + radians)

    path.reset()
    path.moveTo(from, yAt(from))
    var x = from + segment
    while (x < to) {
        path.lineTo(x, yAt(x))
        x += segment
    }
    path.lineTo(to, yAt(to))

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = metrics.trackHeight, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
