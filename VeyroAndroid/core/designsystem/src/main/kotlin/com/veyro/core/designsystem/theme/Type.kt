package com.veyro.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Veyro's text scale: stock Material 3 sizes everywhere, with a single deviation. Body text gets
 * 16 sp on a 24 sp line and slightly wider tracking so paragraphs in cards stay easy to read.
 */
internal val VeyroTypography: Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * Fixed-pitch style for device fingerprints.
 *
 * Two people comparing a fingerprint on two screens need glyphs that cannot be confused and a
 * grouping that does not reflow, which is why this is monospace (REQ-I05, REQ-U04).
 */
public val VeyroCodeStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 18.sp,
    letterSpacing = 2.sp,
)

/** Monospace style for the six-digit pairing PIN: bigger and bolder than [VeyroCodeStyle]. */
public val VeyroPinStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 40.sp,
    letterSpacing = 6.sp,
)

/** Headline number for a transfer size or speed. */
public val VeyroMetricStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp,
    letterSpacing = 0.sp,
)

/** Companion line under a [VeyroMetricStyle] number, for example "of 2.4 MB". */
public val VeyroMetricSubStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    letterSpacing = 0.2.sp,
)
