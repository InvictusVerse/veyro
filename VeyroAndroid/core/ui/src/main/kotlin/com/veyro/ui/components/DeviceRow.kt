package com.veyro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanContainer
import com.veyro.core.designsystem.theme.VeyroCyanGlow
import com.veyro.core.designsystem.theme.VeyroExtras
import com.veyro.core.designsystem.theme.VeyroGlass
import com.veyro.core.designsystem.theme.VeyroGlassBorder
import com.veyro.core.designsystem.theme.VeyroRed
import com.veyro.core.designsystem.theme.VeyroRedContainer
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTealContainer
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.model.PeerDevice
import com.veyro.core.model.Platform
import com.veyro.core.model.TrustState

private val DeviceIconShape = RoundedCornerShape(14.dp)

/**
 * Futuristic device row for the Home screen discovery list and Send screen target picker.
 *
 * Layout:
 *   [Avatar] [Name + subtitle + fingerprint]  [Trust chip]
 *
 * Selected state uses a subtle cyan glow border — the primary action is always obvious.
 */
@Composable
public fun DeviceRow(
    device: PeerDevice,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val shape = RoundedCornerShape(18.dp)

    val bgBrush = if (selected) {
        VeyroExtras.gradients.cardSelected
    } else {
        VeyroExtras.gradients.card
    }

    val borderColor = when {
        selected -> VeyroCyan.copy(alpha = 0.6f)
        device.trust == TrustState.TRUSTED -> VeyroGlassBorder.copy(alpha = 0.7f)
        else -> VeyroGlassBorder.copy(alpha = 0.4f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(brush = bgBrush, shape = shape)
            .border(
                width = if (selected) 1.5f.dp else 0.5f.dp,
                color = borderColor,
                shape = shape,
            )
            .clickable {}
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar
        DeviceAvatar(
            platform = device.platform,
            trust = device.trust,
        )

        Spacer(modifier = Modifier.width(14.dp))

        // Name + subtitle + fingerprint
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when (device.trust) {
                    TrustState.TRUSTED -> "Trusted · ${device.platform.name.lowercase().replaceFirstChar { it.uppercase() }}"
                    TrustState.KNOWN_UNPAIRED -> "Tap to pair"
                    TrustState.UNKNOWN -> "Discovered"
                    TrustState.REVOKED -> "Revoked device"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (device.trust) {
                    TrustState.TRUSTED -> VeyroCyan.copy(alpha = 0.8f)
                    TrustState.REVOKED -> VeyroRed.copy(alpha = 0.7f)
                    else -> VeyroTextSecondary
                },
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Trust chip
        TrustChip(trust = device.trust)
    }
}

@Composable
private fun DeviceAvatar(
    platform: Platform,
    trust: TrustState,
) {
    val icon: ImageVector = when (platform) {
        Platform.ANDROID -> Icons.Default.Phone
        Platform.WINDOWS -> Icons.Default.Home
        Platform.UNKNOWN -> Icons.Default.Star
    }

    val bgColor = when (trust) {
        TrustState.TRUSTED -> VeyroCyanContainer
        TrustState.REVOKED -> VeyroRedContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val iconColor = when (trust) {
        TrustState.TRUSTED -> VeyroCyan
        TrustState.REVOKED -> VeyroRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(DeviceIconShape)
            .background(bgColor)
            .then(
                if (trust == TrustState.TRUSTED) {
                    Modifier.border(1.dp, VeyroCyan.copy(alpha = 0.3f), DeviceIconShape)
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun TrustChip(trust: TrustState) {
    val bgColor: Color
    val textColor: Color
    val label: String

    when (trust) {
        TrustState.TRUSTED -> {
            bgColor = VeyroCyanContainer
            textColor = VeyroCyan
            label = "Paired"
        }
        TrustState.REVOKED -> {
            bgColor = VeyroRedContainer
            textColor = VeyroRed
            label = "Revoked"
        }
        TrustState.KNOWN_UNPAIRED -> {
            bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            textColor = VeyroTextSecondary
            label = "Nearby"
        }
        TrustState.UNKNOWN -> {
            bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            textColor = VeyroTextSecondary
            label = "Found"
        }
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = textColor,
        )
    }
}
