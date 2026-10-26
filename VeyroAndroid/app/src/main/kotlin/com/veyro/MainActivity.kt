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
import com.veyro.core.common.VeyroLog
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

    private companion object {
        const val TAG = "MainActivity"
    }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val container = (application as VeyroApplication).container
        // Written into the process-scoped buffer rather than handed to a callback, so a rotation
        // between launching the picker and its result cannot drop the selection.
        container.pickedFiles.add(uris.mapNotNull { describe(it) })
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        result.forEach { (perm, granted) ->
            VeyroLog.i(TAG) { "[permission-result] $perm granted=$granted" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()

        val container = (application as VeyroApplication).container
        val factory = VeyroViewModelFactory(container)

        setContent {
            VeyroTheme {
                VeyroNavHost(
                    viewModelFactory = factory,
                    transferCoordinator = container.transferCoordinator,
                    // `*/*` rather than a type list: Veyro transfers arbitrary files, and
                    // restricting the picker would only stop the user sending what they wanted.
                    onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                )
                InboundPairingDialog(connectionManager = container.connectionManager)
                InboundTransferDialog(transferCoordinator = container.transferCoordinator)
            }
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
