package com.veyro.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.veyro.core.link.TransferCoordinator
import com.veyro.feature.clipboard.ClipboardScreen
import com.veyro.ui.components.ActiveTransferBanner
import com.veyro.ui.components.ActiveTransferDialog
import com.veyro.ui.devices.DevicesScreen
import com.veyro.ui.devices.DevicesViewModel
import com.veyro.ui.history.HistoryScreen
import com.veyro.ui.history.HistoryViewModel
import com.veyro.ui.home.HomeScreen
import com.veyro.ui.home.HomeViewModel
import com.veyro.ui.pairing.PairingScreen
import com.veyro.ui.pairing.PairingViewModel
import com.veyro.ui.receive.ReceiveScreen
import com.veyro.ui.receive.ReceiveViewModel
import com.veyro.ui.send.SendScreen
import com.veyro.ui.send.SendViewModel
import com.veyro.ui.settings.SettingsScreen
import com.veyro.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.reflect.KClass

private data class TopLevelEntry(
    val route: VeyroDestination,
    val routeClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
)

// Icons come from material-icons-core, never material-icons-extended: that artifact declares every
// Material icon as code and cost 30 MB of dex when measured (ADR-017). These four are in the core
// set. A better-fitting glyph should be added as a single vector drawable.
private val topLevel = listOf(
    TopLevelEntry(VeyroDestination.Home, VeyroDestination.Home::class, "Home", Icons.Filled.Home),
    TopLevelEntry(VeyroDestination.Devices, VeyroDestination.Devices::class, "Devices", Icons.Filled.Phone),
    TopLevelEntry(VeyroDestination.History, VeyroDestination.History::class, "History", Icons.Filled.List),
    TopLevelEntry(VeyroDestination.Settings, VeyroDestination.Settings::class, "Settings", Icons.Filled.Settings),
)

/**
 * The single navigation graph.
 *
 * Four top-level destinations in a bottom bar, with detail destinations pushed on top. Tab switches
 * use `saveState`/`restoreState` with `launchSingleTop`, so switching away and back does not rebuild
 * a tab or stack duplicates - which also means a transfer screen is never silently recreated
 * mid-transfer.
 *
 * `onPickFiles` is passed in from the Activity rather than handled here, because the file picker is
 * an `ActivityResultContract` and only the Activity can launch one. Keeping that boundary explicit
 * stops the navigation layer from needing an Activity reference.
 */
@Composable
public fun VeyroNavHost(
    viewModelFactory: ViewModelProvider.Factory,
    onPickFiles: () -> Unit,
    transferCoordinator: TransferCoordinator? = null,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val context = LocalContext.current

    var showTransferDialog by remember { mutableStateOf(false) }
    val activeTransfer by (transferCoordinator?.activeTransfer ?: remember { MutableStateFlow(null) })
        .collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                topLevel.forEach { entry ->
                    val selected = currentDestination
                        ?.hierarchy
                        ?.any { it.hasRoute(entry.routeClass) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(entry.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Authoritative active transfer banner placed cleanly above the screen content
            ActiveTransferBanner(
                activeTransfer = activeTransfer,
                onCancel = { transferCoordinator?.cancel() },
                onDismiss = { transferCoordinator?.clearActiveTransfer() },
                onClick = { showTransferDialog = true },
                modifier = Modifier.fillMaxWidth(),
            )

            NavHost(
                navController = navController,
                startDestination = VeyroDestination.Home,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                composable<VeyroDestination.Home> {
                    HomeScreen(
                        viewModel = viewModel<HomeViewModel>(factory = viewModelFactory),
                        onDeviceClick = { navController.navigate(VeyroDestination.Send) },
                        onPairClick = { navController.navigate(VeyroDestination.Pairing) },
                    )
                }

                composable<VeyroDestination.Devices> {
                    DevicesScreen(
                        viewModel = viewModel<DevicesViewModel>(factory = viewModelFactory),
                        onPairNewDevice = { navController.navigate(VeyroDestination.Pairing) },
                    )
                }

                composable<VeyroDestination.History> {
                    HistoryScreen(viewModel = viewModel<HistoryViewModel>(factory = viewModelFactory))
                }

                composable<VeyroDestination.Settings> {
                    SettingsScreen(viewModel = viewModel<SettingsViewModel>(factory = viewModelFactory))
                }

                composable<VeyroDestination.Pairing> {
                    PairingScreen(viewModel = viewModel<PairingViewModel>(factory = viewModelFactory))
                }

                composable<VeyroDestination.Send> {
                    SendScreen(
                        viewModel = viewModel<SendViewModel>(factory = viewModelFactory),
                        onPickFiles = onPickFiles,
                    )
                }

                composable<VeyroDestination.Receive> {
                    ReceiveScreen(viewModel = viewModel<ReceiveViewModel>(factory = viewModelFactory))
                }

                composable<VeyroDestination.Clipboard> { ClipboardScreen() }
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

private val NavDestination.hierarchy: Sequence<NavDestination>
    get() = generateSequence(this) { it.parent }
