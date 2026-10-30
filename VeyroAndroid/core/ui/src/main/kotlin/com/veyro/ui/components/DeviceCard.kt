// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/common/components/DeviceCard.kt, GPL-3.0.
// Modified for Veyro. The list card itself is now com.veyro.ui.devices.PairedDeviceCard.
package com.veyro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.model.Platform

/** Platform icon in a tinted circle; Veyro's stand-in for Sefirah's device avatar. */
@Composable
public fun DeviceAvatar(platform: Platform, size: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = platformIcon(platform),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((size / 2).dp),
        )
    }
}

@Composable
internal fun platformIcon(platform: Platform): ImageVector = when (platform) {
    Platform.ANDROID -> VeyroIcons.Mobile
    Platform.WINDOWS, Platform.UNKNOWN -> VeyroIcons.Desktop
}
