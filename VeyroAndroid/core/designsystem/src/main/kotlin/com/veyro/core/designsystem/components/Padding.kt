package com.veyro.core.designsystem.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Opacity for content that is switched off. */
public const val DISABLED_ALPHA: Float = .38f

/** Opacity for secondary text and icons. */
public const val SECONDARY_ALPHA: Float = .78f

/**
 * Named spacing steps for screens, read as `MaterialTheme.padding.medium`. Each step is a fixed
 * size and does not depend on the theme.
 */
public class Padding internal constructor() {
    public val extraLarge: Dp = 32.dp
    public val large: Dp = 24.dp
    public val medium: Dp = 16.dp
    public val small: Dp = 8.dp
    public val extraSmall: Dp = 4.dp
}

private val SharedSteps = Padding()

/** The shared [Padding] steps. */
public val MaterialTheme.padding: Padding
    get() = SharedSteps
