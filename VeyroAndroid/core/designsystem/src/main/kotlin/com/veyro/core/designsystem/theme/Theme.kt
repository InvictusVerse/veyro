package com.veyro.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ---------------------------------------------------------------------------
// Color schemes
// ---------------------------------------------------------------------------

private val VeyroDarkScheme = darkColorScheme(
    // Primary — neon cyan
    primary = VeyroCyan,
    onPrimary = VeyroTextOnPrimary,
    primaryContainer = VeyroCyanContainer,
    onPrimaryContainer = VeyroCyanGlow,

    // Secondary — electric blue
    secondary = VeyroBlue,
    onSecondary = VeyroTextOnPrimary,
    secondaryContainer = VeyroBlueContainer,
    onSecondaryContainer = VeyroBlueGlow,

    // Tertiary — teal for verified / trust states
    tertiary = VeyroTeal,
    onTertiary = VeyroTextOnPrimary,
    tertiaryContainer = VeyroTealContainer,
    onTertiaryContainer = VeyroTealGlow,

    // Background
    background = VeyroVoid,
    onBackground = VeyroTextPrimary,

    // Surface — glass dark
    surface = VeyroGlass,
    onSurface = VeyroTextPrimary,
    surfaceVariant = VeyroGlassLight,
    onSurfaceVariant = VeyroTextSecondary,

    // Outline
    outline = VeyroGlassBorder,
    outlineVariant = VeyroGlassBorderLight,

    // Error
    error = VeyroRed,
    onError = Color.White,
    errorContainer = VeyroRedContainer,
    onErrorContainer = VeyroRedGlow,

    // Inverse
    inverseSurface = VeyroMist,
    inverseOnSurface = VeyroVoid,
    inversePrimary = VeyroCyanDark,

    // Scrim
    scrim = VeyroVoid.copy(alpha = 0.85f),
)

private val VeyroLightScheme = lightColorScheme(
    primary = VeyroCyanDark,
    onPrimary = Color.White,
    primaryContainer = VeyroCyanContainerLight,
    onPrimaryContainer = VeyroCyanDark,

    secondary = VeyroBlue,
    onSecondary = Color.White,
    secondaryContainer = VeyroBlueContainerLight,
    onSecondaryContainer = VeyroBlueDark,

    tertiary = VeyroTeal,
    onTertiary = Color.White,
    tertiaryContainer = VeyroTealContainerLight,
    onTertiaryContainer = VeyroTeal,

    background = VeyroMist,
    onBackground = VeyroVoid,

    surface = VeyroMistGlass,
    onSurface = VeyroVoid,
    surfaceVariant = VeyroMistElevated,
    onSurfaceVariant = Color(0xFF3A4A60),

    outline = Color(0xFFBBC8D8),
    outlineVariant = Color(0xFFE0E8F0),

    error = VeyroRed,
    onError = Color.White,
    errorContainer = VeyroRedContainer,
    onErrorContainer = VeyroRedGlow,

    inverseSurface = VeyroVoid,
    inverseOnSurface = VeyroTextPrimary,
    inversePrimary = VeyroCyanGlow,

    scrim = VeyroVoid.copy(alpha = 0.85f),
)

// ---------------------------------------------------------------------------
// Veyro extras — gradients and glow brushes
// ---------------------------------------------------------------------------

/**
 * Gradients available as Material 3 composition locals so screens can pull them
 * without importing separate file.
 */
@Immutable
data class VeyroGradients(
    val cyanGlow: Brush,
    val blueGlow: Brush,
    val violetGlow: Brush,
    val card: Brush,
    val cardSelected: Brush,
    val background: Brush,
    val progressTrack: Brush,
    val progressFill: Brush,
)

private val DarkVeyroGradients = VeyroGradients(
    cyanGlow = Brush.verticalGradient(
        colors = listOf(VeyroCyanGlow.copy(alpha = 0.3f), Color.Transparent),
    ),
    blueGlow = Brush.verticalGradient(
        colors = listOf(VeyroBlueGlow.copy(alpha = 0.25f), Color.Transparent),
    ),
    violetGlow = Brush.verticalGradient(
        colors = listOf(VeyroVioletGlow.copy(alpha = 0.3f), Color.Transparent),
    ),
    card = Brush.verticalGradient(
        colors = listOf(VeyroGlassLight, VeyroGlass),
    ),
    cardSelected = Brush.verticalGradient(
        colors = listOf(VeyroCyanContainer.copy(alpha = 0.6f), VeyroCyanContainer.copy(alpha = 0.2f)),
    ),
    background = Brush.verticalGradient(
        colors = listOf(VeyroDeep, VeyroVoid),
    ),
    progressTrack = Brush.horizontalGradient(
        colors = listOf(VeyroGlassBorder, VeyroGlassBorder),
    ),
    progressFill = Brush.horizontalGradient(
        colors = listOf(VeyroCyan, VeyroCyanGlow),
    ),
)

private val LightVeyroGradients = VeyroGradients(
    cyanGlow = Brush.verticalGradient(
        colors = listOf(VeyroCyan.copy(alpha = 0.2f), Color.Transparent),
    ),
    blueGlow = Brush.verticalGradient(
        colors = listOf(VeyroBlue.copy(alpha = 0.15f), Color.Transparent),
    ),
    violetGlow = Brush.verticalGradient(
        colors = listOf(VeyroViolet.copy(alpha = 0.2f), Color.Transparent),
    ),
    card = Brush.verticalGradient(
        colors = listOf(VeyroMistGlass, VeyroMistElevated),
    ),
    cardSelected = Brush.verticalGradient(
        colors = listOf(VeyroCyanContainerLight.copy(alpha = 0.7f), VeyroCyanContainerLight.copy(alpha = 0.3f)),
    ),
    background = Brush.verticalGradient(
        colors = listOf(VeyroMist, VeyroMistElevated),
    ),
    progressTrack = Brush.horizontalGradient(
        colors = listOf(Color(0xFFD0DCE8), Color(0xFFE0E8F0)),
    ),
    progressFill = Brush.horizontalGradient(
        colors = listOf(VeyroCyanDark, VeyroCyan),
    ),
)

val LocalVeyroGradients = staticCompositionLocalOf { DarkVeyroGradients }

// ---------------------------------------------------------------------------
// Root theme
// ---------------------------------------------------------------------------

/**
 * Root theme.
 *
 * Dynamic colour is opt-in rather than default: Veyro leans on a consistent accent to signal
 * trust state, and a wallpaper-derived palette can collapse the difference between "trusted" and
 * "unknown" into two similar hues.
 */
@Composable
public fun VeyroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> VeyroDarkScheme
        else -> VeyroLightScheme
    }

    val gradients = if (darkTheme) DarkVeyroGradients else LightVeyroGradients

    CompositionLocalProvider(
        LocalVeyroGradients provides gradients,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = VeyroTypography,
            content = content,
        )
    }
}

/** Access the Veyro gradient brushes from any composable context. */
object VeyroExtras {
    val gradients: VeyroGradients
        @Composable
        get() = LocalVeyroGradients.current
}
