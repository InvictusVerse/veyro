package com.veyro.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.model.ActiveTransfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferStage
import com.veyro.ui.format.formatBytes
import com.veyro.ui.format.formatSpeed
import kotlin.math.abs
import kotlin.math.roundToInt

private const val FLING_VELOCITY = 1_800f

/**
 * The live transfer banner (ADR-038 point 4), floating at the top of every screen.
 *
 * One stable composable: it is keyed by transfer id, so progress updates of the same transfer
 * change values in place instead of rebuilding the card; the bar animates towards a quantised
 * fraction read through `derivedStateOf`, so a burst of progress events does not recompose the
 * card on every byte. What is shown, and when it leaves, is decided by [TransferBannerModel]:
 * success slides out by itself after about 3 s, a failure stays. Swiping it sideways or the close
 * button hides it; a running transfer keeps going (tap the banner for details and Cancel).
 *
 * It floats over the content rather than pushing it down, so nothing below jumps when it
 * appears or leaves. It carries its own status-bar inset.
 */
@Composable
public fun ActiveTransferBanner(
    activeTransfer: ActiveTransfer?,
    onDismiss: () -> Unit,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Keeps the last transfer on screen while the exit animation plays.
    var last by remember { mutableStateOf<ActiveTransfer?>(null) }
    LaunchedEffect(activeTransfer) { if (activeTransfer != null) last = activeTransfer }
    val display = activeTransfer ?: last

    AnimatedVisibility(
        visible = activeTransfer != null,
        enter = slideInVertically(tween(260)) { -it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(240)) { -it } + fadeOut(tween(200)),
        modifier = modifier,
    ) {
        if (display == null) return@AnimatedVisibility
        key(display.transferId) {
            SwipeToHide(onDismiss = onDismiss) {
                BannerCard(transfer = display, onDismiss = onDismiss, onClick = onClick)
            }
        }
    }
}

/** Horizontal swipe with a spring back, dismissing past a third of the width or on a fling. */
@Composable
private fun SwipeToHide(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    val dismiss by rememberUpdatedState(onDismiss)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .offset { IntOffset(drag.roundToInt(), 0) }
            .graphicsLayer { alpha = 1f - (abs(drag) / width).coerceIn(0f, 0.6f) }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> drag += delta },
                onDragStopped = { velocity ->
                    val farEnough = abs(drag) > width / 3f || abs(velocity) > FLING_VELOCITY
                    if (farEnough) {
                        val direction = if (drag + velocity / 10f >= 0f) 1f else -1f
                        animate(drag, direction * width, animationSpec = tween(180)) { value, _ -> drag = value }
                        dismiss()
                    } else {
                        animate(drag, 0f) { value, _ -> drag = value }
                    }
                },
            ),
        contentAlignment = Alignment.TopCenter,
    ) { content() }
}

@Composable
private fun BannerCard(transfer: ActiveTransfer, onDismiss: () -> Unit, onClick: (() -> Unit)?) {
    val current by rememberUpdatedState(transfer)
    // Recomposes the bar only when the fraction moves by a visible step (0.5 %).
    val target by remember {
        derivedStateOf {
            val t = current
            val raw = if (t.stage == TransferStage.COMPLETED) 1f else t.overallFraction
            (raw * 200f).roundToInt() / 200f
        }
    }
    val progress by animateFloatAsState(targetValue = target, animationSpec = tween(320), label = "bannerProgress")
    val indeterminate by remember {
        derivedStateOf {
            current.stage == TransferStage.PREPARING ||
                current.stage == TransferStage.CONNECTING ||
                current.stage == TransferStage.AWAITING_CONSENT
        }
    }

    val failed = transfer.stage == TransferStage.FAILED
    val done = transfer.stage == TransferStage.COMPLETED
    val accent = when {
        failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val title = bannerTitle(transfer)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Show transfer details", onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        failed -> Icons.Default.Warning
                        done -> Icons.Default.CheckCircle
                        transfer.direction == TransferDirection.SEND -> VeyroIcons.Upload
                        else -> VeyroIcons.Download
                    },
                    contentDescription = null,
                    tint = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$title. ${bannerDetail(transfer)}"
                    },
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = transfer.currentFileName.ifEmpty { if (transfer.totalFiles > 1) "${transfer.totalFiles} files" else "Files" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Spacer(Modifier.height(8.dp))
                if (indeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = accent,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = accent,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = bannerDetail(transfer),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = if (transfer.isTerminal) "Dismiss" else "Hide banner",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** "Sending to Office PC", "Received from Office PC", "Could not send to Office PC". */
internal fun bannerTitle(transfer: ActiveTransfer): String {
    val peer = transfer.peerDisplayName ?: "your device"
    val send = transfer.direction == TransferDirection.SEND
    return when (transfer.stage) {
        TransferStage.COMPLETED -> if (send) "Sent to $peer" else "Received from $peer"
        TransferStage.FAILED -> if (send) "Could not send to $peer" else "Could not receive from $peer"
        TransferStage.CANCELLED -> if (send) "Stopped sending to $peer" else "Stopped receiving from $peer"
        TransferStage.AWAITING_CONSENT -> if (send) "Waiting for $peer to accept" else "$peer wants to send files"
        else -> if (send) "Sending to $peer" else "Receiving from $peer"
    }
}

/** The line under the bar: bytes and speed while running, the outcome when done. */
internal fun bannerDetail(transfer: ActiveTransfer): String = when (transfer.stage) {
    TransferStage.FAILED -> transfer.errorMessage?.takeIf { it.isNotBlank() } ?: transfer.effectiveStatusText
    TransferStage.CANCELLED, TransferStage.COMPLETED -> transfer.effectiveStatusText
    TransferStage.PREPARING, TransferStage.CONNECTING, TransferStage.AWAITING_CONSENT -> transfer.effectiveStatusText
    else -> buildString {
        append(formatBytes(transfer.bytesTransferred)).append(" of ").append(formatBytes(transfer.totalBytes))
        if (transfer.bytesPerSecond > 0) append(", ").append(formatSpeed(transfer.bytesPerSecond))
        if (transfer.totalFiles > 1) append(", file ").append(transfer.currentFileIndex + 1).append(" of ").append(transfer.totalFiles)
    }
}
