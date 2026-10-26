// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/ui/theme/Theme.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ---------------------------------------------------------------------------
// Colour schemes
// ---------------------------------------------------------------------------
//
// Sefirah ships the Material baseline purple as its fallback. Veyro keeps its own identity
// instead: the fallback is the Material 3 tonal-spot scheme for Veyro's cyan seed (#00D9FF), used
// below Android 12 or when dynamic colour is switched off.

private val VeyroLightScheme = lightColorScheme(
    primary = Color(0xFF006782),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBAEAFF),
    onPrimaryContainer = Color(0xFF001F29),
    inversePrimary = Color(0xFF5FD4FD),
    secondary = Color(0xFF4C616B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE6F1),
    onSecondaryContainer = Color(0xFF071E26),
    tertiary = Color(0xFF5C5B7E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2DFFF),
    onTertiaryContainer = Color(0xFF191837),
    background = Color(0xFFF6FAFD),
    onBackground = Color(0xFF171C1F),
    surface = Color(0xFFF6FAFD),
    onSurface = Color(0xFF171C1F),
    surfaceVariant = Color(0xFFDCE4E9),
    onSurfaceVariant = Color(0xFF40484C),
    surfaceTint = Color(0xFF006782),
    inverseSurface = Color(0xFF2C3134),
    inverseOnSurface = Color(0xFFEDF1F5),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF70787D),
    outlineVariant = Color(0xFFC0C8CD),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF6FAFD),
    surfaceDim = Color(0xFFD6DBDE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4F7),
    surfaceContainer = Color(0xFFEAEEF2),
    surfaceContainerHigh = Color(0xFFE4E9EC),
    surfaceContainerHighest = Color(0xFFDFE3E6),
)

private val VeyroDarkScheme = darkColorScheme(
    primary = Color(0xFF5FD4FD),
    onPrimary = Color(0xFF003545),
    primaryContainer = Color(0xFF004D62),
    onPrimaryContainer = Color(0xFFBAEAFF),
    inversePrimary = Color(0xFF006782),
    secondary = Color(0xFFB3CAD5),
    onSecondary = Color(0xFF1E333C),
    secondaryContainer = Color(0xFF354A53),
    onSecondaryContainer = Color(0xFFCFE6F1),
    tertiary = Color(0xFFC5C2EA),
    onTertiary = Color(0xFF2E2D4D),
    tertiaryContainer = Color(0xFF444465),
    onTertiaryContainer = Color(0xFFE2DFFF),
    background = Color(0xFF0F1417),
    onBackground = Color(0xFFDEE3E6),
    surface = Color(0xFF0F1417),
    onSurface = Color(0xFFDEE3E6),
    surfaceVariant = Color(0xFF40484C),
    onSurfaceVariant = Color(0xFFC0C8CD),
    surfaceTint = Color(0xFF5FD4FD),
    inverseSurface = Color(0xFFDEE3E6),
    inverseOnSurface = Color(0xFF2C3134),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A9297),
    outlineVariant = Color(0xFF40484C),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353A3D),
    surfaceDim = Color(0xFF0F1417),
    surfaceContainerLowest = Color(0xFF0A0F11),
    surfaceContainerLow = Color(0xFF171C1F),
    surfaceContainer = Color(0xFF1B2023),
    surfaceContainerHigh = Color(0xFF252B2D),
    surfaceContainerHighest = Color(0xFF303638),
)

/** Sefirah uses the Material 3 default shapes; so does Veyro. Named so screens can refer to it. */
public val VeyroShapes: Shapes = Shapes()

// ---------------------------------------------------------------------------
// Veyro extras - gradients, now derived from whichever scheme is active
// ---------------------------------------------------------------------------

/**
 * Brushes some older components still read through [VeyroExtras]. They are computed from the
 * active [ColorScheme], so with dynamic colour they follow the wallpaper like everything else.
 */
@Immutable
public data class VeyroGradients(
    val cyanGlow: Brush,
    val blueGlow: Brush,
    val violetGlow: Brush,
    val card: Brush,
    val cardSelected: Brush,
    val background: Brush,
    val progressTrack: Brush,
    val progressFill: Brush,
)

private fun gradientsFor(scheme: ColorScheme): VeyroGradients = VeyroGradients(
    cyanGlow = Brush.verticalGradient(listOf(scheme.primary.copy(alpha = 0.18f), Color.Transparent)),
    blueGlow = Brush.verticalGradient(listOf(scheme.secondary.copy(alpha = 0.18f), Color.Transparent)),
    violetGlow = Brush.verticalGradient(listOf(scheme.tertiary.copy(alpha = 0.18f), Color.Transparent)),
    card = Brush.verticalGradient(listOf(scheme.surfaceContainerLow, scheme.surfaceContainerLow)),
    cardSelected = Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.primaryContainer)),
    background = Brush.verticalGradient(listOf(scheme.background, scheme.background)),
    progressTrack = Brush.horizontalGradient(listOf(scheme.surfaceContainerHighest, scheme.surfaceContainerHighest)),
    progressFill = Brush.horizontalGradient(listOf(scheme.primary, scheme.primary)),
)

public val LocalVeyroGradients: androidx.compose.runtime.ProvidableCompositionLocal<VeyroGradients> =
    staticCompositionLocalOf { gradientsFor(VeyroDarkScheme) }

// ---------------------------------------------------------------------------
// Root theme
// ---------------------------------------------------------------------------

/**
 * Root theme, ported from Sefirah's.
 *
 * Dynamic colour is **on by default** now, as in Sefirah: on Android 12+ the palette comes from the
 * wallpaper. Below that, or with [dynamicColor] false, the Veyro cyan scheme above is used. Trust
 * state no longer depends on hue alone - every trust or connection indicator also carries text or
 * an icon - which was the reason dynamic colour used to be opt-in (ADR-031).
 */
@Composable
public fun VeyroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> VeyroDarkScheme
        else -> VeyroLightScheme
    }

    val gradients = remember(colorScheme) { gradientsFor(colorScheme) }

    CompositionLocalProvider(LocalVeyroGradients provides gradients) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = VeyroTypography,
            shapes = VeyroShapes,
            content = content,
        )
    }
}

/** Access the Veyro gradient brushes from any composable context. */
public object VeyroExtras {
    public val gradients: VeyroGradients
        @Composable
        get() = LocalVeyroGradients.current
}
