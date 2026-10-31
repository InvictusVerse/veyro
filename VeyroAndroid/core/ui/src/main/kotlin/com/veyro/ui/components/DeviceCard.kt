package com.veyro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.model.Platform

/**
 * A device's platform glyph on a round, faintly tinted disc. [size] is the diameter in dp; the
 * glyph takes the middle half of it. The paired-device list card is
 * `com.veyro.ui.devices.PairedDeviceCard`.
 */
@Composable
public fun DeviceAvatar(platform: Platform, size: Int, modifier: Modifier = Modifier) {
    Icon(
        imageVector = platformIcon(platform),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .size(size.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), CircleShape)
            .padding((size / 4f).dp),
    )
}

/** The glyph for a platform; an unknown platform is shown as a desktop. */
@Composable
internal fun platformIcon(platform: Platform): ImageVector = when (platform) {
    Platform.ANDROID -> VeyroIcons.Mobile
    Platform.WINDOWS, Platform.UNKNOWN -> VeyroIcons.Desktop
}
