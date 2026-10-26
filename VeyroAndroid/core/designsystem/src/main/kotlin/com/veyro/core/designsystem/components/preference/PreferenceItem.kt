// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/settings/components/PreferenceItem.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.core.designsystem.components.preference

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

public val LocalPreferenceHighlighted: ProvidableCompositionLocal<Boolean> =
    compositionLocalOf(structuralEqualityPolicy()) { false }

public val LocalPreferenceMinHeight: ProvidableCompositionLocal<Dp> =
    compositionLocalOf(structuralEqualityPolicy()) { 56.dp }
