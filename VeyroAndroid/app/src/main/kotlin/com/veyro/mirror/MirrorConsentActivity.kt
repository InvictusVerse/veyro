package com.veyro.mirror

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.veyro.VeyroApplication
import com.veyro.core.common.VeyroLog
import com.veyro.core.link.StreamRules

/**
 * The window behind Android's own dialogs for screen sharing (ADR-066). Transparent, started
 * only by Veyro (the request notification, or directly while Veyro is on screen):
 *  - screen mode: Android's audio permission first when the PC wants the phone's sound and it was
 *    never refused for good, then Android's screen-capture dialog (the whole screen on 14+);
 *    the result goes to the foreground service, which Android 14+ requires before the projection;
 *  - audio mode (the PC's Ask again): Android's audio dialog, or Veyro's App info screen once
 *    Android no longer shows it; the answer is checked when the user comes back here.
 * A request that is no longer waiting closes the window at once. Nothing here is logged by name.
 */
public class MirrorConsentActivity : Activity() {

    internal companion object {
        private const val TAG = "MirrorConsent"
        private const val EXTRA_SID = "sid"
        private const val EXTRA_MODE = "mode"
        private const val MODE_SCREEN = "screen"
        private const val MODE_AUDIO = "audio"
        private const val REQUEST_CAPTURE = 1
        private const val REQUEST_AUDIO = 2
        private const val STATE_STEP = "step"
        private const val STATE_LEFT = "leftForAppInfo"

        fun screenIntent(context: Context, sid: String): Intent = intent(context, sid, MODE_SCREEN)

        fun audioIntent(context: Context, sid: String): Intent = intent(context, sid, MODE_AUDIO)

        private fun intent(context: Context, sid: String, mode: String): Intent =
            Intent(context, MirrorConsentActivity::class.java)
                .putExtra(EXTRA_SID, sid)
                .putExtra(EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    /** Where this window is: nothing asked yet, an Android dialog is showing, or App info is open. */
    private enum class Step { START, AUDIO_DIALOG, CAPTURE_DIALOG, APP_INFO, DONE }

    private val coordinator: ScreenShareCoordinator
        get() = (application as VeyroApplication).container.screenShareCoordinator

    private var step = Step.START
    private var leftForAppInfo = false
    private var sid: String? = null
    private var mode: String = MODE_SCREEN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sid = intent.getStringExtra(EXTRA_SID)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SCREEN
        step = savedInstanceState?.getString(STATE_STEP)?.let { runCatching { Step.valueOf(it) }.getOrNull() } ?: Step.START
        leftForAppInfo = savedInstanceState?.getBoolean(STATE_LEFT, false) == true
        if (step != Step.START) return
        when (mode) {
            MODE_AUDIO -> startAudio()
            else -> startScreen()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_STEP, step.name)
        // Recreated while App info is open: the next resume is the user coming back.
        outState.putBoolean(STATE_LEFT, step == Step.APP_INFO)
    }

    override fun onStop() {
        super.onStop()
        if (step == Step.APP_INFO) leftForAppInfo = true
    }

    override fun onResume() {
        super.onResume()
        if (step == Step.APP_INFO && leftForAppInfo) {
            // Back from Veyro's App info: report what the user chose there.
            coordinator.audioAnswered(coordinator.audioState.granted(), showRationale = false)
            coordinator.audioRecheck(this)
            close()
        }
    }

    private fun startScreen() {
        val request = coordinator.pendingRequest(sid) ?: return close()
        val state = coordinator.audioState.current()
        if (request.audio && state != StreamRules.AUDIO_GRANTED && state != StreamRules.AUDIO_BLOCKED) {
            step = Step.AUDIO_DIALOG
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        } else {
            launchCapture()
        }
    }

    private fun startAudio() {
        if (!coordinator.wantsAudio(sid)) return close()
        when {
            coordinator.audioState.granted() -> {
                coordinator.audioRecheck(this)
                close()
            }
            coordinator.audioState.current() == StreamRules.AUDIO_BLOCKED -> {
                step = Step.APP_INFO
                val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                if (runCatching { startActivity(settings) }.isFailure) close()
            }
            else -> {
                step = Step.AUDIO_DIALOG
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_AUDIO) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        coordinator.audioAnswered(granted, shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
        VeyroLog.i(TAG) { "Audio permission answered: granted=$granted" }
        if (mode == MODE_AUDIO) {
            coordinator.audioRecheck(this)
            close()
        } else {
            // A refusal costs only the sound: the screen is asked for either way.
            launchCapture()
        }
    }

    private fun launchCapture() {
        if (coordinator.pendingRequest(sid) == null) return close()
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return decline()
        val capture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The whole screen, as mirroring means; single-app sharing is not offered.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        step = Step.CAPTURE_DIALOG
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(capture, REQUEST_CAPTURE)
        } catch (e: RuntimeException) {
            VeyroLog.w(TAG) { "Capture dialog not shown: ${e.javaClass.simpleName}" }
            decline()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        val id = sid
        if (resultCode == RESULT_OK && data != null && id != null) {
            VeyroLog.i(TAG) { "Screen sharing allowed" }
            coordinator.granted(this, id, resultCode, data)
            close()
        } else {
            VeyroLog.i(TAG) { "Screen sharing declined" }
            decline()
        }
    }

    private fun decline() {
        coordinator.decline(sid)
        close()
    }

    private fun close() {
        step = Step.DONE
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        // Closed before an answer (Back on the transparent window): that is a no.
        if (isFinishing && step == Step.CAPTURE_DIALOG && mode == MODE_SCREEN) coordinator.decline(sid)
        super.onDestroy()
    }
}
