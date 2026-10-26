package com.veyro.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.link.IncomingOffer
import com.veyro.core.link.TransferCoordinator
import com.veyro.core.model.fingerprint
import com.veyro.ui.format.FileKind
import com.veyro.ui.format.destinationLabel
import com.veyro.ui.format.fileKindOf
import com.veyro.ui.format.formatBytes
import com.veyro.ui.format.formatCountdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File

/**
 * The question asked on the phone when a paired PC sends files (REQ-T02 explicit consent).
 *
 * Design: a sheet anchored at the bottom, where the thumb already is, read top to bottom as
 * who / what / where / decide. The sender's name comes with its fingerprint, because identity is
 * the part of this question that matters. Its one expressive element is the fuse along the top
 * edge: it burns down over the same 60 s after which the offer is declined (the PC gives up at
 * the same moment), and turns to the error colour for the last ten seconds. Everything else is
 * plain Material 3 roles from the Veyro theme, so it follows dynamic colour and keeps contrast.
 *
 * Mounted once in `MainActivity` so it appears over any screen. While the app is not visible the
 * same decision is offered as a notification (`OfferNotifier`); both call
 * [TransferCoordinator.respondToOffer], and the first answer wins.
 */
@Composable
public fun InboundTransferDialog(
    transferCoordinator: TransferCoordinator,
    /** The folder chosen in Settings, if any; otherwise files go to the shared media folders. */
    customDestination: Flow<File?> = flowOf(null),
) {
    val pending by transferCoordinator.pendingOffer.collectAsStateWithLifecycle()
    val offer = pending ?: return
    val custom by customDestination.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    IncomingFilesSheet(
        offer = offer,
        destination = destinationLabel(offer.files.map { it.name }, custom),
        onAccept = { always ->
            scope.launch { transferCoordinator.respondToOffer(offer.transferId, accept = true, alwaysAccept = always) }
        },
        onDecline = {
            scope.launch { transferCoordinator.respondToOffer(offer.transferId, accept = false) }
        },
    )
}

/** The sheet itself, stateless apart from the checkbox and the clock, so it can be previewed. */
@Composable
public fun IncomingFilesSheet(
    offer: IncomingOffer,
    destination: String,
    onAccept: (alwaysAccept: Boolean) -> Unit,
    onDecline: () -> Unit,
    now: () -> Long = System::currentTimeMillis,
) {
    // Ticked by default (ADR-038): the first file from a paired PC asks once, then later ones are
    // accepted without a prompt. The per-device switch in device settings turns it back off.
    var alwaysAccept by remember(offer.transferId) { mutableStateOf(defaultAlwaysAccept(offer)) }
    var answered by remember(offer.transferId) { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(now()) }
    LaunchedEffect(offer.transferId) {
        while (true) {
            nowMs = now()
            delay(TICK_MS)
        }
    }
    val total = TransferCoordinator.OFFER_TIMEOUT_MS
    val hasDeadline = offer.expiresAtEpochMillis > 0L
    val remainingMs = if (hasDeadline) (offer.expiresAtEpochMillis - nowMs).coerceIn(0L, total) else total
    val secondsLeft = (remainingMs + 999) / 1000
    val urgent = hasDeadline && secondsLeft <= URGENT_SECONDS

    val visible = remember(offer.transferId) { MutableTransitionState(false).apply { targetState = true } }

    Dialog(
        // Silence is not consent: only Accept, Decline or the deadline answers.
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(
                visibleState = visible,
                // The one entrance: the sheet rises from where the thumb is.
                enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(180)),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp)
                        .semantics { paneTitle = "Incoming files from ${offer.peerName}" },
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 2.dp,
                ) {
                    Column(modifier = Modifier.navigationBarsPadding()) {
                        if (hasDeadline) Fuse(fraction = remainingMs / total.toFloat(), urgent = urgent)
                        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp)) {
                            Sender(offer)
                            Spacer(Modifier.height(20.dp))
                            Text(
                                text = headline(offer),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.semantics { heading() },
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${formatBytes(offer.totalBytes)}, saved to $destination",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (offer.files.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                FileTray(offer)
                            }
                            Spacer(Modifier.height(8.dp))
                            AlwaysAcceptRow(
                                peerName = offer.peerName,
                                checked = alwaysAccept,
                                enabled = offer.peerDeviceId != null && !answered,
                                onChange = { alwaysAccept = it },
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        answered = true
                                        onDecline()
                                    },
                                    enabled = !answered,
                                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                                ) { Text("Decline", style = MaterialTheme.typography.labelLarge) }
                                Button(
                                    onClick = {
                                        answered = true
                                        onAccept(alwaysAccept)
                                    },
                                    enabled = !answered,
                                    modifier = Modifier.weight(1.6f).heightIn(min = 56.dp),
                                ) { Text("Accept", style = MaterialTheme.typography.labelLarge) }
                            }
                            if (hasDeadline) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "Declines automatically in ${formatCountdown(secondsLeft)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clearAndSetSemantics {
                                            // Announced at a few moments, not every second.
                                            contentDescription = countdownAnnouncement(secondsLeft)
                                            liveRegion = LiveRegionMode.Polite
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The countdown as a line along the sheet's top edge, burning from the right. Decorative. */
@Composable
private fun Fuse(fraction: Float, urgent: Boolean) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val burn = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clearAndSetSemantics {},
    ) {
        drawRect(color = track)
        drawRect(color = burn, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
    }
}

@Composable
private fun Sender(offer: IncomingOffer) {
    val fingerprint = offer.peerDeviceId?.fingerprint()?.split(' ')?.take(4)?.joinToString(" ")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = VeyroIcons.Desktop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("From ").append(offer.peerName)
                    if (fingerprint != null) append(", fingerprint starts ").append(fingerprint)
                }
            },
        ) {
            Text(
                text = offer.peerName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fingerprint != null) {
                Text(
                    text = "Fingerprint $fingerprint",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FileTray(offer: IncomingOffer) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        offer.files.forEach { file -> FileRow(file) }
        val more = offer.fileCount - offer.files.size
        if (more > 0) {
            Text(
                text = "and $more more ${if (more == 1) "file" else "files"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 38.dp, top = 2.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun FileRow(file: IncomingOffer.ListedFile) {
    val kind = fileKindOf(file.name)
    val size = formatBytes(file.sizeBytes)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clearAndSetSemantics { contentDescription = "${kindLabel(kind)} ${file.name}, $size" },
    ) {
        Icon(
            imageVector = kindIcon(kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            // Keeps the extension visible on long camera names.
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = size,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlwaysAcceptRow(peerName: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Always accept from $peerName",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun headline(offer: IncomingOffer): String = when (offer.fileCount) {
    0 -> "${offer.peerName} is sending an empty folder"
    1 -> "${offer.peerName} is sending a file"
    else -> "${offer.peerName} is sending ${offer.fileCount} files"
}

@Composable
private fun kindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.IMAGE -> VeyroIcons.Image
    FileKind.VIDEO -> VeyroIcons.Movie
    FileKind.AUDIO -> VeyroIcons.MusicNote
    FileKind.DOCUMENT -> VeyroIcons.Document
    FileKind.ARCHIVE -> VeyroIcons.Folder
    FileKind.OTHER -> VeyroIcons.Download
}

private fun kindLabel(kind: FileKind): String = when (kind) {
    FileKind.IMAGE -> "Image"
    FileKind.VIDEO -> "Video"
    FileKind.AUDIO -> "Audio"
    FileKind.DOCUMENT -> "Document"
    FileKind.ARCHIVE -> "Archive"
    FileKind.OTHER -> "File"
}

/**
 * Whether "Always accept" starts ticked: yes for a paired sender (ADR-038), never when the session
 * names no device, because auto-accept can only be stored for a known, paired device (REQ-T03).
 */
public fun defaultAlwaysAccept(offer: IncomingOffer): Boolean = offer.peerDeviceId != null

/** What TalkBack hears, bucketed so the live region speaks at a few moments only. */
internal fun countdownAnnouncement(secondsLeft: Long): String = when {
    secondsLeft > 30 -> "Declines automatically in about a minute"
    secondsLeft > 10 -> "Declines automatically in 30 seconds"
    secondsLeft > 0 -> "Declines automatically in 10 seconds"
    else -> "Declining"
}

private const val TICK_MS = 250L
private const val URGENT_SECONDS = 10L
