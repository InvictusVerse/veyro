package com.veyro.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

/**
 * Page dots for a horizontal pager. While the user swipes, the dot of the page being left
 * stretches towards its neighbour, the neighbour stretches back, and the highlight hands over
 * between them. At rest it is a plain row of dots with the current page highlighted.
 *
 * @param currentPageOffset signed scroll distance from [currentPage] as a fraction of a page:
 * positive while moving to the next page, negative while moving to the previous one.
 */
@Composable
public fun FlowingPageIndicator(
    pageCount: Int,
    currentPage: Int,
    currentPageOffset: Float,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
) {
    if (pageCount <= 1) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Page ${currentPage + 1} of $pageCount"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(width = DOT_PITCH * pageCount, height = DOT_DIAMETER)) {
            drawDots(
                pageCount = pageCount,
                currentPage = currentPage,
                pageOffset = currentPageOffset,
                activeColor = activeColor,
                inactiveColor = inactiveColor,
            )
        }
    }
}

private val DOT_DIAMETER = 7.dp

/** Distance between the centres of two neighbouring dots. */
private val DOT_PITCH = 14.dp

/** Width of a dot at its most stretched. */
private val STRETCHED_WIDTH = 14.dp

/** Below this scroll fraction the dots are drawn as a resting row. */
private const val REST_THRESHOLD = 0.01f

private fun DrawScope.drawDots(
    pageCount: Int,
    currentPage: Int,
    pageOffset: Float,
    activeColor: Color,
    inactiveColor: Color,
) {
    val diameter = DOT_DIAMETER.toPx()
    val pitch = DOT_PITCH.toPx()
    val stretched = STRETCHED_WIDTH.toPx()

    val progress = abs(pageOffset)
    val movingForward = pageOffset > 0f
    val neighbour = when {
        movingForward -> if (currentPage < pageCount - 1) currentPage + 1 else currentPage
        else -> if (currentPage > 0) currentPage - 1 else currentPage
    }
    val flowing = progress >= REST_THRESHOLD

    fun centreOf(page: Int): Float = pitch * (page + 0.5f)

    fun pill(centreX: Float, width: Float, color: Color) {
        drawRoundRect(
            color = color,
            topLeft = Offset(centreX - width / 2f, 0f),
            size = Size(width, diameter),
            cornerRadius = CornerRadius(diameter / 2f),
        )
    }

    // The two dots that take part in the hand-over are skipped here while flowing; they are
    // drawn below with their animated width, position and colour.
    for (page in 0 until pageCount) {
        if (flowing && (page == currentPage || page == neighbour)) continue
        pill(centreOf(page), diameter, if (page == currentPage) activeColor else inactiveColor)
    }
    if (!flowing) return

    // One scroll fraction drives both dots through four quarter-page steps. The leaving dot
    // stretches over the first quarter and relaxes over the third; the arriving dot stretches
    // over the second quarter and relaxes over the last.
    val direction = if (movingForward) 1f else -1f
    val halfPitch = pitch / 2f

    val leavingStretch = min(ramp(progress, 0f, 0.25f), 1f - ramp(progress, 0.5f, 0.75f))
    val leavingShift = min(ramp(progress, 0f, 0.25f), 1f - ramp(progress, 0.75f, 1f))
    val leavingCentre = centreOf(currentPage) + direction * halfPitch * leavingShift
    val leavingWidth = mix(diameter, stretched, leavingStretch)

    val arrivingStretch = min(ramp(progress, 0.25f, 0.5f), 1f - ramp(progress, 0.75f, 1f))
    val arrivingShift = ramp(progress, 0.25f, 0.5f)
    val arrivingCentre = centreOf(neighbour) - direction * halfPitch * arrivingShift
    val arrivingWidth = mix(diameter, stretched, arrivingStretch)
    val arrivingColor = lerpColor(inactiveColor, activeColor, ramp(progress, 0.25f, 0.75f))

    // Whichever dot is further along the hand-over is drawn on top.
    if (progress < 0.5f) {
        pill(arrivingCentre, arrivingWidth, arrivingColor)
        pill(leavingCentre, leavingWidth, activeColor)
    } else {
        pill(leavingCentre, leavingWidth, activeColor)
        pill(arrivingCentre, arrivingWidth, arrivingColor)
    }
}

/** Position of [value] within [from]..[to], clamped to 0..1. */
private fun ramp(value: Float, from: Float, to: Float): Float =
    ((value - from) / (to - from)).coerceIn(0f, 1f)

private fun mix(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction
