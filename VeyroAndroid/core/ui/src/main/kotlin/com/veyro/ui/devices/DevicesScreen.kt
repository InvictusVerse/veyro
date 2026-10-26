// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/devices/DeviceScreen.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.EmptyScreen
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.model.DeviceId
import com.veyro.core.model.fingerprint
import com.veyro.ui.components.DeviceCard
import com.veyro.ui.home.HomeViewModel

/**
 * Paired devices as cards (the same card as Home), filtered by the top bar's search, tapping one
 * opens its settings. The "Add device" button is the host's floating action button, as in Sefirah.
 *
 * Veyro changes: paired devices come from the trust store, connection state from the live link,
 * and the search also matches the fingerprint. A trust store that failed its HMAC is reported
 * above the list (REQ-I09). Discovery runs while the screen is started so Connect has endpoints.
 */
@Composable
public fun DevicesScreen(
    homeViewModel: HomeViewModel,
    devicesViewModel: DevicesViewModel,
    searchQuery: String,
    onDeviceClick: (DeviceId) -> Unit,
    onAddDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val home by homeViewModel.state.collectAsStateWithLifecycle()
    val trust by devicesViewModel.state.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) {
        homeViewModel.acquireDiscovery()
        onStopOrDispose { homeViewModel.releaseDiscovery() }
    }

    if (trust.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val devices = home.pairedDevices
    val filteredDevices = remember(devices, searchQuery) {
        if (searchQuery.isBlank()) {
            devices
        } else {
            val q = searchQuery.trim()
            devices.filter { device ->
                device.name.contains(q, ignoreCase = true) ||
                    device.deviceId.fingerprint().contains(q, ignoreCase = true)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        trust.loadError?.let { message ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Paired devices could not be verified", style = MaterialTheme.typography.titleMedium)
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (filteredDevices.isEmpty()) {
            EmptyScreen(
                message = if (devices.isEmpty()) {
                    "No paired devices. Pair a device once and it is recognised automatically from then on."
                } else {
                    "No paired device matches \"$searchQuery\"."
                },
                icon = VeyroIcons.Desktop,
                action = if (devices.isEmpty()) {
                    { Button(onClick = onAddDevice) { Text("Pair a device") } }
                } else {
                    null
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Bottom room so the floating action button never covers the last card.
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 88.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(
                    items = filteredDevices,
                    key = { it.deviceId.value },
                ) { device ->
                    DeviceCard(
                        device = device,
                        onSyncAction = { homeViewModel.toggleConnection(device.deviceId) },
                        onClick = { onDeviceClick(device.deviceId) },
                    )
                }
            }
        }
    }
}
