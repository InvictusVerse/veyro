// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/navigation/graphs/RootNavGraph.kt, GPL-3.0.
// Modified for Veyro.
// The device routes follow app/src/main/java/com/castle/sefirah/navigation/graphs/DevicesNavGraph.kt.
package com.veyro.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.veyro.core.designsystem.components.VeyroDetailScaffold
import com.veyro.core.link.TransferCoordinator
import com.veyro.feature.clipboard.ClipboardScreen
import com.veyro.ui.components.ActiveTransferBanner
import com.veyro.ui.components.ActiveTransferDialog
import com.veyro.ui.devices.DeviceSettingsScreen
import com.veyro.ui.devices.DevicesViewModel
import com.veyro.ui.devices.MediaSettingsScreen
import com.veyro.ui.devices.MessagesSettingsScreen
import com.veyro.ui.home.HomeViewModel
import com.veyro.ui.pairing.PairingScreen
import com.veyro.ui.pairing.PairingViewModel
import com.veyro.ui.receive.ReceiveScreen
import com.veyro.ui.receive.ReceiveViewModel
import com.veyro.ui.send.SendScreen
import com.veyro.ui.send.SendViewModel
import com.veyro.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The root navigation graph: the tabbed [MainScreen] plus every pushed screen, each with its own
 * back arrow (Sefirah's root graph).
 *
 * The one authoritative [ActiveTransferBanner] sits above the whole graph, so it shows on every
 * screen exactly once. When it is visible it takes the status-bar inset and the graph below is
 * told the inset is consumed, so no top bar pads for the status bar twice.
 *
 * Home and Devices share one [HomeViewModel], like Sefirah's ConnectionViewModel, so a Connect
 * started on one card shows on the other. It and [DevicesViewModel] are scoped to the Activity.
 *
 * `onPickFiles` is passed in from the Activity rather than handled here, because the file picker is
 * an `ActivityResultContract` and only the Activity can launch one.
 */
@Composable
public fun VeyroNavHost(
    viewModelFactory: ViewModelProvider.Factory,
    onPickFiles: () -> Unit,
    transferCoordinator: TransferCoordinator? = null,
    modifier: Modifier = Modifier,
    /** Runtime permissions need the Activity, like the picker (ADR-027). */
    onRequestMessagingPermissions: () -> Unit = {},
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    val homeViewModel = viewModel<HomeViewModel>(factory = viewModelFactory)
    val devicesViewModel = viewModel<DevicesViewModel>(factory = viewModelFactory)

    var showTransferDialog by remember { mutableStateOf(false) }
    val activeTransfer by (transferCoordinator?.activeTransfer ?: remember { MutableStateFlow(null) })
        .collectAsStateWithLifecycle()
    val bannerVisible = activeTransfer != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ActiveTransferBanner(
            activeTransfer = activeTransfer,
            onCancel = { transferCoordinator?.cancel() },
            onDismiss = { transferCoordinator?.clearActiveTransfer() },
            onClick = { showTransferDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .then(if (bannerVisible) Modifier.statusBarsPadding() else Modifier),
        )

        NavHost(
            navController = navController,
            startDestination = VeyroDestination.Main,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(if (bannerVisible) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
            enterTransition = { NavigationTransitions.rootEnterTransition(this) },
            exitTransition = { NavigationTransitions.rootExitTransition(this) },
            popEnterTransition = { NavigationTransitions.rootPopEnterTransition(this) },
            popExitTransition = { NavigationTransitions.rootPopExitTransition(this) },
        ) {
            composable<VeyroDestination.Main> {
                MainScreen(
                    rootNavController = navController,
                    viewModelFactory = viewModelFactory,
                    homeViewModel = homeViewModel,
                    devicesViewModel = devicesViewModel,
                    onRequestMessagingPermissions = onRequestMessagingPermissions,
                )
            }

            composable<VeyroDestination.Pairing> {
                VeyroDetailScaffold(title = "Add device", onNavigateBack = { navController.navigateUp() }) { padding ->
                    PairingScreen(
                        viewModel = viewModel<PairingViewModel>(factory = viewModelFactory),
                        modifier = Modifier.padding(padding),
                    )
                }
            }

            composable<VeyroDestination.Send> { entry ->
                val route = entry.toRoute<VeyroDestination.Send>()
                VeyroDetailScaffold(title = "Send files", onNavigateBack = { navController.navigateUp() }) { padding ->
                    SendScreen(
                        viewModel = viewModel<SendViewModel>(factory = viewModelFactory),
                        onPickFiles = onPickFiles,
                        initialTarget = route.deviceId,
                        modifier = Modifier.padding(padding),
                    )
                }
            }

            composable<VeyroDestination.Receive> {
                VeyroDetailScaffold(title = "Receive", onNavigateBack = { navController.navigateUp() }) { padding ->
                    ReceiveScreen(
                        viewModel = viewModel<ReceiveViewModel>(factory = viewModelFactory),
                        modifier = Modifier.padding(padding),
                    )
                }
            }

            composable<VeyroDestination.Clipboard> {
                VeyroDetailScaffold(title = "Clipboard", onNavigateBack = { navController.navigateUp() }) { padding ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(padding)) { ClipboardScreen() }
                }
            }

            composable<VeyroDestination.DeviceSettings> { entry ->
                val route = entry.toRoute<VeyroDestination.DeviceSettings>()
                DeviceSettingsScreen(
                    deviceId = route.deviceId,
                    homeViewModel = homeViewModel,
                    devicesViewModel = devicesViewModel,
                    settingsViewModel = viewModel<SettingsViewModel>(factory = viewModelFactory),
                    onNavigateBack = { navController.navigateUp() },
                    onSendFiles = { navController.navigate(VeyroDestination.Send(route.deviceId)) },
                    onOpenMediaSettings = { navController.navigate(VeyroDestination.DeviceMedia) },
                    onOpenMessagesSettings = { navController.navigate(VeyroDestination.DeviceMessages) },
                    onRequestMessagingPermissions = onRequestMessagingPermissions,
                )
            }

            composable<VeyroDestination.DeviceMedia> {
                MediaSettingsScreen(
                    settingsViewModel = viewModel<SettingsViewModel>(factory = viewModelFactory),
                    onNavigateBack = { navController.navigateUp() },
                )
            }

            composable<VeyroDestination.DeviceMessages> {
                MessagesSettingsScreen(
                    settingsViewModel = viewModel<SettingsViewModel>(factory = viewModelFactory),
                    onNavigateBack = { navController.navigateUp() },
                    onRequestMessagingPermissions = onRequestMessagingPermissions,
                )
            }
        }
    }

    if (showTransferDialog && activeTransfer != null) {
        ActiveTransferDialog(
            activeTransfer = activeTransfer,
            onCancel = { transferCoordinator?.cancel() },
            onDismiss = {
                showTransferDialog = false
                transferCoordinator?.clearActiveTransfer()
            },
            onViewFile = { uriStr ->
                runCatching {
                    val uri = Uri.parse(uriStr)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Open file with..."))
                }
            },
        )
    }
}
