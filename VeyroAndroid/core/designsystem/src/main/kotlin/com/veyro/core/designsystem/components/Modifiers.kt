package com.veyro.core.designsystem.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha

/** Softens secondary content (subtitles, empty-state text) by drawing it at [SECONDARY_ALPHA]. */
public fun Modifier.secondaryItemAlpha(): Modifier = alpha(SECONDARY_ALPHA)
