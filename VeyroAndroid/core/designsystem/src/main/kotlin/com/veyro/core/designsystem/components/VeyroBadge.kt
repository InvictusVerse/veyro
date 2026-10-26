package com.veyro.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanContainer
import com.veyro.core.designsystem.theme.VeyroRed
import com.veyro.core.designsystem.theme.VeyroRedContainer
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTealContainer
import com.veyro.core.designsystem.theme.VeyroTextSecondary

/**
 * A rounded-rect icon badge with a tinted background.
 *
 * @param size  Total badge size. Default 48.dp.
 * @param iconSize  Inner icon size. Default 24.dp.
 */
@Composable
public fun VeyroIconBadge(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Pre-built badge for a trusted/connected device. */
@Composable
public fun VeyroConnectedBadge(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    VeyroIconBadge(
        icon = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        size = size,
        iconSize = iconSize,
        backgroundColor = VeyroCyanContainer,
        iconColor = VeyroCyan,
    )
}

/** Pre-built badge for an error/problem state. */
@Composable
public fun VeyroErrorBadge(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    VeyroIconBadge(
        icon = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        size = size,
        iconSize = iconSize,
        backgroundColor = VeyroRedContainer,
        iconColor = VeyroRed,
    )
}

/** Pre-built badge for a verified/success state. */
@Composable
public fun VeyroSuccessBadge(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    VeyroIconBadge(
        icon = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        size = size,
        iconSize = iconSize,
        backgroundColor = VeyroTealContainer,
        iconColor = VeyroTeal,
    )
}

/** Neutral/info badge. */
@Composable
public fun VeyroNeutralBadge(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    VeyroIconBadge(
        icon = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        size = size,
        iconSize = iconSize,
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
        iconColor = VeyroTextSecondary,
    )
}
