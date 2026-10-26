package com.veyro.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanContainer
import com.veyro.core.designsystem.theme.VeyroCyanMuted
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.link.LinkMessage
import kotlinx.coroutines.delay
import java.util.Base64

/**
 * Compact "PC media" card (ADR-026): what is playing on the connected PC, with transport
 * controls, a seek bar when the session supports seeking, and the PC's master volume.
 *
 * Position is not streamed. While playing, it is extrapolated from `positionMs` + `ts`.
 */
@Composable
internal fun PcMediaCard(
    session: LinkMessage.MediaSessionInfo,
    volume: Int?,
    onCommand: (action: String, id: String?, value: Long?) -> Unit,
) {
    VeyroCard(glow = false) {
        Text(
            text = "PC MEDIA" + (session.app?.let { "  \u00b7  $it" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = VeyroCyanMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(session.art)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title ?: "Unknown title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                session.artist?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = VeyroTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onCommand(LinkMessage.MediaCommand.PREV, session.id, null) },
                enabled = session.canPrev,
            ) { Icon(SkipPreviousIcon, contentDescription = "Previous track") }

            IconButton(
                onClick = { onCommand(LinkMessage.MediaCommand.TOGGLE, session.id, null) },
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(VeyroCyanContainer),
            ) {
                Icon(
                    imageVector = if (session.playing) PauseIcon else Icons.Default.PlayArrow,
                    contentDescription = if (session.playing) "Pause" else "Play",
                    tint = VeyroCyan,
                )
            }

            IconButton(
                onClick = { onCommand(LinkMessage.MediaCommand.NEXT, session.id, null) },
                enabled = session.canNext,
            ) { Icon(SkipNextIcon, contentDescription = "Next track") }
        }

        val duration = session.durationMs?.takeIf { it > 0 }
        if (session.canSeek && duration != null) {
            SeekBar(session, duration) { target -> onCommand(LinkMessage.MediaCommand.SEEK, session.id, target) }
        }

        if (volume != null) {
            VolumeSlider(volume) { target -> onCommand(LinkMessage.MediaCommand.VOLUME, null, target.toLong()) }
        }
    }
}

@Composable
private fun Artwork(art: String?) {
    val image: ImageBitmap? = remember(art) {
        art?.let {
            runCatching {
                val bytes = Base64.getDecoder().decode(it)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(12.dp)
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "Album art",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(shape),
        )
    } else {
        Box(
            modifier = Modifier.size(56.dp).clip(shape).background(VeyroCyanContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = VeyroCyan)
        }
    }
}

@Composable
private fun SeekBar(
    session: LinkMessage.MediaSessionInfo,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.playing, session.ts, session.positionMs) {
        while (session.playing) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val base = session.positionMs ?: 0L
    val sampledAt = session.ts
    val position = (if (session.playing && sampledAt != null) base + (now - sampledAt) else base)
        .coerceIn(0L, durationMs)

    var dragging by remember(session.id) { mutableStateOf<Float?>(null) }

    Slider(
        value = dragging ?: position.toFloat(),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { onSeek(it.toLong()) }
            dragging = null
        },
        valueRange = 0f..durationMs.toFloat(),
        colors = SliderDefaults.colors(thumbColor = VeyroCyan, activeTrackColor = VeyroCyan),
        modifier = Modifier.semantics { contentDescription = "Playback position" },
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = formatClock((dragging?.toLong() ?: position)),
            style = MaterialTheme.typography.labelSmall,
            color = VeyroTextSecondary,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = formatClock(durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = VeyroTextSecondary,
        )
    }
}

@Composable
private fun VolumeSlider(volume: Int, onVolume: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Spacer(modifier = Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "PC volume",
            style = MaterialTheme.typography.labelMedium,
            color = VeyroTextSecondary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Slider(
            value = dragging ?: volume.coerceIn(0, 100).toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onVolume(it.toInt().coerceIn(0, 100)) }
                dragging = null
            },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = VeyroCyan, activeTrackColor = VeyroCyan),
            modifier = Modifier.weight(1f).semantics { contentDescription = "PC volume" },
        )
    }
}

private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

// The core icon set has no pause or skip glyphs, and the extended set is ruled out (ADR-016),
// so the three needed are drawn here from the standard 24dp Material paths.

private val PauseIcon: ImageVector by lazy {
    materialIcon(name = "Veyro.Pause") {
        materialPath {
            moveTo(6f, 19f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineTo(6f)
            close()
            moveTo(14f, 5f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            close()
        }
    }
}

private val SkipNextIcon: ImageVector by lazy {
    materialIcon(name = "Veyro.SkipNext") {
        materialPath {
            moveTo(6f, 18f)
            lineToRelative(8.5f, -6f)
            lineTo(6f, 6f)
            close()
            moveTo(16f, 6f)
            verticalLineToRelative(12f)
            horizontalLineToRelative(2f)
            verticalLineTo(6f)
            close()
        }
    }
}

private val SkipPreviousIcon: ImageVector by lazy {
    materialIcon(name = "Veyro.SkipPrevious") {
        materialPath {
            moveTo(6f, 6f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(12f)
            horizontalLineTo(6f)
            close()
            moveTo(9.5f, 12f)
            lineToRelative(8.5f, 6f)
            verticalLineTo(6f)
            close()
        }
    }
}
