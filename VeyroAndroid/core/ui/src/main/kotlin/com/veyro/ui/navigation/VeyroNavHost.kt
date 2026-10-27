// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/navigation/graphs/RootNavGraph.kt, GPL-3.0.
// Modified for Veyro.
// The device routes follow app/src/main/java/com/castle/sefirah/navigation/graphs/DevicesNavGraph.kt.
package com.veyro.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.veyro.core.designsystem.components.VeyroDetailScaffold
import com.veyro.core.link.TransferCoordinator
import com.veyro.core.model.ActiveTransfer
import com.veyro.ui.clipboard.ClipboardScreen
import com.veyro.ui.clipboard.ClipboardViewModel
import com.veyro.ui.components.ActiveTransferBanner
import com.veyro.ui.components.ActiveTransferDialog
import com.veyro.ui.components.TransferBannerModel
import com.veyro.ui.devices.DeviceSettingsScreen
import com.veyro.ui.devices.DevicesViewModel
import com.veyro.ui.devices.MediaSettingsScreen
import com.veyro.ui.devices.MessagesSettingsScreen
import com.veyro.ui.home.HomeViewModel
import com.veyro.ui.pairing.PairingScreen
import com.veyro.ui.pairing.PairingViewModel
import com.veyro.ui.receive.ReceiveScreen
import com.veyro.ui.receive.ReceiveViewModel
import com.veyro.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The root navigation graph: the tabbed [MainScreen] plus every pushed screen, each with its own
 * back arrow (Sefirah's root graph).
 *
 * The one authoritative [ActiveTransferBanner] floats above the whole graph, so it shows on every
 * screen exactly once, and nothing under it moves when it appears or leaves (ADR-038). What it
 * shows and when it slides out is decided by [TransferBannerModel].
 *
 * Home and Devices share one [HomeViewModel], like Sefirah's ConnectionViewModel, so a Connect
 * started on one card shows on the other. It and [DevicesViewModel] are scoped to the Activity.
 *
 * [onSendFiles] is passed in from the Activity: Send files opens the system picker directly
 * (an `ActivityResultContract`, which only the Activity can launch) and sends the result to the
 * given PC, with no screen in between.
 */
@Composable
public fun VeyroNavHost(
    viewModelFactory: ViewModelProvider.Factory,
    /** Opens the system file picker and sends what is picked to these device ids (empty: the selected PC). */
    onSendFiles: (deviceIds: List<String>) -> Unit,
    transferCoordinator: TransferCoordinator? = null,
    modifier: Modifier = Modifier,
    /** Runtime permissions need the Activity, like the picker (ADR-027). */
    onRequestMessagingPermissions: () -> Unit = {},
    /** The photo read permission, for "Photos on PC" (ADR-034) and the wallpaper (ADR-038). */
    onRequestPhotosPermission: () -> Unit = {},
    /** The clipboard notification was tapped: show Clipboard, then call [onClipboardOpened]. */
    openClipboard: Boolean = false,
    onClipboardOpened: () -> Unit = {},
) {
    val navController = rememberNavController()
    LaunchedEffect(openClipboard) {
        if (!openClipboard) return@LaunchedEffect
        if (navController.currentDestination?.hasRoute(VeyroDestination.Clipboard::class) != true) {
            navController.navigate(VeyroDestination.Clipboard) { launchSingleTop = true }
        }
        onClipboardOpened()
    }
    val context = LocalContext.current

    val homeViewModel = viewModel<HomeViewModel>(factory = viewModelFactory)
    val devicesViewModel = viewModel<DevicesViewModel>(factory = viewModelFactory)

    val bannerScope = rememberCoroutineScope()
    val bannerModel = remember(transferCoordinator) {
        TransferBannerModel(
            scope = bannerScope,
            transfers = transferCoordinator?.activeTransfer ?: MutableStateFlow<ActiveTransfer?>(null),
            clear = { id -> transferCoordinator?.clearActiveTransfer(id) },
        )
    }
    val bannerTransfer by bannerModel.shown.collectAsStateWithLifecycle()

    var showTransferDialog by remember { mutableStateOf(false) }
    val activeTransfer by (transferCoordinator?.activeTransfer ?: remember { MutableStateFlow(null) })
        .collectAsStateWithLifecycle()
    // The details dialog belongs to one transfer; it must not reopen for the next one.
    val noTransfer = activeTransfer == null
    LaunchedEffect(noTransfer) { if (noTransfer) showTransferDialog = false }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(
            navController = navController,
            startDestination = VeyroDestination.Main,
            modifier = Modifier.fillMaxSize(),
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
                    onSendFiles = onSendFiles,
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
                    ClipboardScreen(
                        viewModel = viewModel<ClipboardViewModel>(factory = viewModelFactory),
                        modifier = Modifier.padding(padding),
                    )
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
                    onSendFiles = { onSendFiles(listOf(route.deviceId)) },
                    onOpenMediaSettings = { navController.navigate(VeyroDestination.DeviceMedia) },
                    onOpenMessagesSettings = { navController.navigate(VeyroDestination.DeviceMessages) },
                    onRequestMessagingPermissions = onRequestMessagingPermissions,
                    onRequestPhotosPermission = onRequestPhotosPermission,
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

        // Drawn after the graph, so it floats above every screen.
        ActiveTransferBanner(
            activeTransfer = bannerTransfer,
            onDismiss = { bannerModel.dismiss() },
            onClick = { showTransferDialog = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        )
    }

    if (showTransferDialog && activeTransfer != null) {
        ActiveTransferDialog(
            activeTransfer = activeTransfer,
            onCancel = { transferCoordinator?.cancel() },
            onDismiss = {
                showTransferDialog = false
                if (activeTransfer?.isTerminal == true) bannerModel.dismiss()
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
