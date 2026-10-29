package com.veyro.ui.home.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.model.Platform
import com.veyro.ui.home.ConnectionStatus
import com.veyro.ui.home.PairedDeviceUi
import com.veyro.ui.home.lastSeenLabel

/**
 * The top of Home (ADR-038): the PC drawn as a laptop showing the wallpaper it sent, its name
 * (a switcher when more than one PC is paired), its Wi-Fi and battery, and when it was last seen.
 *
 * The laptop is the one expressive element on the screen; it is drawn from shapes so it follows
 * the theme in light and dark, and falls back to a gradient from the theme's containers when the
 * PC has sent no wallpaper.
 */
@Composable
internal fun PcHero(
    pc: PairedDeviceUi?,
    wallpaper: ByteArray?,
    allPcs: List<PairedDeviceUi>,
    now: Long,
    onSelect: (PairedDeviceUi) -> Unit,
    onConnect: () -> Unit,
    onPcSettings: () -> Unit,
    onAddPc: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (pc != null && pc.platform == Platform.ANDROID) {
            // ADR-052: another phone is drawn as a phone.
            PhoneIllustration(dimmed = !pc.isConnected, contentDescription = "${pc.name}, a phone")
        } else {
            LaptopIllustration(
                wallpaper = wallpaper,
                dimmed = pc == null || !pc.isConnected,
                contentDescription = if (pc == null) "No device paired" else "${pc.name}, drawn with its wallpaper",
            )
        }
        Spacer(Modifier.height(20.dp))

        if (pc == null) {
            Text(
                text = "No device paired yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Pair your Windows PC or another phone once and it reconnects on its own.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAddPc) { Text("Pair a device") }
            return@Column
        }

        PcSwitcher(pc = pc, allPcs = allPcs, onSelect = onSelect, onPcSettings = onPcSettings, onAddPc = onAddPc)
        Spacer(Modifier.height(6.dp))
        StatusIcons(pc)
        Spacer(Modifier.height(4.dp))
        Text(
            text = lastSeenLabel(pc, now),
            style = MaterialTheme.typography.bodyMedium,
            color = if (pc.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (pc.status == ConnectionStatus.NEARBY || pc.status == ConnectionStatus.OFFLINE) {
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = onConnect, enabled = pc.status == ConnectionStatus.NEARBY) {
                Text(if (pc.status == ConnectionStatus.NEARBY) "Connect" else "Not on this network")
            }
            if (pc.connectFailed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (pc.status == ConnectionStatus.NEARBY) "Could not connect. Try again." else "Not found on this network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PcSwitcher(
    pc: PairedDeviceUi,
    allPcs: List<PairedDeviceUi>,
    onSelect: (PairedDeviceUi) -> Unit,
    onPcSettings: () -> Unit,
    onAddPc: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        val connectedCount = allPcs.count { it.isConnected }
        TextButton(
            onClick = { open = true },
            modifier = Modifier
                .widthIn(max = 320.dp)
                .semantics {
                    role = Role.DropdownList
                    stateDescription = buildString {
                        append(if (pc.isConnected) "Connected" else "Not connected")
                        if (allPcs.size > 1) append(". ${allPcs.size} devices paired, $connectedCount connected")
                    }
                },
        ) {
            ConnectedDot(connected = pc.isConnected)
            Spacer(Modifier.width(8.dp))
            Text(
                text = pc.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            allPcs.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = (if (item.platform == Platform.ANDROID) "Phone · " else "PC · ") +
                                    if (item.isConnected) "connected" else "not connected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = {
                        if (item.deviceId == pc.deviceId) {
                            Icon(Icons.Default.Check, contentDescription = "Selected")
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    trailingIcon = { ConnectedDot(connected = item.isConnected) },
                    onClick = {
                        open = false
                        onSelect(item)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (pc.platform == Platform.ANDROID) "Phone settings" else "PC settings") },
                leadingIcon = { Icon(VeyroIcons.SettingsFill, contentDescription = null) },
                onClick = {
                    open = false
                    onPcSettings()
                },
            )
            DropdownMenuItem(
                text = { Text("Pair another device") },
                leadingIcon = { Icon(VeyroIcons.Add, contentDescription = null) },
                onClick = {
                    open = false
                    onAddPc()
                },
            )
        }
    }
}

/**
 * The switcher's live-state dot: filled in the primary colour while that PC is connected, an
 * outlined ring otherwise, so the state does not rely on colour alone. Decorative: the text next
 * to it, or the switcher's state description, says the same.
 */
@Composable
private fun ConnectedDot(connected: Boolean) {
    val color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Canvas(modifier = Modifier.size(10.dp)) {
        if (connected) {
            drawCircle(color = color)
        } else {
            drawCircle(color = color, radius = size.minDimension / 2f - 1.dp.toPx(), style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

/** Wi-Fi bars and battery with a charging bolt, from the PC's `devStatus`. Only while connected. */
@Composable
private fun StatusIcons(pc: PairedDeviceUi) {
    val wifi = pc.wifi?.takeIf { it >= 0 }
    val battery = pc.battery
    if (!pc.isConnected || (wifi == null && battery == null)) return
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = buildString {
                if (wifi != null) append("Wi-Fi signal $wifi of 4. ")
                if (battery != null) {
                    append("Battery $battery percent")
                    if (pc.charging == true) append(", charging")
                }
            }
        },
    ) {
        if (wifi != null) WifiBars(level = wifi, color = tint)
        if (battery != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (pc.charging == true) VeyroIcons.BatteryCharging else VeyroIcons.Battery,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "$battery%",
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = tint,
                )
            }
        }
    }
}

/** A Wi-Fi fan with [level] of 4 steps lit (the dot counts as the first). */
@Composable
private fun WifiBars(level: Int, color: Color) {
    val dim = color.copy(alpha = 0.28f)
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = size.minDimension * 0.11f
        val center = Offset(size.width / 2f, size.height * 0.86f)
        drawCircle(color = if (level >= 1) color else dim, radius = stroke * 0.9f, center = center)
        for (step in 1..3) {
            val radius = size.minDimension * (0.12f + 0.2f * step)
            drawArc(
                color = if (level >= step + 1) color else dim,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * A laptop drawn from shapes: a thin dark bezel around the screen, a base slightly wider than the
 * lid with a thumb notch. The screen shows [wallpaper], else a gradient from the theme.
 */
@Composable
private fun LaptopIllustration(wallpaper: ByteArray?, dimmed: Boolean, contentDescription: String) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val bezel = if (dark) Color(0xFF3A3F45) else Color(0xFF23272C)
    val base = if (dark) Color(0xFF4A5057) else Color(0xFFCDD2D8)
    val notch = if (dark) Color(0xFF30353A) else Color(0xFFA9B0B8)
    val image = rememberWallpaper(wallpaper)
    val fallback = Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer, scheme.secondaryContainer))

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val lidWidth = (maxWidth * 0.62f).coerceAtMost(300.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .width(lidWidth)
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                    .background(bezel)
                    .padding(start = 6.dp, end = 6.dp, top = 7.dp, bottom = 6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(fallback),
                ) {
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (dimmed) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (dark) 0.45f else 0.28f)))
                    }
                }
            }
            Box(
                modifier = Modifier
                    .width(lidWidth * 1.16f)
                    .height(11.dp)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomStart = 10.dp, bottomEnd = 10.dp))
                    .background(base),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .width(lidWidth * 0.18f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                        .background(notch),
                )
            }
        }
    }
}

/**
 * Another phone (ADR-052), drawn from shapes like the laptop: a dark frame with rounded corners,
 * a camera dot, and a screen in the theme's gradient. Phones share no wallpaper with each other.
 */
@Composable
private fun PhoneIllustration(dimmed: Boolean, contentDescription: String) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val frame = if (dark) Color(0xFF3A3F45) else Color(0xFF23272C)
    val screen = Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer, scheme.secondaryContainer))
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        // The same height as the laptop's lid, so Home does not jump when switching devices.
        val height = ((maxWidth * 0.62f).coerceAtMost(300.dp) * (10f / 16f)) + 11.dp
        Box(
            modifier = Modifier
                .height(height)
                .aspectRatio(9f / 18.5f)
                .clip(RoundedCornerShape(18.dp))
                .background(frame)
                .padding(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(screen),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(frame),
                )
                if (dimmed) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (dark) 0.45f else 0.28f)))
                }
            }
        }
    }
}

/**
 * Decodes the PC's JPEG once per change. The bytes come from the network and were checked for a
 * JPEG header and size by the bridge; decoding is still bounded (sampled down past 1440 px) so a
 * hostile header cannot ask for a huge allocation.
 */
@Composable
private fun rememberWallpaper(bytes: ByteArray?): ImageBitmap? = remember(bytes) {
    if (bytes == null) return@remember null
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1440) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()
}
