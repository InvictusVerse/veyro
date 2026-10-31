package com.veyro.ui.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
import com.veyro.ui.keepalive.KeepConnectedScreen
import com.veyro.ui.keepalive.KeepConnectedViewModel
import com.veyro.ui.pairing.PairingScreen
import com.veyro.ui.pairing.PairingViewModel
import com.veyro.ui.receive.ReceiveScreen
import com.veyro.ui.receive.ReceiveViewModel
import com.veyro.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Root of the app's navigation: the tabbed [MainScreen] as the start destination, and every other
 * screen pushed over it with its own back arrow.
 *
 * Two things live above the graph so they behave the same on every screen:
 *  - the transfer banner. It floats over the graph in a [Box] and is drawn exactly once, so it
 *    never shifts the content underneath when it appears or slides out (ADR-038). What it shows,
 *    and when it leaves, is decided by [TransferBannerModel].
 *  - the transfer details dialog, opened by tapping the banner.
 *
 * Home and Devices read the same [HomeViewModel], so a connection started from one card shows on
 * the other. It and [DevicesViewModel] are scoped to the Activity.
 *
 * File and folder picking need an `ActivityResultContract`, which only the Activity can launch,
 * so [onSendFiles] and [onSendFolder] are handed in: they open the system picker and send the
 * result to the given devices, with no screen of ours in between.
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
    /** ADR-061: asks for the local network permission, or opens App info when only that works. */
    onFixLocalNetwork: () -> Unit = {},
    /** Opens the system folder picker and sends the folder to these device ids (ADR-064). */
    onSendFolder: ((deviceIds: List<String>) -> Unit)? = null,
) {
    val navController = rememberNavController()
    ShowClipboardOnRequest(navController, requested = openClipboard, onShown = onClipboardOpened)

    val context = LocalContext.current
    val homeViewModel = viewModel<HomeViewModel>(factory = viewModelFactory)
    val devicesViewModel = viewModel<DevicesViewModel>(factory = viewModelFactory)

    // Without a coordinator (previews, tests) the feed is simply always empty.
    val transferFeed: StateFlow<ActiveTransfer?> = remember(transferCoordinator) {
        transferCoordinator?.activeTransfer ?: MutableStateFlow<ActiveTransfer?>(null)
    }
    val bannerScope = rememberCoroutineScope()
    val bannerModel = remember(transferCoordinator) {
        TransferBannerModel(
            scope = bannerScope,
            transfers = transferFeed,
            clear = { id -> transferCoordinator?.clearActiveTransfer(id) },
        )
    }
    val bannerTransfer by bannerModel.shown.collectAsStateWithLifecycle()
    val activeTransfer by transferFeed.collectAsStateWithLifecycle()

    var detailsOpen by remember { mutableStateOf(false) }
    // The details dialog belongs to one transfer; it must not reopen for the next one.
    val noTransfer = activeTransfer == null
    LaunchedEffect(noTransfer) { if (noTransfer) detailsOpen = false }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(
            navController = navController,
            startDestination = VeyroDestination.Main,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { pushEnter() },
            exitTransition = { pushExit() },
            popEnterTransition = { backEnter() },
            popExitTransition = { backExit() },
        ) {
            rootGraph(
                navController = navController,
                viewModelFactory = viewModelFactory,
                homeViewModel = homeViewModel,
                devicesViewModel = devicesViewModel,
                onSendFiles = onSendFiles,
                onSendFolder = onSendFolder,
                onRequestMessagingPermissions = onRequestMessagingPermissions,
                onRequestPhotosPermission = onRequestPhotosPermission,
                onFixLocalNetwork = onFixLocalNetwork,
            )
        }

        // Declared after the graph, so it is drawn over every screen.
        ActiveTransferBanner(
            activeTransfer = bannerTransfer,
            onDismiss = { bannerModel.dismiss() },
            onClick = { detailsOpen = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        )
    }

    if (detailsOpen && activeTransfer != null) {
        ActiveTransferDialog(
            activeTransfer = activeTransfer,
            onCancel = { transferCoordinator?.cancel() },
            onDismiss = {
                detailsOpen = false
                if (activeTransfer?.isTerminal == true) bannerModel.dismiss()
            },
            onViewFile = { uriString -> openInViewer(context, uriString) },
        )
    }
}

/** Shows Clipboard once when [requested] turns true (the notification was tapped), then calls [onShown]. */
@Composable
private fun ShowClipboardOnRequest(navController: NavHostController, requested: Boolean, onShown: () -> Unit) {
    LaunchedEffect(requested) {
        if (!requested) return@LaunchedEffect
        val alreadyThere = navController.currentDestination?.hasRoute(VeyroDestination.Clipboard::class) == true
        if (!alreadyThere) {
            navController.navigate(VeyroDestination.Clipboard) { launchSingleTop = true }
        }
        onShown()
    }
}

/** Lets the user pick an app to open a received file; a missing handler is ignored. */
private fun openInViewer(context: Context, uriString: String) {
    runCatching {
        val uri = Uri.parse(uriString)
        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(view, "Open file with..."))
    }
}

/** A pushed screen: a titled page whose back arrow pops the root stack. */
@Composable
private fun DetailPage(
    title: String,
    navController: NavController,
    content: @Composable (PaddingValues) -> Unit,
) {
    VeyroDetailScaffold(title = title, onNavigateBack = { navController.navigateUp() }) { padding ->
        content(padding)
    }
}

/** Every destination of the root graph. The tabs themselves are inside [MainScreen]. */
private fun NavGraphBuilder.rootGraph(
    navController: NavHostController,
    viewModelFactory: ViewModelProvider.Factory,
    homeViewModel: HomeViewModel,
    devicesViewModel: DevicesViewModel,
    onSendFiles: (deviceIds: List<String>) -> Unit,
    onSendFolder: ((deviceIds: List<String>) -> Unit)?,
    onRequestMessagingPermissions: () -> Unit,
    onRequestPhotosPermission: () -> Unit,
    onFixLocalNetwork: () -> Unit,
) {
    composable<VeyroDestination.Main> {
        MainScreen(
            rootNavController = navController,
            viewModelFactory = viewModelFactory,
            homeViewModel = homeViewModel,
            devicesViewModel = devicesViewModel,
            onRequestMessagingPermissions = onRequestMessagingPermissions,
            onSendFiles = onSendFiles,
            onFixLocalNetwork = onFixLocalNetwork,
            onSendFolder = onSendFolder,
        )
    }

    composable<VeyroDestination.KeepConnected> {
        DetailPage("Keep Veyro connected", navController) { padding ->
            KeepConnectedScreen(
                viewModel = viewModel<KeepConnectedViewModel>(factory = viewModelFactory),
                onFixLocalNetwork = onFixLocalNetwork,
                modifier = Modifier.padding(padding),
            )
        }
    }

    composable<VeyroDestination.Pairing> {
        DetailPage("Add device", navController) { padding ->
            PairingScreen(
                viewModel = viewModel<PairingViewModel>(factory = viewModelFactory),
                modifier = Modifier.padding(padding),
            )
        }
    }

    composable<VeyroDestination.Receive> {
        DetailPage("Receive", navController) { padding ->
            ReceiveScreen(
                viewModel = viewModel<ReceiveViewModel>(factory = viewModelFactory),
                modifier = Modifier.padding(padding),
            )
        }
    }

    composable<VeyroDestination.Clipboard> {
        DetailPage("Clipboard", navController) { padding ->
            ClipboardScreen(
                viewModel = viewModel<ClipboardViewModel>(factory = viewModelFactory),
                modifier = Modifier.padding(padding),
            )
        }
    }

    composable<VeyroDestination.DeviceSettings> { entry ->
        val deviceId = entry.toRoute<VeyroDestination.DeviceSettings>().deviceId
        DeviceSettingsScreen(
            deviceId = deviceId,
            homeViewModel = homeViewModel,
            devicesViewModel = devicesViewModel,
            settingsViewModel = viewModel<SettingsViewModel>(factory = viewModelFactory),
            onNavigateBack = { navController.navigateUp() },
            onSendFiles = { onSendFiles(listOf(deviceId)) },
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
