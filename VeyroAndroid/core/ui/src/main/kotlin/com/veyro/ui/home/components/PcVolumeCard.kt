// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/home/components/AudioDeviceBottomSheet.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.home.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons

/**
 * The PC's master volume, from Sefirah's "selected audio device" card.
 *
 * Veyro changes: the PC reports one master volume in `mediaState`, so there is no device list and
 * no bottom sheet; the card is not clickable. Mute and volume go out as `mediaCmd`.
 */
@Composable
internal fun PcVolumeCard(
    pcName: String,
    volume: Int,
    muted: Boolean,
    onVolumeChange: (Int) -> Unit,
    onToggleMute: () -> Unit,
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "$pcName volume",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = VeyroIcons.ComputerSound,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            VolumeSlider(
                volume = volume.coerceIn(0, 100).toFloat(),
                onVolumeChange = { onVolumeChange(it.toInt().coerceIn(0, 100)) },
                isMuted = muted,
                toggleMute = onToggleMute,
            )
        }
    }
}
