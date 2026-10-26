package com.veyro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.veyro.core.common.VeyroLog
import com.veyro.ui.onboarding.OnboardingScreen
import com.veyro.ui.onboarding.OnboardingViewModel
import com.veyro.core.companion.AndroidPhotoSource
import com.veyro.core.companion.MessagingPermissions
import com.veyro.core.designsystem.theme.VeyroTheme
import com.veyro.core.protocol.RelativePath
import com.veyro.core.link.OutgoingFile
import com.veyro.di.VeyroViewModelFactory
import com.veyro.navigation.VeyroNavHost
import com.veyro.ui.components.InboundPairingDialog
import com.veyro.ui.components.InboundTransferDialog
import java.io.IOException

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

        private const val STATE_SEND_TARGET = "sendTarget"
        private const val STATE_SEND_PENDING = "sendPending"
    }

    /**
     * The PCs the open picker is for (empty: the selected or most recent PC). Saved with the
     * instance state, so a rotation or process death while the picker is open keeps the targets.
     */
    private var sendTargets: ArrayList<String> = ArrayList()
    private var sendPending = false

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
                    showOnboarding -> OnboardingScreen(viewModel = onboarding, viewModelFactory = factory)
                    else -> {
                        // Launch-time permissions wait until onboarding is out of the way, so the
                        // first screen a new user sees is not a system dialog.
                        LaunchedEffect(Unit) { requestRequiredPermissions() }
                        VeyroNavHost(
                            viewModelFactory = factory,
                            transferCoordinator = container.transferCoordinator,
                            onSendFiles = ::launchSendPicker,
                            onRequestMessagingPermissions = ::requestMessagingPermissions,
                            onRequestPhotosPermission = ::requestPhotosPermission,
                        )
                    }
                }
                InboundPairingDialog(connectionManager = container.connectionManager)
                InboundTransferDialog(
                    transferCoordinator = container.transferCoordinator,
                    customDestination = container.settingsStore.destinationDir,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // ADR-038: "All files access" may have just been granted in system settings. Unchanged
        // wallpapers are not resent, so this costs one local read.
        (application as VeyroApplication).container.wallpaperBridge.refresh()
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

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
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

    /**
     * Turns a picked `content://` URI into something sendable.
     *
     * The display name and size come from the content provider, not from the URI path: a
     * `content://` URI frequently has no meaningful path at all. A provider that reports neither is
     * **skipped rather than guessed at**, because an item with an unknown size breaks the chunk plan
     * and would fail at the far end as a hash mismatch - which the user would read as a network
     * problem.
     *
     * The stream is opened lazily, each time, rather than being held open. A picker selection can
     * sit on screen for minutes before the user hits Send, and an open file descriptor that long is
     * both a leak and a liability if the provider revokes access.
     */
    private fun describe(uri: Uri): OutgoingFile? {
        // Persist the read grant so the URI still resolves when the transfer actually starts.
        // Without it, opening the stream later can fail with a SecurityException partway through.
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure {
            // Not fatal: many providers grant only a transient permission, which is still valid for
            // this process. Logged because it predicts a later failure if the send is delayed.
            VeyroLog.d(TAG) { "No persistable permission for a picked file: ${it.message}" }
        }

        var name: String? = null
        var size = -1L

        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        name = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        }.onFailure { VeyroLog.w(TAG, it) { "Could not query a picked file" } }

        val rawName = name ?: uri.lastPathSegment?.substringAfterLast('/')
        if (rawName.isNullOrBlank() || size < 0) {
            VeyroLog.w(TAG) { "Skipping a picked file with no reported name or size" }
            return null
        }

        // Sanitised here so what the user sees in the list is what goes on the wire. This is a
        // courtesy to the receiver, not a security control - the receiver validates regardless
        // (REQ-T11), because a sender's idea of "sanitised" is not something to trust.
        val safeName = RelativePath.sanitizeOutgoingSegment(rawName)

        return OutgoingFile(
            effectiveName = safeName,
            sizeBytes = size,
            lastModifiedEpochMillis = System.currentTimeMillis(),
            openStream = {
                contentResolver.openInputStream(uri)
                    ?: throw IOException("could not open $safeName")
            },
        )
    }
}
