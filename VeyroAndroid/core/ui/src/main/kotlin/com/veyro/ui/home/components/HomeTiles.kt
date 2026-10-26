package com.veyro.ui.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.ui.format.FileKind
import com.veyro.ui.home.RecentTransferUi

/** Corner radius shared by Home's outlined surfaces, matching the laptop's lid. */
internal val HomeShape = RoundedCornerShape(20.dp)

@Composable
private fun homeBorder(enabled: Boolean = true): BorderStroke = BorderStroke(
    width = 1.dp,
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 1f else 0.5f),
)

/** One of the two big outlined tiles: an icon over a label, the whole tile is the button. */
@Composable
internal fun ActionTile(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    disabledReason: String? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = HomeShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = homeBorder(enabled),
        modifier = modifier
            .heightIn(min = 112.dp)
            .semantics {
                role = Role.Button
                if (!enabled && disabledReason != null) stateDescription = disabledReason
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What the Recent card's overflow menu can do with the file. */
internal class RecentActions(
    val onOpen: () -> Unit,
    val onShare: () -> Unit,
    val onShowInFolder: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * "Recent" with "See more", and the newest transfer: a thumbnail for an image (a type icon
 * otherwise), the name with a middle ellipsis, "Received from" / "Sent to", and the overflow menu.
 */
@Composable
internal fun RecentCard(
    recent: RecentTransferUi?,
    onSeeMore: () -> Unit,
    actions: RecentActions,
    loadThumbnail: suspend (String) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = HomeShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = homeBorder(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Recent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSeeMore) { Text("See more") }
            }
            if (recent == null) {
                Text(
                    text = "Files you send or receive show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp, top = 2.dp, bottom = 4.dp),
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Thumbnail(recent = recent, loadThumbnail = loadThumbnail)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recent.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    Text(
                        text = recent.subtitle + if (recent.moreCount > 0) ", and ${recent.moreCount} more" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (recent.succeeded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RecentMenu(recent = recent, actions = actions)
            }
        }
    }
}

@Composable
private fun Thumbnail(recent: RecentTransferUi, loadThumbnail: suspend (String) -> ImageBitmap?) {
    val uri = recent.destinationUri?.takeIf { recent.kind == FileKind.IMAGE && recent.canOpen }
    val image by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = uri?.let { runCatching { loadThumbnail(it) }.getOrNull() }
    }
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                imageVector = kindIcon(recent.kind),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun RecentMenu(recent: RecentTransferUi, actions: RecentActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options for ${recent.fileName}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (recent.canOpen) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { open = false; actions.onOpen() })
                DropdownMenuItem(text = { Text("Share") }, onClick = { open = false; actions.onShare() })
                DropdownMenuItem(text = { Text("Show in folder") }, onClick = { open = false; actions.onShowInFolder() })
            }
            DropdownMenuItem(text = { Text("Delete from history") }, onClick = { open = false; actions.onDelete() })
        }
    }
}

/** An outlined row with a leading icon, a label and a chevron, like Phone Link's lower rows. */
@Composable
internal fun HomeRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Surface(
        onClick = onClick,
        shape = HomeShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = homeBorder(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun kindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.IMAGE -> VeyroIcons.Image
    FileKind.VIDEO -> VeyroIcons.Movie
    FileKind.AUDIO -> VeyroIcons.MusicNote
    FileKind.DOCUMENT -> VeyroIcons.Document
    FileKind.ARCHIVE -> VeyroIcons.Folder
    FileKind.OTHER -> VeyroIcons.Download
}
