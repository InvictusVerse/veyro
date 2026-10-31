package com.veyro.ui.onboarding.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val DotSize = 10.dp
private val ActiveLength = 28.dp
private const val MorphMillis = 400

/**
 * Progress marker for the onboarding bottom bar. One dot per page; the dot of [selectedPage]
 * stretches into a pill and takes the accent colour. The row is announced once to screen readers
 * as "Step N of M" and its dots are hidden from them.
 */
@Composable
internal fun PageIndicator(
    pageCount: Int,
    selectedPage: Int,
    modifier: Modifier = Modifier,
    selectedColor: Color = MaterialTheme.colorScheme.primary,
    unselectedColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    val idleColor = unselectedColor.copy(alpha = 0.35f)
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "Step ${selectedPage + 1} of $pageCount"
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (page in 0 until pageCount) {
            Dot(isActive = page == selectedPage, activeColor = selectedColor, idleColor = idleColor)
        }
    }
}

@Composable
private fun Dot(isActive: Boolean, activeColor: Color, idleColor: Color) {
    val transition = updateTransition(targetState = isActive, label = "onboardingDot")
    val length: Dp by transition.animateDp(
        transitionSpec = { tween(MorphMillis) },
        label = "dotLength",
    ) { active -> if (active) ActiveLength else DotSize }
    val fill: Color by transition.animateColor(
        transitionSpec = { tween(MorphMillis) },
        label = "dotColor",
    ) { active -> if (active) activeColor else idleColor }

    Box(
        modifier = Modifier
            .width(length)
            .height(DotSize)
            .background(color = fill, shape = CircleShape),
    )
}
