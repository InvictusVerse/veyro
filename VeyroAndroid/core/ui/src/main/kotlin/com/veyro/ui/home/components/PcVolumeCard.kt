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
 * The connected PC's master volume. The PC reports a single volume and mute state in `mediaState`
 * (there is no list of audio devices), so the card is just a title and a [VolumeSlider]; mute and
 * level changes go back as `mediaCmd`. [volume] is a percentage and is kept within 0..100.
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
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
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
                level = volume.coerceIn(0, 100).toFloat(),
                muted = muted,
                onLevelSet = { onVolumeChange(it.toInt().coerceIn(0, 100)) },
                onToggleMute = onToggleMute,
            )
        }
    }
}
