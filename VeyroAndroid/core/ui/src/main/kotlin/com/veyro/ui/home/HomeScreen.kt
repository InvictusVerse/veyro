// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/home/HomeScreen.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.PullRefresh
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.link.LinkMessage
import com.veyro.core.model.DeviceId
import com.veyro.core.network.DiagnosisAdvice
import com.veyro.ui.home.components.DeviceControlCard
import com.veyro.ui.home.components.MediaCard
import com.veyro.ui.home.components.PcVolumeCard
import com.veyro.ui.home.components.QuickAction
import com.veyro.ui.home.components.SwipeableDevicesCard
import kotlinx.coroutines.delay

/**
 * The hub, laid out like Sefirah's Home: the paired PCs as swipeable cards, quick actions for the
 * one shown, then the PC's media and volume.
 *
 * Veyro changes: the quick actions are send / receive / device settings / pair; there is no audio
 * device sheet (the PC reports one master volume); a network notice appears only when discovery
 * cannot work (no usable network, VPN, or nothing found with a named cause); pull to refresh
 * restarts the discovery window. The discovery window is open only while this screen is started
 * (NFR-02).
 */
@Composable
public fun HomeScreen(
    viewModel: HomeViewModel,
    onDeviceClick: (DeviceId) -> Unit,
    onAddDevice: () -> Unit,
    onSendFiles: (DeviceId) -> Unit,
    onReceive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) {
        viewModel.acquireDiscovery()
        onStopOrDispose { viewModel.releaseDiscovery() }
    }

    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val pairedDevices = state.pairedDevices
    val selected = pairedDevices.firstOrNull { it.deviceId.value == selectedId } ?: pairedDevices.firstOrNull()

    var refreshing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(1_500)
            refreshing = false
        }
    }

    PullRefresh(
        refreshing = refreshing,
        enabled = true,
        onRefresh = {
            refreshing = true
            viewModel.refresh()
        },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (networkNoticeNeeded(state)) {
                item(key = "network") { NetworkNotice(state) }
            }

            item(key = "devices") {
                SwipeableDevicesCard(
                    devices = pairedDevices,
                    onDeviceSelected = { selectedId = it.deviceId.value },
                    onSyncAction = { viewModel.toggleConnection(it.deviceId) },
                    onDeviceClick = { onDeviceClick(it.deviceId) },
                    onAddDevice = onAddDevice,
                )
            }

            if (selected != null) {
                item(key = "device_control") {
                    DeviceControlCard(
                        actions = listOf(
                            QuickAction("Send files", VeyroIcons.Upload) { onSendFiles(selected.deviceId) },
                            QuickAction("Receive", VeyroIcons.Download, onReceive),
                            QuickAction("Device settings", VeyroIcons.SettingsFill) { onDeviceClick(selected.deviceId) },
                            QuickAction("Pair a device", VeyroIcons.Add, onAddDevice),
                        ),
                    )
                }
            }

            val sessions = state.pcMedia?.sessions.orEmpty()
            if (sessions.isNotEmpty()) {
                item(key = "media_sessions") {
                    MediaCard(sessions = sessions, onCommand = viewModel::mediaCommand)
                }
            }

            val volume = state.pcMedia?.volume
            if (volume != null) {
                item(key = "pc_volume") {
                    val muted = state.pcMedia?.muted == true
                    PcVolumeCard(
                        pcName = state.connectedDevice?.name ?: "PC",
                        volume = volume,
                        muted = muted,
                        onVolumeChange = { viewModel.mediaCommand(LinkMessage.MediaCommand.VOLUME, null, it.toLong()) },
                        onToggleMute = {
                            viewModel.mediaCommand(LinkMessage.MediaCommand.MUTE, null, if (muted) 0L else 1L)
                        },
                    )
                }
            }
        }
    }
}

private fun networkNoticeNeeded(state: HomeUiState): Boolean =
    !state.hasUsableNetwork ||
        state.isVpnActive ||
        (state.shouldExplainEmptiness && state.diagnosis.isNetworkProblem)

/** Veyro addition: explains why discovery cannot find the PC, using the network diagnosis. */
@Composable
private fun NetworkNotice(state: HomeUiState) {
    val advice = DiagnosisAdvice.forDiagnosis(state.diagnosis)
    val title = when {
        !state.hasUsableNetwork -> "No usable network"
        state.isVpnActive -> "VPN is on"
        else -> "No devices found"
    }
    val summary = when {
        state.isVpnActive && state.hasUsableNetwork -> "A VPN can stop this phone from seeing your PC. Turn it off if the PC does not appear."
        else -> advice.summary
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(VeyroIcons.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            advice.steps.forEach { step ->
                Text("- $step", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
