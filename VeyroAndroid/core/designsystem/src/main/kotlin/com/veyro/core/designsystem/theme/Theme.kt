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
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palettes
//
// Both schemes are the Material 3 tonal-spot palette generated from Veyro's cyan seed (#00D9FF).
// They apply below Android 12, and on any version when dynamic colour is switched off.

private val CyanLight: ColorScheme = lightColorScheme(
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
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FAFD),
    onBackground = Color(0xFF171C1F),
    surface = Color(0xFFF6FAFD),
    onSurface = Color(0xFF171C1F),
    surfaceVariant = Color(0xFFDCE4E9),
    onSurfaceVariant = Color(0xFF40484C),
    surfaceTint = Color(0xFF006782),
    inverseSurface = Color(0xFF2C3134),
    inverseOnSurface = Color(0xFFEDF1F5),
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

private val CyanDark: ColorScheme = darkColorScheme(
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
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1417),
    onBackground = Color(0xFFDEE3E6),
    surface = Color(0xFF0F1417),
    onSurface = Color(0xFFDEE3E6),
    surfaceVariant = Color(0xFF40484C),
    onSurfaceVariant = Color(0xFFC0C8CD),
    surfaceTint = Color(0xFF5FD4FD),
    inverseSurface = Color(0xFFDEE3E6),
    inverseOnSurface = Color(0xFF2C3134),
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

/** Shapes for the whole app. Veyro uses the Material 3 defaults; the name lets screens refer to it. */
public val VeyroShapes: Shapes = Shapes()

// Gradient brushes

/**
 * Brushes that older components still read through [VeyroExtras]. They are derived from the
 * active [ColorScheme], so under dynamic colour they follow the wallpaper like everything else.
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

/** A tint that fades to nothing towards the bottom. */
private fun fadingGlow(tint: Color): Brush =
    Brush.verticalGradient(listOf(tint.copy(alpha = GLOW_ALPHA), Color.Transparent))

/** A single colour expressed as a vertical brush, for call sites that expect a [Brush]. */
private fun flatVertical(color: Color): Brush = Brush.verticalGradient(listOf(color, color))

/** A single colour expressed as a horizontal brush. */
private fun flatHorizontal(color: Color): Brush = Brush.horizontalGradient(listOf(color, color))

private const val GLOW_ALPHA = 0.18f

private fun buildGradients(scheme: ColorScheme): VeyroGradients = VeyroGradients(
    cyanGlow = fadingGlow(scheme.primary),
    blueGlow = fadingGlow(scheme.secondary),
    violetGlow = fadingGlow(scheme.tertiary),
    card = flatVertical(scheme.surfaceContainerLow),
    cardSelected = flatVertical(scheme.primaryContainer),
    background = flatVertical(scheme.background),
    progressTrack = flatHorizontal(scheme.surfaceContainerHighest),
    progressFill = flatHorizontal(scheme.primary),
)

public val LocalVeyroGradients: ProvidableCompositionLocal<VeyroGradients> =
    staticCompositionLocalOf { buildGradients(CyanDark) }

// Root theme

/**
 * Wraps the app in Veyro's Material 3 theme.
 *
 * With [dynamicColor] on (the default) and Android 12 or newer, the palette is taken from the
 * wallpaper. Otherwise the cyan palettes above are used. Trust and connection state is never
 * conveyed by hue alone, because every such indicator also carries text or an icon (ADR-031).
 */
@Composable
public fun VeyroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val useWallpaperColors = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        useWallpaperColors && darkTheme -> dynamicDarkColorScheme(context)
        useWallpaperColors -> dynamicLightColorScheme(context)
        darkTheme -> CyanDark
        else -> CyanLight
    }
    val gradients = remember(colorScheme) { buildGradients(colorScheme) }

    CompositionLocalProvider(LocalVeyroGradients provides gradients) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = VeyroTypography,
            shapes = VeyroShapes,
            content = content,
        )
    }
}

/** Entry point to the Veyro-specific theme values that Material's own theme does not carry. */
public object VeyroExtras {
    public val gradients: VeyroGradients
        @Composable
        get() = LocalVeyroGradients.current
}
