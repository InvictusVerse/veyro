// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), core/presentation/src/main/java/sefirah/presentation/components/Constants.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.core.designsystem.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

public const val DISABLED_ALPHA: Float = .38f
public const val SECONDARY_ALPHA: Float = .78f

/** The spacing steps Sefirah's screens use, reachable as `MaterialTheme.padding.medium`. */
public class Padding internal constructor() {
    public val extraLarge: Dp = 32.dp
    public val large: Dp = 24.dp
    public val medium: Dp = 16.dp
    public val small: Dp = 8.dp
    public val extraSmall: Dp = 4.dp
}

private val PaddingInstance = Padding()

public val MaterialTheme.padding: Padding
    get() = PaddingInstance
