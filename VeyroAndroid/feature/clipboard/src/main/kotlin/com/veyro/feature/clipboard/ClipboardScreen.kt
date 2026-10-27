package com.veyro.feature.clipboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.icons.VeyroIcons

/**
 * The phone's virtual clipboard (ADR-043).
 *
 * Top: one card to send this phone's clipboard, with the "Auto-fetch PC clipboard" switch under a
 * divider. Then filter chips and every clip from the PCs in **one** rounded container, rows split
 * by dividers. Each row has an icon for what it is, a quick action that fits it (Open, Call,
 * Email, Copy code) and Copy. Tap a row to copy it; long-press for Remove.
 *
 * Android lets no app read the clipboard in the background, so the card's button is the only way
 * the phone's own clipboard reaches the PC; text and links go with "Send to PC" from any app.
 */
@Composable
public fun ClipboardScreen(viewModel: ClipboardViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.messageShown()
    }

    val rows = state.rows
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        ) {
            item(key = "hero") {
                HeroCard(
                    sending = state.sending,
                    autoFetch = state.autoFetch,
                    onSend = viewModel::sendClipboardToPc,
                    onAutoFetch = viewModel::setAutoFetchEnabled,
                )
            }
            item(key = "filters") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 20.dp, bottom = 8.dp),
                ) {
                    ClipFilter.entries.forEach { filter ->
                        val count = state.count(filter)
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text(if (count > 0) "${filter.label}  $count" else filter.label) },
                        )
                    }
                }
            }
            item(key = "header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
                ) {
                    Text(
                        "From your PC",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.all.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearAll) { Text("Clear all") }
                    }
                }
            }
            if (rows.isEmpty()) {
                item(key = "empty") { EmptyState(autoFetch = state.autoFetch, filtered = state.all.isNotEmpty()) }
            }
            itemsIndexed(rows, key = { _, row -> "${row.source}|${row.id}" }) { index, row ->
                // Rounded only at the ends, same fill throughout: the rows read as one container.
                val shape = RoundedCornerShape(
                    topStart = if (index == 0) 24.dp else 4.dp,
                    topEnd = if (index == 0) 24.dp else 4.dp,
                    bottomStart = if (index == rows.lastIndex) 24.dp else 4.dp,
                    bottomEnd = if (index == rows.lastIndex) 24.dp else 4.dp,
                )
                Surface(
                    shape = shape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().animateItem(),
                ) {
                    Column {
                        ClipRowItem(
                            row = row,
                            onCopy = { viewModel.copy(row) },
                            onCopyCode = { viewModel.copyValue(row) },
                            onAction = { quickAction(context, row) },
                            onRemove = { viewModel.remove(row) },
                        )
                        if (index != rows.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
            item(key = "hint") {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 20.dp),
                ) {
                    Icon(
                        VeyroIcons.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "To send text or a link to your PC, select it or tap Share in any app and choose " +
                            "\"Send to PC\". It lands on the PC's clipboard.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

@Composable
private fun HeroCard(sending: Boolean, autoFetch: Boolean, onSend: () -> Unit, onAutoFetch: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.tertiaryContainer)))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(colors.surface.copy(alpha = 0.55f)),
                    ) {
                        Icon(VeyroIcons.Clipboard, contentDescription = null, tint = colors.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Send this phone's clipboard", style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
                        Text(
                            "Goes to your PC's Veyro list. It does not replace what the PC has copied.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
                Button(onClick = onSend, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
                    if (sending) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Send clipboard to PC")
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .toggleable(value = autoFetch, onValueChange = onAutoFetch, role = Role.Switch)
                    .semantics { stateDescription = if (autoFetch) "On" else "Off" }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-fetch PC clipboard", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Keep what your PC copies here, with a notification to copy or open it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                // The row carries the semantics; the switch is decorative.
                Switch(checked = autoFetch, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun EmptyState(autoFetch: Boolean, filtered: Boolean) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(vertical = 32.dp, horizontal = 24.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Icon(VeyroIcons.Clipboard, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(
                when {
                    filtered -> "Nothing of this kind yet"
                    autoFetch -> "Copy something on your PC"
                    else -> "Auto-fetch is off"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                when {
                    filtered -> "Pick All to see every clip."
                    autoFetch -> "It shows up here, ready to paste."
                    else -> "Turn it on to keep what your PC copies here."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipRowItem(
    row: ClipRow,
    onCopy: () -> Unit,
    onCopyCode: () -> Unit,
    onAction: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val look = look(row.kind)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = "Copy",
                    onLongClickLabel = "More options",
                    onClick = if (row.kind == ClipKind.CODE) onCopyCode else onCopy,
                    onLongClick = { menu = true },
                )
                .semantics { customActions = listOf(CustomAccessibilityAction("Remove") { onRemove(); true }) }
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)
                .animateContentSize(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(look.container),
            ) {
                Icon(look.icon, contentDescription = look.label, tint = look.content, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val thumbnail = row.thumbnail
                when {
                    thumbnail != null -> Image(
                        bitmap = thumbnail,
                        contentDescription = "Image from ${row.source}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    row.kind == ClipKind.CODE && row.value != null -> Text(
                        row.value,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 4.sp,
                    )
                    row.kind == ClipKind.LINK && row.value != null -> {
                        val host = Uri.parse(row.value).host?.removePrefix("www.")
                        if (host != null) {
                            Text(host, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            row.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    else -> Text(
                        row.text.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${look.label}  ·  ${row.source}  ·  ${DateUtils.getRelativeTimeSpanString(row.atMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (row.kind) {
                ClipKind.LINK -> IconButton(onClick = onAction) { Icon(VeyroIcons.OpenInNew, contentDescription = "Open link") }
                ClipKind.PHONE -> IconButton(onClick = onAction) { Icon(VeyroIcons.Call, contentDescription = "Call") }
                ClipKind.EMAIL -> IconButton(onClick = onAction) { Icon(VeyroIcons.Mail, contentDescription = "Write email") }
                else -> Unit
            }
            IconButton(onClick = if (row.kind == ClipKind.CODE) onCopyCode else onCopy) {
                Icon(VeyroIcons.ContentCopy, contentDescription = if (row.kind == ClipKind.CODE) "Copy code" else "Copy")
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy() })
            if (row.kind == ClipKind.CODE) {
                DropdownMenuItem(text = { Text("Copy code only") }, onClick = { menu = false; onCopyCode() })
            }
            DropdownMenuItem(
                text = { Text("Remove") },
                leadingIcon = { Icon(VeyroIcons.Delete, contentDescription = null) },
                onClick = { menu = false; onRemove() },
            )
        }
    }
}

private data class KindLook(val label: String, val icon: ImageVector, val container: Color, val content: Color)

@Composable
private fun look(kind: ClipKind): KindLook {
    val c = MaterialTheme.colorScheme
    return when (kind) {
        ClipKind.LINK -> KindLook("Link", VeyroIcons.Link, c.primaryContainer, c.onPrimaryContainer)
        ClipKind.PHONE -> KindLook("Phone", VeyroIcons.Call, c.tertiaryContainer, c.onTertiaryContainer)
        ClipKind.EMAIL -> KindLook("Email", VeyroIcons.Mail, c.tertiaryContainer, c.onTertiaryContainer)
        ClipKind.CODE -> KindLook("Code", VeyroIcons.Key, c.errorContainer, c.onErrorContainer)
        ClipKind.IMAGE -> KindLook("Image", VeyroIcons.Image, c.secondaryContainer, c.onSecondaryContainer)
        ClipKind.TEXT -> KindLook("Text", VeyroIcons.Document, c.surfaceContainerHighest, c.onSurfaceVariant)
    }
}

/** Open, Call or Email. Only http(s) links are ever opened. */
private fun quickAction(context: Context, row: ClipRow) {
    val value = row.value ?: return
    val intent = when (row.kind) {
        ClipKind.LINK -> Uri.parse(value).takeIf { it.scheme?.lowercase() in setOf("http", "https") }
            ?.let { Intent(Intent.ACTION_VIEW, it).addCategory(Intent.CATEGORY_BROWSABLE) }
        ClipKind.PHONE -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:$value"))
        ClipKind.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$value"))
        else -> null
    } ?: return
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No app for it (a tablet without a dialer): nothing to open.
    }
}
