package com.veyro.feature.clipboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Veyro Clipboard — **not implemented**.
 *
 * This screen says so plainly rather than showing a disabled toggle or an empty list. A control that
 * looks functional and does nothing is worse than an honest gap: the user tries it, nothing happens,
 * and they conclude the app is broken rather than unfinished.
 *
 * The capability bit (`Capability.CLIPBOARD`) already exists in the protocol and is deliberately
 * **not** advertised by this build, so a peer will never believe this device can accept clipboard
 * data. See `ProjectInfo/06-ROADMAP.md` phase P6.
 *
 * What it will need when it is built, and why none of it is trivial:
 *  - Android restricts clipboard reads from the background (API 29+), so a push cannot simply be
 *    initiated whenever the clipboard changes.
 *  - Clipboard content is frequently sensitive - passwords from a manager, one-time codes - so it
 *    needs per-device opt-in and a size cap, and must never auto-sync to an unpaired device
 *    (REQ-C02).
 */
@Composable
public fun ClipboardScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Veyro Clipboard", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Not built yet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Universal clipboard will run over the same secure channel as file transfer, " +
                        "so it needs no new pairing and no new permissions.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "It is not advertised to other devices yet, so no peer will try to send " +
                        "clipboard data to this one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
