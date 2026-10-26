// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/main/MainScreen.kt, GPL-3.0.
// Modified for Veyro.
// The tab graph inside follows app/src/main/java/com/castle/sefirah/navigation/graphs/MainNavGraph.kt.
package com.veyro.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.ui.devices.DevicesScreen
import com.veyro.ui.devices.DevicesViewModel
import com.veyro.ui.history.HistoryScreen
import com.veyro.ui.history.HistoryViewModel
import com.veyro.ui.home.HomeScreen
import com.veyro.ui.home.HomeViewModel
import com.veyro.ui.settings.SettingsScreen
import com.veyro.ui.settings.SettingsViewModel
import kotlin.reflect.KClass

private data class NavigationItem(
    val route: VeyroDestination,
    val routeClass: KClass<*>,
    val text: String,
    val selectedIcon: @Composable () -> ImageVector,
    val icon: @Composable () -> ImageVector,
)

// Sefirah animates its tab icons with animated vectors; Veyro swaps a filled and an outlined
// glyph instead, which needs no extra Compose artifact (animation-graphics is not in the cache).
private val navigationItems = listOf(
    NavigationItem(VeyroDestination.Home, VeyroDestination.Home::class, "Home", { VeyroIcons.HomeFill }, { Icons.Outlined.Home }),
    NavigationItem(VeyroDestination.Devices, VeyroDestination.Devices::class, "Devices", { VeyroIcons.Desktop }, { VeyroIcons.Desktop }),
    NavigationItem(VeyroDestination.History, VeyroDestination.History::class, "History", { VeyroIcons.History }, { VeyroIcons.History }),
    NavigationItem(VeyroDestination.Settings, VeyroDestination.Settings::class, "Settings", { VeyroIcons.SettingsFill }, { Icons.Outlined.Settings }),
)

/**
 * The tabbed main screen: Home, Devices, History and Settings with Sefirah's top bar (search on
 * Devices), bottom bar and the "Add device" floating action button on Devices.
 *
 * Veyro changes: a plain [NavigationBar] instead of the adaptive navigation suite (that artifact is
 * not in the Gradle cache), History kept as a fourth tab, no update check and no pull-to-refresh
 * here (Home owns its own). Pushed screens go through [rootNavController]; tab switches through
 * [homeNavController].
 */
@Composable
internal fun MainScreen(
    rootNavController: NavHostController,
    viewModelFactory: ViewModelProvider.Factory,
    homeViewModel: HomeViewModel,
    devicesViewModel: DevicesViewModel,
    onRequestMessagingPermissions: () -> Unit,
    /** Send files from Home: the system picker, then straight to the PC (ADR-038). */
    onSendFiles: (deviceId: String?) -> Unit,
    homeNavController: NavHostController = rememberNavController(),
) {
    val backStackState by homeNavController.currentBackStackEntryAsState()
    val currentDestination = backStackState?.destination
    val selectedItem = navigationItems
        .indexOfFirst { item -> currentDestination.isOn(item.routeClass) }
        .takeIf { it >= 0 } ?: 0
    val onDevices = navigationItems[selectedItem].route == VeyroDestination.Devices
    // Home draws its own header (the round settings button), like Phone Link's app.
    val onHome = navigationItems[selectedItem].route == VeyroDestination.Home

    var searchQuery by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (!onHome) {
                AppTopBar(
                    title = navigationItems[selectedItem].text,
                    searchEnabled = onDevices,
                    searchPlaceholder = "Search devices",
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                navigationItems.forEachIndexed { index, item ->
                    val selected = index == selectedItem
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navigateToTab(homeNavController, item.route) },
                        icon = { Icon(if (selected) item.selectedIcon() else item.icon(), contentDescription = null) },
                        label = { Text(text = item.text) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (onDevices) {
                FloatingActionButton(
                    onClick = { rootNavController.navigate(VeyroDestination.Pairing) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(VeyroIcons.Add, contentDescription = "Add device")
                }
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = homeNavController,
            startDestination = VeyroDestination.Home,
            modifier = Modifier.padding(contentPadding),
            enterTransition = {
                val from = tabIndex(initialState.destination)
                val to = tabIndex(targetState.destination)
                NavigationTransitions.enterTransition(isEnteringFromRight = to > from)
            },
            exitTransition = {
                val from = tabIndex(initialState.destination)
                val to = tabIndex(targetState.destination)
                NavigationTransitions.exitTransition(isExitingToRight = to < from)
            },
            popEnterTransition = { NavigationTransitions.rootPopEnterTransition(this) },
            popExitTransition = { NavigationTransitions.rootPopExitTransition(this) },
        ) {
            composable<VeyroDestination.Home> {
                HomeScreen(
                    viewModel = homeViewModel,
                    onOpenSettings = { navigateToTab(homeNavController, VeyroDestination.Settings) },
                    onPcSettings = { rootNavController.navigate(VeyroDestination.DeviceSettings(it.value)) },
                    onAddDevice = { rootNavController.navigate(VeyroDestination.Pairing) },
                    onSendFiles = { onSendFiles(it.value) },
                    onOpenHistory = { navigateToTab(homeNavController, VeyroDestination.History) },
                )
            }

            composable<VeyroDestination.Devices> {
                DevicesScreen(
                    homeViewModel = homeViewModel,
                    devicesViewModel = devicesViewModel,
                    searchQuery = searchQuery,
                    onDeviceClick = { rootNavController.navigate(VeyroDestination.DeviceSettings(it.value)) },
                    onAddDevice = { rootNavController.navigate(VeyroDestination.Pairing) },
                )
            }

            composable<VeyroDestination.History> {
                HistoryScreen(
                    viewModel = viewModel<HistoryViewModel>(factory = viewModelFactory),
                )
            }

            composable<VeyroDestination.Settings> {
                SettingsScreen(
                    viewModel = viewModel<SettingsViewModel>(factory = viewModelFactory),
                    onOpenDevices = { navigateToTab(homeNavController, VeyroDestination.Devices) },
                    onRequestMessagingPermissions = onRequestMessagingPermissions,
                )
            }
        }
    }
}

private fun NavDestination?.isOn(routeClass: KClass<*>): Boolean =
    this?.let { dest -> generateSequence(dest) { it.parent }.any { it.hasRoute(routeClass) } } == true

private fun tabIndex(destination: NavDestination): Int =
    navigationItems.indexOfFirst { destination.isOn(it.routeClass) }.coerceAtLeast(0)

/**
 * Tab switches pop to the start destination with saveState/restoreState and launchSingleTop, so
 * switching away and back neither rebuilds a tab nor stacks duplicates.
 */
internal fun navigateToTab(navController: NavController, route: VeyroDestination) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
