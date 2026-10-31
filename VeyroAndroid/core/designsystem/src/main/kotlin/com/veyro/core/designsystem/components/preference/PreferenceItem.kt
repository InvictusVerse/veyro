package com.veyro.core.designsystem.components.preference

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * When true, preference rows flash briefly after they first appear. A settings screen provides it
 * for the row it wants to draw attention to, for example one reached from a search result.
 */
public val LocalPreferenceHighlighted: ProvidableCompositionLocal<Boolean> =
    compositionLocalOf { false }

/** The smallest height a preference row may have. 56 dp keeps one-line rows comfortably tappable. */
public val LocalPreferenceMinHeight: ProvidableCompositionLocal<Dp> =
    compositionLocalOf { 56.dp }

/** Spacing and type constants shared by the preference row composables in this package. */
internal object PreferenceMetrics {
    /** Space between the screen edge and the row content. Wider than 16 dp for long fingerprints (ADR-038). */
    val sideInset: Dp = 20.dp
    val verticalInset: Dp = 16.dp
    val iconEndGap: Dp = 8.dp

    /** Gap between the text and a trailing switch or other widget. */
    val widgetGap: Dp = 16.dp

    val titleSize: TextUnit = 16.sp
}
