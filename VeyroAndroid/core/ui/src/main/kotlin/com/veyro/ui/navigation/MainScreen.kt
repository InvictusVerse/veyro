package com.veyro.ui.navigation

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
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.veyro.core.designsystem.icons.VeyroIcons
import com.veyro.core.model.DeviceId
import com.veyro.ui.devices.DevicesScreen
import com.veyro.ui.devices.DevicesViewModel
import com.veyro.ui.history.HistoryScreen
import com.veyro.ui.history.HistoryViewModel
import com.veyro.ui.home.HomeScreen
import com.veyro.ui.home.HomeViewModel
import com.veyro.ui.settings.SettingsScreen
import com.veyro.ui.settings.SettingsViewModel
import kotlin.reflect.KClass

/** One entry of the bottom bar. The selected entry gets the filled icon, the others the outlined one. */
private class Tab(
    val destination: VeyroDestination,
    val routeType: KClass<out VeyroDestination>,
    val label: String,
    val outlinedIcon: @Composable () -> ImageVector,
    val filledIcon: @Composable () -> ImageVector,
)

// Order matters: it is the left-to-right order of the bar and decides which way tab changes slide.
// Devices and History have a single glyph, so both states use it.
private val tabs = listOf(
    Tab(VeyroDestination.Home, VeyroDestination.Home::class, "Home",
        outlinedIcon = { Icons.Outlined.Home }, filledIcon = { VeyroIcons.HomeFill }),
    Tab(VeyroDestination.Devices, VeyroDestination.Devices::class, "Devices",
        outlinedIcon = { VeyroIcons.Desktop }, filledIcon = { VeyroIcons.Desktop }),
    Tab(VeyroDestination.History, VeyroDestination.History::class, "History",
        outlinedIcon = { VeyroIcons.History }, filledIcon = { VeyroIcons.History }),
    Tab(VeyroDestination.Settings, VeyroDestination.Settings::class, "Settings",
        outlinedIcon = { Icons.Outlined.Settings }, filledIcon = { VeyroIcons.SettingsFill }),
)

private fun NavDestination?.isInside(tab: Tab): Boolean =
    this?.hierarchy?.any { it.hasRoute(tab.routeType) } == true

private fun positionOf(destination: NavDestination): Int =
    tabs.indexOfFirst { destination.isInside(it) }.coerceAtLeast(0)

/**
 * The tabbed main screen: Home, Devices, History and Settings behind a bottom bar, with a top bar
 * on every tab but Home (Devices adds search) and an "Add device" button on Devices.
 *
 * Tab changes go through [homeNavController]; screens that are pushed over the tabs go through
 * [rootNavController]. The transfer banner is not drawn here, [VeyroNavHost] floats it above
 * everything.
 */
@Composable
internal fun MainScreen(
    rootNavController: NavHostController,
    viewModelFactory: ViewModelProvider.Factory,
    homeViewModel: HomeViewModel,
    devicesViewModel: DevicesViewModel,
    onRequestMessagingPermissions: () -> Unit,
    /** Send files from Home: the system picker, then straight to the chosen PCs (ADR-038). */
    onSendFiles: (deviceIds: List<String>) -> Unit,
    homeNavController: NavHostController = rememberNavController(),
    /** ADR-061: Home's local network notice. */
    onFixLocalNetwork: () -> Unit = {},
    /** Send folder: the system folder picker, then straight to the chosen devices (ADR-064). */
    onSendFolder: ((deviceIds: List<String>) -> Unit)? = null,
) {
    val currentEntry by homeNavController.currentBackStackEntryAsState()
    val currentTab = tabs.firstOrNull { currentEntry?.destination.isInside(it) } ?: tabs.first()

    var searchQuery by rememberSaveable { mutableStateOf("") }

    val openPage: (VeyroDestination) -> Unit = { rootNavController.navigate(it) }
    val openTab: (VeyroDestination) -> Unit = { navigateToTab(homeNavController, it) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            // Home draws its own header with the round settings button.
            if (currentTab.destination != VeyroDestination.Home) {
                AppTopBar(
                    title = currentTab.label,
                    searchEnabled = currentTab.destination == VeyroDestination.Devices,
                    searchPlaceholder = "Search devices",
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                )
            }
        },
        bottomBar = { TabBar(selectedTab = currentTab, onSelect = { openTab(it.destination) }) },
        floatingActionButton = {
            if (currentTab.destination == VeyroDestination.Devices) {
                AddDeviceButton(onClick = { openPage(VeyroDestination.Pairing) })
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = homeNavController,
            startDestination = VeyroDestination.Home,
            modifier = Modifier.padding(contentPadding),
            enterTransition = {
                tabEnter(fromRight = positionOf(targetState.destination) > positionOf(initialState.destination))
            },
            exitTransition = {
                tabExit(toRight = positionOf(targetState.destination) < positionOf(initialState.destination))
            },
            popEnterTransition = { backEnter() },
            popExitTransition = { backExit() },
        ) {
            composable<VeyroDestination.Home> {
                HomeScreen(
                    viewModel = homeViewModel,
                    onOpenSettings = { openTab(VeyroDestination.Settings) },
                    onPcSettings = { openPage(VeyroDestination.DeviceSettings(it.value)) },
                    onAddDevice = { openPage(VeyroDestination.Pairing) },
                    onSendFiles = { ids -> onSendFiles(ids.map { it.value }) },
                    onOpenHistory = { openTab(VeyroDestination.History) },
                    onOpenClipboard = { openPage(VeyroDestination.Clipboard) },
                    onFixLocalNetwork = onFixLocalNetwork,
                    onOpenKeepConnected = { openPage(VeyroDestination.KeepConnected) },
                    onSendFolder = onSendFolder?.let { send -> { ids: List<DeviceId> -> send(ids.map { it.value }) } },
                )
            }

            composable<VeyroDestination.Devices> {
                DevicesScreen(
                    homeViewModel = homeViewModel,
                    devicesViewModel = devicesViewModel,
                    searchQuery = searchQuery,
                    onDeviceClick = { openPage(VeyroDestination.DeviceSettings(it.value)) },
                    onAddDevice = { openPage(VeyroDestination.Pairing) },
                    onSendFiles = { onSendFiles(listOf(it.value)) },
                    onSendFolder = onSendFolder?.let { send -> { id: DeviceId -> send(listOf(id.value)) } },
                )
            }

            composable<VeyroDestination.History> {
                HistoryScreen(viewModel = viewModel<HistoryViewModel>(factory = viewModelFactory))
            }

            composable<VeyroDestination.Settings> {
                SettingsScreen(
                    viewModel = viewModel<SettingsViewModel>(factory = viewModelFactory),
                    onOpenDevices = { openTab(VeyroDestination.Devices) },
                    onRequestMessagingPermissions = onRequestMessagingPermissions,
                    onOpenKeepConnected = { openPage(VeyroDestination.KeepConnected) },
                )
            }
        }
    }
}

@Composable
private fun TabBar(selectedTab: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar {
        for (tab in tabs) {
            val selected = tab === selectedTab
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.filledIcon() else tab.outlinedIcon(),
                        contentDescription = null,
                    )
                },
                label = { Text(text = tab.label) },
            )
        }
    }
}

@Composable
private fun AddDeviceButton(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Icon(VeyroIcons.Add, contentDescription = "Add device")
    }
}

/**
 * Switches to a tab without growing the back stack: it pops back to the start destination,
 * saving the tab being left and restoring the one being opened, and never duplicates the top
 * entry. A tab keeps its scroll position and state when you come back to it.
 */
internal fun navigateToTab(navController: NavController, route: VeyroDestination) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
