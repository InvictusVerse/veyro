package com.veyro.di

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.veyro.core.network.LocalNetworkPermission
import com.veyro.core.network.LocalNetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Android 17's `ACCESS_LOCAL_NETWORK` for this install (ADR-061), as a [StateFlow] Home, the
 * background notification and the tiles follow.
 *
 * Whether the dialog may still be shown needs an Activity (`shouldShowRequestPermissionRationale`),
 * so [refresh] takes one when it has it; without one (boot, the service) the last answer is kept,
 * which only matters for which button to show, never for whether the network is blocked. Whether
 * Veyro asked before is a marker file, because Android does not say.
 *
 * Revoking a permission in system settings kills the process, so a later revoke is seen on the
 * next start; a grant in App info is seen when the Activity resumes.
 */
public class LocalNetworkAccess(private val context: Context) {

    private val askedMarker = File(context.noBackupFilesDir, ASKED_FILE)
    private var rationale = false
    private val _state = MutableStateFlow(read())

    public val state: StateFlow<LocalNetworkState> = _state.asStateFlow()

    /** True while local network traffic is blocked. Reads the grant now. */
    public val isBlocked: Boolean get() = refresh(null).isBlocked

    /** Re-reads the grant; with [activity], also whether the dialog may be shown again. */
    public fun refresh(activity: Activity?): LocalNetworkState {
        if (activity != null && LocalNetworkPermission.isRequired(Build.VERSION.SDK_INT)) {
            rationale = activity.shouldShowRequestPermissionRationale(LocalNetworkPermission.NAME)
        }
        return read().also { _state.value = it }
    }

    /** Called just before the system dialog is shown. */
    public fun markAsked() {
        runCatching { askedMarker.createNewFile() }
    }

    private fun read(): LocalNetworkState {
        val sdk = Build.VERSION.SDK_INT
        if (!LocalNetworkPermission.isRequired(sdk)) return LocalNetworkState.NOT_NEEDED
        val granted = context.checkSelfPermission(LocalNetworkPermission.NAME) == PackageManager.PERMISSION_GRANTED
        return LocalNetworkPermission.state(sdk, granted, askedBefore = askedMarker.exists(), rationaleAllowed = rationale)
    }

    private companion object {
        const val ASKED_FILE = "local-network-asked"
    }
}
