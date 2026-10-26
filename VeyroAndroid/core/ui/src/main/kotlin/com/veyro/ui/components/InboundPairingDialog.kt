package com.veyro.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.link.ConnectionManager
import com.veyro.core.model.DeviceId
import com.veyro.core.model.fingerprint
import com.veyro.ui.pairing.DevicePairHeader
import com.veyro.ui.pairing.ExpiryBar
import com.veyro.ui.pairing.PairingCodeTiles
import com.veyro.ui.pairing.PairingPromptDialog
import com.veyro.ui.pairing.PromptActions
import com.veyro.ui.pairing.PromptBody
import com.veyro.ui.pairing.PromptTitle

/** The PC waits this long for Accept (04-PROTOCOL.md 4.3); drives the countdown bar. */
private const val CONFIRM_WINDOW_MS = 120_000L

/**
 * Shown on this phone when a PC pairs from its nearby list (confirm code, 04-PROTOCOL.md 4.3,
 * ADR-031): the PC's name, the 6-digit code both screens show, the fingerprint, Accept / Decline.
 * Nothing is typed. It declines itself when the window runs out and disappears if the PC cancels.
 * Same look as the phone-started prompt ([PairingPromptDialog]). Mounted globally in `MainActivity`.
 */
@Composable
public fun InboundPairingDialog(
    connectionManager: ConnectionManager,
) {
    val pending by connectionManager.pendingInbound.collectAsStateWithLifecycle()
    val current = pending ?: return
    val code = current.confirmCode ?: return
    val fingerprint = remember(current.peerDeviceId) { DeviceId.parseOrNull(current.peerDeviceId)?.fingerprint() }

    PairingPromptDialog(onDismissRequest = { connectionManager.respondToInbound(false) }) {
        DevicePairHeader()
        PromptTitle("${current.peerDeviceName} wants to pair")
        PromptBody("Accept only if ${current.peerDeviceName} shows the same code.")
        PairingCodeTiles(code, modifier = Modifier.padding(top = 24.dp))
        if (fingerprint != null) {
            Text(
                text = "Device fingerprint $fingerprint",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        ExpiryBar(current.expiresAt, CONFIRM_WINDOW_MS, modifier = Modifier.padding(top = 20.dp))
        PromptActions(
            primary = "Accept" to { connectionManager.respondToInbound(true) },
            secondary = "Decline" to { connectionManager.respondToInbound(false) },
            destructiveSecondary = true,
        )
    }
}
