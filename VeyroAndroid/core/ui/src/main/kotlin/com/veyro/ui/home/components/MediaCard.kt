package com.veyro.ui.home.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.components.FlowingPageIndicator
import com.veyro.core.designsystem.components.WavySlider
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.link.PeerMessage
import com.veyro.ui.components.rememberTickingClock
import java.util.Base64
import java.util.Locale

/** Sends a transport command to the PC: the action, the target session id and an optional value. */
private typealias MediaCommandSink = (action: String, id: String?, value: Long?) -> Unit

/**
 * What is playing on the connected PC (ADR-026). Each media session gets a swipeable page; when a
 * different session starts playing, the pager moves to it unless the page on screen is playing too.
 *
 * The PC sends `mediaState` with a position sample and the time it was taken, so the progress is
 * extrapolated here while a session plays instead of being streamed. Commands go back as `mediaCmd`.
 * Artwork arrives inline as base64 and is decoded once per picture with BitmapFactory.
 */
@Composable
internal fun MediaCard(
    sessions: List<PeerMessage.MediaSessionInfo>,
    onCommand: (action: String, id: String?, value: Long?) -> Unit,
) {
    if (sessions.isEmpty()) return

    val pagerState = rememberPagerState(initialPage = sessions.indexOfFirst { it.playing }.coerceAtLeast(0)) {
        sessions.size
    }
    ScrollToPlayingSession(sessions, pagerState)

    Box(Modifier.fillMaxWidth()) {
        HorizontalPager(state = pagerState, pageSpacing = 16.dp) { index ->
            NowPlayingPage(session = sessions[index], onCommand = onCommand)
        }
        if (sessions.size > 1) {
            FlowingPageIndicator(
                pageCount = sessions.size,
                currentPage = pagerState.currentPage,
                currentPageOffset = pagerState.currentPageOffsetFraction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun ScrollToPlayingSession(sessions: List<PeerMessage.MediaSessionInfo>, pagerState: PagerState) {
    LaunchedEffect(sessions) {
        val playingIndex = sessions.indexOfFirst { it.playing }
        val shownIsPlaying = sessions.getOrNull(pagerState.currentPage)?.playing == true
        if (playingIndex >= 0 && playingIndex != pagerState.currentPage && !shownIsPlaying) {
            pagerState.animateScrollToPage(playingIndex)
        }
    }
}

@Composable
private fun NowPlayingPage(session: PeerMessage.MediaSessionInfo, onCommand: MediaCommandSink) {
    Card(shape = MaterialTheme.shapes.large) {
        Box(Modifier.fillMaxWidth()) {
            val artwork = remember(session.art) { decodeArtwork(session.art) }
            if (artwork != null) Backdrop(artwork)

            // A translucent surface over the artwork keeps the text readable on any picture.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                TrackLine(session.app ?: "Playing now", MaterialTheme.typography.bodyMedium, dimmed = true)
                TrackLine(session.title ?: "Unknown title", MaterialTheme.typography.titleMedium, dimmed = false)
                TrackLine(session.artist ?: "Unknown artist", MaterialTheme.typography.bodyMedium, dimmed = true)

                session.durationMs?.takeIf { it > 0 }?.let { duration ->
                    ProgressSection(session, duration, onCommand)
                }

                TransportControls(session, onCommand)
            }
        }
    }
}

@Composable
private fun BoxScope.Backdrop(artwork: ImageBitmap) {
    Image(
        bitmap = artwork,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .matchParentSize()
            .clip(MaterialTheme.shapes.large),
    )
}

@Composable
private fun TrackLine(text: String, style: TextStyle, dimmed: Boolean) {
    val color = MaterialTheme.colorScheme.onSurface
    Text(
        text = text,
        style = style,
        color = if (dimmed) color.copy(alpha = 0.7f) else color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TransportControls(session: PeerMessage.MediaSessionInfo, onCommand: MediaCommandSink) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(
            icon = VeyroIcons.SkipPrevious,
            description = "Previous track",
            enabled = session.canPrev,
            onClick = { onCommand(PeerMessage.MediaCommand.PREV, session.id, null) },
        )
        FilledIconButton(
            onClick = { onCommand(PeerMessage.MediaCommand.TOGGLE, session.id, null) },
            modifier = Modifier.size(width = 72.dp, height = 48.dp),
        ) {
            Icon(
                imageVector = if (session.playing) VeyroIcons.Pause else VeyroIcons.PlayArrow,
                contentDescription = if (session.playing) "Pause" else "Play",
            )
        }
        SkipButton(
            icon = VeyroIcons.SkipNext,
            description = "Next track",
            enabled = session.canNext,
            onClick = { onCommand(PeerMessage.MediaCommand.NEXT, session.id, null) },
        )
    }
}

@Composable
private fun SkipButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ProgressSection(session: PeerMessage.MediaSessionInfo, durationMs: Long, onCommand: MediaCommandSink) {
    // The clock only ticks while the session plays and Veyro is on screen, so a track playing on
    // the PC does not keep waking a phone that has Veyro in the background.
    val now = rememberTickingClock(
        periodMillis = 250,
        enabled = session.playing,
        key = session.ts to session.positionMs,
    )
    val position = estimatePosition(session, now, durationMs)

    // While the thumb is held, the fraction under the finger replaces the PC's position.
    var dragFraction by remember(session.id) { mutableStateOf<Float?>(null) }
    val shownMs = dragFraction?.let { (it * durationMs).toLong() } ?: position

    WavySlider(
        value = dragFraction ?: (position.toFloat() / durationMs).coerceIn(0f, 1f),
        onValueChange = { dragFraction = it },
        onValueChangeFinished = {
            dragFraction?.let { onCommand(PeerMessage.MediaCommand.SEEK, session.id, (it * durationMs).toLong()) }
            dragFraction = null
        },
        enabled = session.canSeek,
        isPlaying = session.playing,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Playback position" },
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TimeLabel(shownMs)
        TimeLabel(durationMs)
    }
}

@Composable
private fun TimeLabel(millis: Long) {
    Text(
        text = formatClock(millis),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * Where playback is now: the reported position plus the time since it was sampled, only while the
 * session plays. Stays inside 0..[durationMs].
 */
private fun estimatePosition(session: PeerMessage.MediaSessionInfo, nowMillis: Long, durationMs: Long): Long {
    val reported = session.positionMs ?: 0L
    val sampledAt = session.ts
    val advanced = if (session.playing && sampledAt != null) reported + (nowMillis - sampledAt) else reported
    return advanced.coerceIn(0L, durationMs)
}

private fun decodeArtwork(base64: String?): ImageBitmap? {
    if (base64 == null) return null
    return runCatching {
        val bytes = Base64.getDecoder().decode(base64)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

/** `m:ss`, or `h:mm:ss` from one hour on; negative input shows as zero. */
private fun formatClock(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    // Locale.US keeps ASCII digits whatever the phone's language is.
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}
