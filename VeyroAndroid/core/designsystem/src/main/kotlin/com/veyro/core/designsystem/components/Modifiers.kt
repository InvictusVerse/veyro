// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), core/presentation/src/main/java/sefirah/presentation/util/Modifier.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.core.designsystem.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha

/** Dims secondary text the way Sefirah's preference subtitles and empty states do. */
public fun Modifier.secondaryItemAlpha(): Modifier = this.alpha(SECONDARY_ALPHA)
