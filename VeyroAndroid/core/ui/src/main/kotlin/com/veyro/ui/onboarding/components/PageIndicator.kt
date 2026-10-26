// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/onboarding/components/PageIndicator.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.onboarding.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.theme.VeyroCyan

/**
 * Onboarding progress dots: the current page is a wide pill, the others small dots.
 *
 * Veyro changes: Veyro colours, spaced rather than stretched, and one accessibility label for
 * the whole row ("Step 2 of 5") instead of unlabelled boxes.
 */
@Composable
internal fun PageIndicator(
    pageCount: Int,
    selectedPage: Int,
    modifier: Modifier = Modifier,
    selectedColor: Color = VeyroCyan,
    unselectedColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Step ${selectedPage + 1} of $pageCount"
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(pageCount) { page ->
            val selected = page == selectedPage
            val width by animateDpAsState(
                targetValue = if (selected) 28.dp else 10.dp,
                animationSpec = tween(400),
                label = "indicatorWidth",
            )
            val color by animateColorAsState(
                targetValue = if (selected) selectedColor else unselectedColor.copy(alpha = 0.35f),
                animationSpec = tween(400),
                label = "indicatorColor",
            )
            Box(
                modifier = Modifier
                    .height(10.dp)
                    .width(width)
                    .clip(if (selected) RoundedCornerShape(50) else CircleShape)
                    .background(color),
            )
        }
    }
}
