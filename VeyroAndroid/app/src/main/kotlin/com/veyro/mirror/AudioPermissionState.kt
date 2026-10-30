package com.veyro.mirror

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.veyro.core.link.StreamRules

/**
 * Android's audio permission for the phone's sound while mirroring (ADR-066), as the PC sees it:
 * `granted`, `ask` (never asked), `denied` (Android shows its dialog again) or `blocked` (only
 * Veyro's App info screen can grant it). Android does not say which of the last two applies, so
 * the answer to Veyro's own last request is kept (app data, no user data).
 */
internal class AudioPermissionState(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun granted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun current(): String = when {
        granted() -> StreamRules.AUDIO_GRANTED
        else -> prefs.getString(KEY_LAST_ANSWER, null)?.takeIf { it == StreamRules.AUDIO_DENIED || it == StreamRules.AUDIO_BLOCKED }
            ?: StreamRules.AUDIO_ASK
    }

    /** After Android's dialog: [showRationale] is what `shouldShowRequestPermissionRationale` says now. */
    fun record(granted: Boolean, showRationale: Boolean) {
        prefs.edit().apply {
            if (granted) remove(KEY_LAST_ANSWER) else putString(KEY_LAST_ANSWER, if (showRationale) StreamRules.AUDIO_DENIED else StreamRules.AUDIO_BLOCKED)
        }.apply()
    }

    private companion object {
        const val PREFS = "veyro_mirror"
        const val KEY_LAST_ANSWER = "audioPermissionAnswer"
    }
}
