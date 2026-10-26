// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/ui/theme/Type.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Sefirah's typography: the Material 3 type scale with one override, bodyLarge. Veyro's earlier
 * bold display/headline scale is gone so text sizes match Sefirah's screens.
 */
internal val VeyroTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * Monospace style for device fingerprints and pairing PINs.
 *
 * This is a correctness concern, not decoration: a fingerprint comparison between two screens is
 * only reliable if the glyphs are unambiguous and the grouping is stable (REQ-I05, REQ-U04).
 */
public val VeyroCodeStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 18.sp,
    letterSpacing = 2.sp,
)

/**
 * Large monospace for the 6-digit pairing PIN display.
 * Slightly larger and bolder than VeyroCodeStyle to fill the pairing dialog.
 */
public val VeyroPinStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 40.sp,
    letterSpacing = 6.sp,
)

/**
 * Metric display style for bytes / transfer speed — large and readable.
 */
public val VeyroMetricStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp,
    letterSpacing = 0.sp,
)

/**
 * Smaller sub-metric for secondary numbers (e.g. "of 2.4 MB").
 */
public val VeyroMetricSubStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    letterSpacing = 0.2.sp,
)
