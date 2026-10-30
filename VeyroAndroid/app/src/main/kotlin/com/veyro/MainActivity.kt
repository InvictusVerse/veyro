package com.veyro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.veyro.core.common.VeyroLog
import com.veyro.ui.onboarding.OnboardingScreen
import com.veyro.ui.onboarding.OnboardingViewModel
import com.veyro.core.companion.AndroidPhotoSource
import com.veyro.core.companion.MessagingPermissions
import com.veyro.core.designsystem.theme.VeyroTheme
import com.veyro.core.link.OutgoingFile
import com.veyro.core.network.LocalNetworkFix
import com.veyro.core.network.LocalNetworkPermission
import com.veyro.di.VeyroViewModelFactory
import com.veyro.ui.navigation.VeyroNavHost
import com.veyro.ui.components.InboundPairingDialog
import com.veyro.ui.components.InboundTransferDialog

/**
 * The only Activity.
 *
 * It owns the two things no other layer can: the file picker (an `ActivityResultContract` needs an
 * Activity) and the window. Everything else lives in the container.
 *
 * `enableEdgeToEdge()` rather than the older `WindowCompat.setDecorFitsSystemWindows(window, false)`
 * plus manual `statusBarColor`/`navigationBarColor`: those two properties are deprecated from API 35
 * and are no-ops on a modern target, so code using them looks like it works while doing nothing.
 */
public class MainActivity : ComponentActivity() {

    public companion object {
        private const val TAG = "MainActivity"

        /** From the background notification's Send files: pick files for [EXTRA_SEND_TO]. */
        public const val ACTION_SEND_FILES: String = "com.veyro.action.SEND_FILES"
        public const val EXTRA_SEND_TO: String = "com.veyro.extra.SEND_TO"

        /** From the "Copied on <PC>" notification: show the Clipboard screen (ADR-043). */
        public const val ACTION_OPEN_CLIPBOARD: String = "com.veyro.action.OPEN_CLIPBOARD"

        private const val STATE_SEND_TARGET = "sendTarget"
        private const val STATE_SEND_PENDING = "sendPending"
    }

    /**
     * The PCs the open picker is for (empty: the selected or most recent PC). Saved with the
     * instance state, so a rotation or process death while the picker is open keeps the targets.
     */
    private var sendTargets: ArrayList<String> = ArrayList()
    private var sendPending = false

    /** Set by the clipboard notification; the nav host navigates once and clears it. */
    private var openClipboard by mutableStateOf(false)

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val targets = sendTargets.toList()
        val wasPending = sendPending
        sendTargets = ArrayList()
        sendPending = false
        if (!wasPending || uris.isNullOrEmpty()) return@registerForActivityResult
        val files = uris.mapNotNull { describe(it) }
        if (files.isEmpty()) return@registerForActivityResult
        // ADR-038: straight to the PCs, no Send screen; the banner shows progress.
        (application as VeyroApplication).container.sendFiles(targets, files)
    }

    /** Send folder (ADR-064): the system folder picker; the tree grant needs no storage permission. */
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val targets = sendTargets.toList()
        val wasPending = sendPending
        sendTargets = ArrayList()
        sendPending = false
        if (!wasPending || uri == null) return@registerForActivityResult
        (application as VeyroApplication).container.sendFolder(targets, uri)
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        result.forEach { (perm, granted) ->
            VeyroLog.i(TAG) { "[permission-result] $perm granted=$granted" }
        }
        // The wallpaper may have become readable (ADR-038).
        if (result[AndroidPhotoSource.permission()] == true) {
            (application as VeyroApplication).container.wallpaperBridge.refresh()
        }
        // ADR-061: Home, the notification and the tiles follow the new answer.
        if (LocalNetworkPermission.NAME in result) {
            (application as VeyroApplication).container.localNetworkAccess.refresh(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as VeyroApplication).container
        val factory = VeyroViewModelFactory(container)
        if (savedInstanceState != null) {
            sendTargets = savedInstanceState.getStringArrayList(STATE_SEND_TARGET) ?: ArrayList()
            sendPending = savedInstanceState.getBoolean(STATE_SEND_PENDING, false)
        } else {
            // A recreated Activity gets the same intent again; the picker must open only once.
            readSendRequest(intent)
        }

        setContent {
            VeyroTheme {
                // First run shows onboarding instead of the main UI (ADR-030). The gate is
                // decided once by the ViewModel, so rotation keeps whichever is showing.
                val onboarding = viewModel<OnboardingViewModel>(factory = factory)
                val onboardingState by onboarding.state.collectAsStateWithLifecycle()
                val showOnboarding = onboardingState.gate == OnboardingViewModel.Gate.REQUIRED &&
                    !onboardingState.completed

                when {
                    onboardingState.gate == OnboardingViewModel.Gate.LOADING -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    )
                    showOnboarding -> {
                        val localNetwork by container.localNetworkAccess.state.collectAsStateWithLifecycle()
                        OnboardingScreen(
                            viewModel = onboarding,
                            viewModelFactory = factory,
                            localNetwork = localNetwork,
                            onFixLocalNetwork = ::fixLocalNetwork,
                        )
                    }
                    else -> {
                        // Launch-time permissions wait until onboarding is out of the way, so the
                        // first screen a new user sees is not a system dialog.
                        LaunchedEffect(Unit) { requestRequiredPermissions() }
                        VeyroNavHost(
                            viewModelFactory = factory,
                            transferCoordinator = container.transferCoordinator,
                            onSendFiles = ::launchSendPicker,
                            onSendFolder = ::launchFolderPicker,
                            onRequestMessagingPermissions = ::requestMessagingPermissions,
                            onRequestPhotosPermission = ::requestPhotosPermission,
                            openClipboard = openClipboard,
                            onClipboardOpened = { openClipboard = false },
                            onFixLocalNetwork = ::fixLocalNetwork,
                        )
                    }
                }
                InboundPairingDialog(connectionManager = container.connectionManager)
                InboundTransferDialog(
                    transferCoordinator = container.transferCoordinator,
                    customDestination = remember { container.receiveFolder.map { it?.name } },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // ADR-038: "All files access" may have just been granted in system settings. Unchanged
        // wallpapers are not resent, so this costs one local read.
        (application as VeyroApplication).container.wallpaperBridge.refresh()
        // ADR-061: Nearby devices may have just been allowed in App info.
        (application as VeyroApplication).container.localNetworkAccess.refresh(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readSendRequest(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SEND_TARGET, sendTargets)
        outState.putBoolean(STATE_SEND_PENDING, sendPending)
    }

    private fun readSendRequest(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_CLIPBOARD) {
            intent.action = Intent.ACTION_MAIN
            com.veyro.di.SystemClipboard.unseen.set(0)
            openClipboard = true
            return
        }
        if (intent?.action != ACTION_SEND_FILES) return
        val target = intent.getStringExtra(EXTRA_SEND_TO)
        // Consumed, so returning to the task later does not open the picker again.
        intent.action = Intent.ACTION_MAIN
        launchSendPicker(listOfNotNull(target))
    }

    /**
     * Every Send files entry point (Home's tile or its "Send to" choice, a device's settings, the
     * background notification) opens the system picker directly (ADR-038).
     */
    private fun launchSendPicker(deviceIds: List<String>) {
        sendTargets = ArrayList(deviceIds)
        sendPending = true
        try {
            // Any type rather than a list: Veyro transfers arbitrary files, and restricting the
            // picker would only stop the user sending what they wanted.
            pickFiles.launch(arrayOf("*/*"))
        } catch (e: android.content.ActivityNotFoundException) {
            sendPending = false
            VeyroLog.w(TAG) { "No document picker on this device" }
        }
    }

    /** Send folder from Home or a device card (ADR-064): the folder picker, then straight to the devices. */
    private fun launchFolderPicker(deviceIds: List<String>) {
        sendTargets = ArrayList(deviceIds)
        sendPending = true
        try {
            pickFolder.launch(null)
        } catch (e: android.content.ActivityNotFoundException) {
            sendPending = false
            VeyroLog.w(TAG) { "No folder picker on this device" }
        }
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
        // ADR-061: Android 17 blocks the local network until this is granted. Asked once with the
        // others (same Nearby devices group, so one dialog); never below API 37.
        val localNetwork = (application as VeyroApplication).container.localNetworkAccess
        if (LocalNetworkPermission.requestAtLaunch(localNetwork.refresh(this))) {
            localNetwork.markAsked()
            needed += LocalNetworkPermission.NAME
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.NEARBY_WIFI_DEVICES
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }
        if (needed.isNotEmpty()) {
            VeyroLog.i(TAG) { "[p2p-perm] Requesting runtime permissions: $needed" }
            requestPermissions.launch(needed.toTypedArray())
        } else {
            VeyroLog.i(TAG) { "[p2p-perm] All required runtime permissions already granted" }
        }
    }

    /**
     * ADR-061: Allow on Home's notice, onboarding's Pair page and the checklist. Shows the system
     * dialog while Android still allows it, otherwise App info, where the user allows Nearby devices.
     */
    private fun fixLocalNetwork() {
        val access = (application as VeyroApplication).container.localNetworkAccess
        when (LocalNetworkPermission.fix(access.refresh(this))) {
            LocalNetworkFix.NONE -> Unit
            LocalNetworkFix.REQUEST -> {
                VeyroLog.i(TAG) { "[p2p-perm] Requesting local network access" }
                access.markAsked()
                requestPermissions.launch(arrayOf(LocalNetworkPermission.NAME))
            }
            LocalNetworkFix.OPEN_SETTINGS -> try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                )
            } catch (e: android.content.ActivityNotFoundException) {
                VeyroLog.w(TAG) { "No App info screen on this device" }
            }
        }
    }

    /**
     * Requested only when the user turns on "Messages and calls on PC" (ADR-027 point 4), never at
     * launch. The Settings row re-reads what is still missing when the screen resumes.
     */
    private fun requestMessagingPermissions() {
        val needed = MessagingPermissions.missing(this)
        if (needed.isEmpty()) return
        VeyroLog.i(TAG) { "Requesting ${needed.size} messaging permission(s)" }
        requestPermissions.launch(needed.toTypedArray())
    }

    /**
     * Requested only when the user turns on "Photos on PC" (ADR-034): `READ_MEDIA_IMAGES` on
     * API 33+, `READ_EXTERNAL_STORAGE` below. The switch re-reads the grant when the screen resumes.
     */
    private fun requestPhotosPermission() {
        if (AndroidPhotoSource.isGranted(this)) return
        VeyroLog.i(TAG) { "Requesting the photo permission" }
        requestPermissions.launch(arrayOf(AndroidPhotoSource.permission()))
    }

    private fun describe(uri: Uri): OutgoingFile? = SharedUris.describe(this, uri)
}
