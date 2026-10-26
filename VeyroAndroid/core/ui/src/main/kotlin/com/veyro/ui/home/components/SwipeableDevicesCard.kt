// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/home/components/SwipeableDevicesCard.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.components.FlowingPageIndicator
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.ui.components.DeviceCard
import com.veyro.ui.home.PairedDeviceUi

/**
 * One card per paired PC, swiped horizontally; the page shown is the device the quick actions
 * below apply to. With nothing paired it becomes the "Pair a device" card.
 *
 * Veyro changes: callbacks instead of a NavController, and the empty card explains what pairing
 * needs before offering the button.
 */
@Composable
internal fun SwipeableDevicesCard(
    devices: List<PairedDeviceUi>,
    onDeviceSelected: (PairedDeviceUi) -> Unit,
    onSyncAction: (PairedDeviceUi) -> Unit,
    onDeviceClick: (PairedDeviceUi) -> Unit,
    onAddDevice: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = 0) { devices.size }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (devices.isEmpty()) {
            EmptyDeviceCard(onAddDevice)
            return@Box
        }

        // Notify when page changes
        LaunchedEffect(pagerState.currentPage, devices) {
            devices.getOrNull(pagerState.currentPage)?.let(onDeviceSelected)
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            HorizontalPager(
                state = pagerState,
                pageSpacing = 16.dp,
            ) { page ->
                val device = devices[page]
                key(device.deviceId.value) {
                    DeviceCard(
                        device = device,
                        onSyncAction = { onSyncAction(device) },
                        onClick = { onDeviceClick(device) },
                    )
                }
            }

            if (devices.size > 1) {
                FlowingPageIndicator(
                    pageCount = devices.size,
                    currentPage = pagerState.currentPage,
                    currentPageOffset = pagerState.currentPageOffsetFraction,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyDeviceCard(onAddDevice: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = VeyroIcons.Desktop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "No PC paired yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Open Veyro on your PC, then pair it with this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onAddDevice) {
                Text("Pair a device")
            }
        }
    }
}
