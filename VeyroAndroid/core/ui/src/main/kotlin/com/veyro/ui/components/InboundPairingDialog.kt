package com.veyro.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroButton
import com.veyro.core.designsystem.components.VeyroOutlineButton
import com.veyro.core.designsystem.theme.VeyroCyanMuted
import com.veyro.core.designsystem.theme.VeyroGlassBorder
import com.veyro.core.designsystem.theme.VeyroPinStyle
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.designsystem.theme.VeyroViolet
import com.veyro.core.designsystem.theme.VeyroVioletContainer
import com.veyro.core.designsystem.theme.VeyroVioletGlow
import com.veyro.core.link.ConnectionManager
import com.veyro.core.model.DeviceId
import com.veyro.core.model.fingerprint
import kotlinx.coroutines.delay

/**
 * Top-level modal dialog shown on the RECEIVING device when another device pairs with this one
 * from its nearby list (confirm code, 04-PROTOCOL.md 4.3, ADR-031).
 *
 * Shows the other device's name, its fingerprint and the 6-digit code both screens display, with
 * Accept / Decline. Nothing is typed. The request declines itself when the window runs out, and
 * disappears if the other device cancels.
 *
 * Only confirm requests are shown. The legacy PIN flow has no UI any more and times out.
 * Mounted globally in `MainActivity` so it overlays any screen.
 */
@Composable
public fun InboundPairingDialog(
    connectionManager: ConnectionManager,
) {
    val pending by connectionManager.pendingInbound.collectAsStateWithLifecycle()
    val current = pending ?: return
    val code = current.confirmCode ?: return

    Dialog(
        onDismissRequest = { connectionManager.respondToInbound(false) },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        GlassmorphicDialogSurface {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(min = 300.dp, max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PairingDialogIcon()

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Pairing request",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "${current.peerDeviceName} wants to pair with this device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VeyroTextSecondary,
                    textAlign = TextAlign.Center,
                )

                // The id is proven by now (its key verified the exchange); the fingerprint lets
                // the user compare it with the other screen after pairing.
                val fingerprint = remember(current.peerDeviceId) {
                    DeviceId.parseOrNull(current.peerDeviceId)?.fingerprint()
                }
                if (fingerprint != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Device fingerprint\n$fingerprint",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = VeyroTextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Accept only if ${current.peerDeviceName} shows the same code:",
                    style = MaterialTheme.typography.bodySmall,
                    color = VeyroTextSecondary,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(12.dp))

                PinDisplayGlow(pin = code)

                Spacer(modifier = Modifier.height(16.dp))

                var timeLeft by remember(current.expiresAt) {
                    mutableLongStateOf(((current.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
                }
                LaunchedEffect(current.expiresAt) {
                    while (timeLeft > 0) {
                        delay(1000)
                        timeLeft = ((current.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = if (timeLeft <= 10) VeyroCyanMuted else VeyroTextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Declines automatically in $timeLeft s",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (timeLeft <= 10) VeyroCyanMuted else VeyroTextSecondary,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    VeyroOutlineButton(
                        text = "Decline",
                        onClick = { connectionManager.respondToInbound(false) },
                        modifier = Modifier.weight(1f),
                    )
                    VeyroButton(
                        text = "Accept",
                        onClick = { connectionManager.respondToInbound(true) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingDialogIcon() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(VeyroVioletContainer)
            .border(1.5.dp, VeyroViolet.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = VeyroViolet,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
private fun PinDisplayGlow(pin: String) {
    // "123 456": two groups of three are easier to compare than six in a row.
    val grouped = pin.chunked(3).joinToString(" ")

    val infiniteTransition = rememberInfiniteTransition(label = "pinGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pinGlowAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        VeyroViolet.copy(alpha = glowAlpha * 0.2f),
                        VeyroVioletContainer,
                    ),
                ),
            )
            .border(1.5.dp, VeyroViolet.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = grouped,
            style = VeyroPinStyle.copy(fontSize = 44.sp, letterSpacing = 4.sp),
            color = VeyroVioletGlow,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics {
                contentDescription = "Pairing code ${pin.toCharArray().joinToString(" ")}"
            },
        )
    }
}

@Composable
internal fun GlassmorphicDialogSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    ),
                ),
            )
            .border(1.dp, VeyroGlassBorder.copy(alpha = 0.6f), RoundedCornerShape(24.dp)),
    ) {
        content()
    }
}
