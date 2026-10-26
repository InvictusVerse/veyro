// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/home/components/MediaCard.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.home.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.components.FlowingPageIndicator
import com.veyro.core.designsystem.components.WavySlider
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.link.LinkMessage
import kotlinx.coroutines.delay
import java.util.Base64
import java.util.Locale

/**
 * What is playing on the connected PC (ADR-026): one page per media session, the playing one
 * shown first.
 *
 * Veyro changes: driven by `mediaState` / `mediaCmd` on the paired link instead of Sefirah's
 * PlaybackInfo; position is extrapolated from the sender's `positionMs` + `ts` sample; the seek
 * bar only moves the track when the session says it can seek, and skip buttons follow
 * canPrev / canNext. No Coil: artwork is decoded once with BitmapFactory.
 */
@Composable
internal fun MediaCard(
    sessions: List<LinkMessage.MediaSessionInfo>,
    onCommand: (action: String, id: String?, value: Long?) -> Unit,
) {
    if (sessions.isEmpty()) return

    val activeSessionIndex = sessions.indexOfFirst { it.playing }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = activeSessionIndex) { sessions.size }

    // When the playing session changes, scroll to it, unless the page shown is also playing.
    LaunchedEffect(sessions) {
        val newActiveIndex = sessions.indexOfFirst { it.playing }
        if (newActiveIndex >= 0) {
            val currentPageIsPlaying = pagerState.currentPage < sessions.size &&
                sessions[pagerState.currentPage].playing
            if (newActiveIndex != pagerState.currentPage && !currentPageIsPlaying) {
                pagerState.animateScrollToPage(newActiveIndex)
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 16.dp,
        ) { page ->
            val session = sessions[page]
            PlaybackSession(session = session, onCommand = onCommand)
        }

        if (sessions.size > 1) {
            FlowingPageIndicator(
                pageCount = sessions.size,
                currentPage = pagerState.currentPage,
                currentPageOffset = pagerState.currentPageOffsetFraction,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun PlaybackSession(
    session: LinkMessage.MediaSessionInfo,
    onCommand: (action: String, id: String?, value: Long?) -> Unit,
) {
    Card(shape = MaterialTheme.shapes.large) {
        Box(Modifier.fillMaxWidth()) {
            val artwork = remember(session.art) { decodeArtwork(session.art) }
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    modifier = Modifier
                        .matchParentSize()
                        .clip(MaterialTheme.shapes.large),
                    contentScale = ContentScale.Crop,
                )
            }

            // Overlay content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // App name
                Text(
                    text = session.app ?: "On your PC",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Title and artist info
                Text(
                    text = session.title ?: "Unknown title",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = session.artist ?: "Unknown artist",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val duration = session.durationMs?.takeIf { it > 0 }
                if (duration != null) {
                    SeekBar(session = session, durationMs = duration, onCommand = onCommand)
                }

                // Control buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { onCommand(LinkMessage.MediaCommand.PREV, session.id, null) },
                        enabled = session.canPrev,
                    ) {
                        Icon(
                            imageVector = VeyroIcons.SkipPrevious,
                            contentDescription = "Previous track",
                            tint = if (session.canPrev) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    }

                    FilledIconButton(
                        onClick = { onCommand(LinkMessage.MediaCommand.TOGGLE, session.id, null) },
                        modifier = Modifier.size(width = 72.dp, height = 48.dp),
                    ) {
                        Icon(
                            imageVector = if (session.playing) VeyroIcons.Pause else VeyroIcons.PlayArrow,
                            contentDescription = if (session.playing) "Pause" else "Play",
                        )
                    }

                    IconButton(
                        onClick = { onCommand(LinkMessage.MediaCommand.NEXT, session.id, null) },
                        enabled = session.canNext,
                    ) {
                        Icon(
                            imageVector = VeyroIcons.SkipNext,
                            contentDescription = "Next track",
                            tint = if (session.canNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekBar(
    session: LinkMessage.MediaSessionInfo,
    durationMs: Long,
    onCommand: (action: String, id: String?, value: Long?) -> Unit,
) {
    // Position is not streamed: tick a local clock while playing and extrapolate from the sample.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.playing, session.ts, session.positionMs) {
        now = System.currentTimeMillis()
        while (session.playing) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    val base = session.positionMs ?: 0L
    val sampledAt = session.ts
    val position = (if (session.playing && sampledAt != null) base + (now - sampledAt) else base)
        .coerceIn(0L, durationMs)

    // Track if user is currently dragging the slider
    var dragging by remember(session.id) { mutableStateOf<Float?>(null) }
    val thumbInteractionSource = remember { MutableInteractionSource() }
    val shown = dragging?.let { (it * durationMs).toLong() } ?: position

    WavySlider(
        value = dragging ?: (position.toFloat() / durationMs).coerceIn(0f, 1f),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { onCommand(LinkMessage.MediaCommand.SEEK, session.id, (it * durationMs).toLong()) }
            dragging = null
        },
        enabled = session.canSeek,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Playback position" },
        interactionSource = thumbInteractionSource,
        isPlaying = session.playing,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = formatTime(shown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = formatTime(durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun decodeArtwork(art: String?): ImageBitmap? = art?.let {
    runCatching {
        val bytes = Base64.getDecoder().decode(it)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

private fun formatTime(timeInMillis: Long): String {
    val totalSeconds = (timeInMillis / 1000).toInt().coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
