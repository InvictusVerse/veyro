package com.veyro.ui.home

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.PullRefresh
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.link.LinkMessage
import com.veyro.core.model.DeviceId
import com.veyro.core.network.DiagnosisAdvice
import com.veyro.ui.home.components.ActionTile
import com.veyro.ui.home.components.HomeRow
import com.veyro.ui.home.components.MediaCard
import com.veyro.ui.home.components.PcHero
import com.veyro.ui.home.components.PcVolumeCard
import com.veyro.ui.home.components.RecentActions
import com.veyro.ui.home.components.RecentCard
import kotlinx.coroutines.delay

/**
 * Home, laid out like Microsoft's Link to Windows app (ADR-038): a round settings button, the PC
 * drawn as a laptop showing its wallpaper, its name (a switcher for several PCs), Wi-Fi and
 * battery, when it was last seen; then two outlined tiles, Lock PC and Send files; the newest
 * transfer under Recent; and outlined rows below. There is no Receive button: receiving is always
 * on. The PC's media and volume follow when the PC shares them (ADR-026).
 *
 * The discovery window is open only while this screen is started (NFR-02); pull to refresh
 * restarts it.
 */
@Composable
public fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
    onPcSettings: (DeviceId) -> Unit,
    onAddDevice: () -> Unit,
    /** Opens the system file picker and sends the result to these PCs, with no screen between. */
    onSendFiles: (List<DeviceId>) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LifecycleStartEffect(Unit) {
        viewModel.acquireDiscovery()
        onStopOrDispose { viewModel.releaseDiscovery() }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.messageShown()
    }

    // "Last connected 5 min ago" moves on while the screen is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }

    var refreshing by rememberSaveable { mutableStateOf(false) }
    var askSendTo by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(1_500)
            refreshing = false
        }
    }

    val pc = state.selectedPc
    val recentActions = remember(state.recent, context) {
        val recent = state.recent
        RecentActions(
            onOpen = { recent?.destinationUri?.let { FileActions.open(context, it) } },
            onShare = { recent?.destinationUri?.let { FileActions.share(context, it) } },
            onShowInFolder = { recent?.let { FileActions.showInFolder(context, it.fileName, it.destinationUri) } },
            onDelete = { recent?.let { viewModel.removeFromHistory(it.id) } },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        PullRefresh(
            refreshing = refreshing,
            enabled = true,
            onRefresh = {
                refreshing = true
                viewModel.refresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "top") {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = onOpenSettings, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.Person, contentDescription = "Settings")
                        }
                    }
                }

                if (networkNoticeNeeded(state)) {
                    item(key = "network") { NetworkNotice(state) }
                }

                item(key = "hero") {
                    PcHero(
                        pc = pc,
                        wallpaper = state.selectedWallpaper,
                        allPcs = state.pairedDevices,
                        now = now,
                        onSelect = { viewModel.selectPc(it.deviceId) },
                        onConnect = { pc?.let { viewModel.toggleConnection(it.deviceId) } },
                        onPcSettings = { pc?.let { onPcSettings(it.deviceId) } },
                        onAddPc = onAddDevice,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }

                if (pc != null) {
                    item(key = "tiles") {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            ActionTile(
                                label = "Lock PC",
                                icon = VeyroIcons.Lock,
                                enabled = state.canLockPc,
                                onClick = viewModel::lockSelectedPc,
                                disabledReason = if (pc.isConnected) "Not supported by this PC" else "PC not connected",
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            ActionTile(
                                label = "Send files",
                                icon = VeyroIcons.Upload,
                                enabled = state.canSendFiles,
                                onClick = {
                                    // Several PCs connected: ask which. Otherwise straight to the one.
                                    if (state.sendNeedsChoice) {
                                        askSendTo = true
                                    } else {
                                        viewModel.defaultSendTarget()?.let { onSendFiles(listOf(it)) }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                item(key = "recent") {
                    RecentCard(
                        recent = state.recent,
                        onSeeMore = onOpenHistory,
                        actions = recentActions,
                        loadThumbnail = viewModel::thumbnail,
                    )
                }

                item(key = "received") {
                    HomeRow(
                        label = "Received files",
                        icon = VeyroIcons.Folder,
                        supporting = "Files from your PC are saved to Download, Pictures, Movies or Music, in a Veyro folder.",
                        onClick = { if (!FileActions.openReceivedFolder(context)) onOpenHistory() },
                    )
                }
                // Clipboard is deferred (06-ROADMAP); its row joins here once it ships.

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
                            pcName = state.selectedPc?.name ?: "PC",
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
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    if (askSendTo) {
        SendToDialog(
            connected = state.connectedDevices,
            preselected = state.selectedPc?.takeIf { it.isConnected }?.deviceId,
            onDismiss = { askSendTo = false },
            onConfirm = { targets ->
                askSendTo = false
                if (targets.isNotEmpty()) onSendFiles(targets)
            },
        )
    }
}

/**
 * "Send to", shown before the file picker when more than one PC is connected (multiple PCs).
 * The selected PC is preselected; "All connected" sends the same files to every connected PC.
 */
@Composable
private fun SendToDialog(
    connected: List<PairedDeviceUi>,
    preselected: DeviceId?,
    onDismiss: () -> Unit,
    onConfirm: (List<DeviceId>) -> Unit,
) {
    // Null means "All connected".
    var choice by rememberSaveable { mutableStateOf(preselected?.value ?: connected.firstOrNull()?.deviceId?.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to") },
        text = {
            Column(Modifier.selectableGroup()) {
                connected.forEach { pc ->
                    SendToOption(label = pc.name, selected = choice == pc.deviceId.value) { choice = pc.deviceId.value }
                }
                SendToOption(label = "All connected (${connected.size})", selected = choice == null) { choice = null }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = choice
                onConfirm(if (id == null) connected.map { it.deviceId } else listOfNotNull(connected.firstOrNull { it.deviceId.value == id }?.deviceId))
            }) { Text("Choose files") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SendToOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 4.dp),
    ) {
        // The row carries the semantics; the button is decorative.
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun networkNoticeNeeded(state: HomeUiState): Boolean =
    !state.hasUsableNetwork ||
        state.isVpnActive ||
        (state.shouldExplainEmptiness && state.diagnosis.isNetworkProblem)

/** Explains why discovery cannot find the PC, using the network diagnosis. */
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
        shape = MaterialTheme.shapes.large,
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
