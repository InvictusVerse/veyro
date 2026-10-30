package com.veyro.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons
import kotlinx.coroutines.launch

private val RowShape = RoundedCornerShape(22.dp)
private val PlateShape = RoundedCornerShape(16.dp)

/**
 * "Send to": the one chooser for Home's Send files / Send folder and for "Send with Veyro" in the
 * share sheet. A bottom sheet with what is being sent, one card per connected device (the
 * Devices card's plate and colours) and "All connected (N)" below. A tap sends at once; dismissing
 * or Cancel sends nothing. [targets] is shown in the order given (see [orderSendTargets]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun SendToSheet(
    content: SendContent,
    targets: List<SendTarget>,
    onSendTo: (SendTarget) -> Unit,
    onSendToAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // One answer only: a second tap while the sheet slides away must not send twice.
    var answered by remember { mutableStateOf(false) }
    val finish: (() -> Unit) -> Unit = { action ->
        if (!answered) {
            answered = true
            scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!answered) { answered = true; onDismiss() } },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Send to",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Icon(
                    imageVector = contentIcon(content),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = sendSummary(content),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            targets.forEach { target ->
                TargetRow(target = target, onTap = { finish { onSendTo(target) } })
            }
            if (targets.size > 1) {
                AllConnectedRow(count = targets.size, onTap = { finish(onSendToAll) })
            }

            TextButton(
                onClick = { finish(onDismiss) },
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp),
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun TargetRow(target: SendTarget, onTap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val description = sendTargetDescription(target)
    Surface(
        onClick = onTap,
        shape = RowShape,
        color = colors.surfaceContainer,
        border = BorderStroke(1.5.dp, colors.primary.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = "Send to ${target.name}") { onTap(); true }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 72.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Plate(icon = platformIcon(target.platform))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = target.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary,
                    )
                    val battery = target.battery
                    if (battery != null) {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            imageVector = if (target.charging == true) VeyroIcons.BatteryCharging else VeyroIcons.Battery,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("$battery%", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(VeyroIcons.Upload, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun AllConnectedRow(count: Int, onTap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = allConnectedLabel(count)
    Surface(
        onClick = onTap,
        shape = RowShape,
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clearAndSetSemantics {
                contentDescription = "$label devices"
                role = Role.Button
                onClick(label = "Send to all $count connected devices") { onTap(); true }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 60.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).clip(PlateShape).background(colors.secondary.copy(alpha = 0.16f)),
            ) {
                Icon(VeyroIcons.Share, contentDescription = null, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The device glyph on its tile, as on the Devices card in the connected colours. */
@Composable
private fun Plate(icon: ImageVector) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(52.dp)
            .clip(PlateShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun contentIcon(content: SendContent): ImageVector = when (content) {
    is SendContent.Files -> when {
        content.mimeTypes.isNotEmpty() && content.mimeTypes.all { it?.startsWith("image/") == true } -> VeyroIcons.Image
        content.mimeTypes.isNotEmpty() && content.mimeTypes.all { it?.startsWith("video/") == true } -> VeyroIcons.Movie
        else -> VeyroIcons.Document
    }
    is SendContent.Text -> if (isLink(content.text)) VeyroIcons.Link else VeyroIcons.Clipboard
    is SendContent.Folder -> VeyroIcons.Folder
    SendContent.FilesToPick -> VeyroIcons.Upload
}
