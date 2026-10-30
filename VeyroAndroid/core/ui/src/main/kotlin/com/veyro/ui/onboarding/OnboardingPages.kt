package com.veyro.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.veyro.core.network.LocalNetworkPermission
import com.veyro.core.network.LocalNetworkState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.veyro.core.designsystem.components.VeyroCard
import com.veyro.core.designsystem.components.VeyroSectionHeader
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.ui.pairing.CandidateCard
import com.veyro.ui.pairing.CodeEntryHint
import com.veyro.ui.pairing.ConfirmPairingDialog
import com.veyro.ui.pairing.PairingScreen
import com.veyro.ui.pairing.PairingViewModel
import com.veyro.ui.pairing.QrPairingState
import com.veyro.ui.pairing.QrStatusCard
import com.veyro.ui.pairing.ScanQrCard
import com.veyro.ui.pairing.SearchingCard
import com.veyro.ui.pairing.rememberQrScanAction

/** Page (b): the name the PC will show for this phone. Prefilled, editable, saved on Next. */
@Composable
internal fun DeviceNamePage(
    name: String,
    onNameChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    OnboardingPage(
        icon = Icons.Default.Edit,
        title = "Name this phone",
        subtitle = "Your PC shows this name when pairing and in its device list.",
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Device name") },
            singleLine = true,
            isError = name.isBlank(),
            supportingText = {
                Text(if (name.isBlank()) "Enter a name to continue" else "You can change it later in Settings.")
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onDone() }),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Page (d): pair with the PC. Reuses the regular PairingViewModel (keyed separately from the
 * Pairing screen's), so scan and the nearby list (confirm code) behave exactly as they do there.
 * The onboarding ViewModel moves to Done when the trust store gains the new peer.
 */
@Composable
internal fun PairPage(
    viewModelFactory: ViewModelProvider.Factory,
    localNetwork: LocalNetworkState = LocalNetworkState.NOT_NEEDED,
    onFixLocalNetwork: () -> Unit = {},
) {
    val pairing = viewModel<PairingViewModel>(factory = viewModelFactory, key = "onboarding-pairing")
    // ADR-061: on Android 17 nothing on the Wi-Fi is reachable until the user allows it, and
    // onboarding runs before the launch-time permission request. Ask once, when this page is reached.
    var askedHere by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(localNetwork) {
        if (!askedHere && LocalNetworkPermission.requestOnPairPage(localNetwork)) {
            askedHere = true
            onFixLocalNetwork()
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (localNetwork.isBlocked) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(LocalNetworkPermission.TITLE, style = MaterialTheme.typography.titleMedium)
                    Text(LocalNetworkPermission.explanation(localNetwork), style = MaterialTheme.typography.bodyMedium)
                    LocalNetworkPermission.actionLabel(localNetwork)?.let { label ->
                        FilledTonalButton(onClick = onFixLocalNetwork) { Text(label) }
                    }
                }
            }
        }
        // Exactly the Pairing screen (hardware test: the old onboarding page looked different and
        // worse). It brings its own scan button, nearby list, code prompt and help.
        PairingScreen(viewModel = pairing, modifier = Modifier.weight(1f))
    }
}

/** Page (e): what was paired, with the fingerprint to compare against the PC. */
@Composable
internal fun DonePage(paired: PairedSummary?) {
    OnboardingPage(
        icon = Icons.Default.CheckCircle,
        title = if (paired != null) "You are all set" else "Setup finished",
    ) {
        if (paired != null) {
            VeyroCard(modifier = Modifier.fillMaxWidth(), glow = true) {
                Text("Paired with", style = MaterialTheme.typography.labelMedium, color = VeyroTextSecondary)
                Text(paired.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Fingerprint", style = MaterialTheme.typography.labelMedium, color = VeyroTextSecondary)
                Text(
                    text = paired.fingerprint,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This identifies your PC. Compare it with the fingerprint Veyro shows on the PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VeyroTextSecondary,
                )
            }
        } else {
            Text(
                text = "No PC is paired yet. When you are ready, go to Devices and tap Pair new device.",
                style = MaterialTheme.typography.bodyMedium,
                color = VeyroTextSecondary,
            )
        }
    }
}
