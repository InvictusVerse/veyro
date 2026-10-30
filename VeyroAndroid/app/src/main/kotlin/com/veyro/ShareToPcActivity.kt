package com.veyro

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.widget.Toast
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.TrustedPeer
import com.veyro.di.ShareShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Send with Veyro" in the share sheet and the text-selection menu (ADR-043, ADR-054).
 *
 *  - Text or a link (`ACTION_SEND` without a stream, or `ACTION_PROCESS_TEXT`): a PC puts it on
 *    its clipboard and offers Open for a link; another phone shows it as a clipboard notification.
 *  - Files (`ACTION_SEND` / `ACTION_SEND_MULTIPLE` with streams): sent in the background by
 *    [ShareSendService]; Veyro does not open.
 *  - Who it goes to: the device picked in the Direct Share row ("Send to <device>",
 *    [ShareShortcuts]); else, with more than one device connected, a "Send to" chooser with every
 *    connected device and "All connected"; else the one connected (or most recently used) device.
 *
 * The chooser is the only UI; otherwise it finishes at once. Everything in the intent comes from
 * another app and is treated as untrusted (length-capped by the bridge, files validated by the
 * receiver).
 */
public class ShareToPcActivity : Activity() {

    private companion object {
        const val TAG = "ShareToPc"
    }

    /** What was shared, read once from the intent. */
    private sealed interface Shared {
        data class Text(val text: String) : Shared
        data class Files(val uris: List<Uri>) : Shared
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var chooser: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = runCatching { read(intent) }
            .onFailure { VeyroLog.w(TAG) { "Share not read: ${it.javaClass.simpleName}" } }
            .getOrNull()
        if (shared == null) {
            done()
            return
        }
        val picked = ShareShortcuts.targetOf(intent)
        if (picked != null) {
            deliver(shared, listOf(picked), names = emptyList())
            done()
            return
        }
        scope.launch {
            val connected = (application as VeyroApplication).container.connectedShareTargets()
            if (connected.size > 1) {
                choose(shared, connected)
            } else {
                deliver(shared, targets = emptyList(), names = connected.map { it.displayName })
                done()
            }
        }
    }

    /** "Send to": each connected device, then "All connected". Dismissing sends nothing. */
    private fun choose(shared: Shared, connected: List<TrustedPeer>) {
        val labels = connected.map { it.displayName } + "All connected (${connected.size})"
        // A DayNight context, so the dialog follows the system theme over the translucent window.
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_DayNight)
        chooser = AlertDialog.Builder(themed)
            .setTitle("Send to")
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = connected.getOrNull(which)
                if (chosen != null) {
                    deliver(shared, listOf(chosen.deviceId.value), listOf(chosen.displayName))
                } else {
                    deliver(shared, connected.map { it.deviceId.value }, connected.map { it.displayName }, all = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { done() }
            .show()
    }

    /**
     * Sends [shared] to [targets] (empty: the default device). [names] are only for the toast.
     * [all]: every connected device, which for text is one broadcast.
     */
    private fun deliver(shared: Shared, targets: List<String>, names: List<String>, all: Boolean = false) {
        runCatching {
            when (shared) {
                is Shared.Text -> sendText(shared.text, if (all) null else targets.singleOrNull(), names)
                is Shared.Files -> forwardFiles(shared.uris, targets, names)
            }
            targets.forEach { ShareShortcuts.reportUsed(this, it) }
        }.onFailure { VeyroLog.w(TAG) { "Share not handled: ${it.javaClass.simpleName}" } }
    }

    private fun done() {
        if (isFinishing) return
        if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
            // Not replacing the selection: the calling app keeps its text as it was.
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    override fun onDestroy() {
        chooser?.setOnDismissListener(null)
        chooser?.dismiss()
        chooser = null
        scope.cancel()
        super.onDestroy()
    }

    private fun read(intent: Intent?): Shared? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let(Shared::Text)
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val streams = streamsOf(intent)
                if (streams.isNotEmpty()) Shared.Files(streams) else textOf(intent)?.takeIf { it.isNotBlank() }?.let(Shared::Text)
            }
            else -> null
        }
    }

    private fun sendText(text: String, target: String?, names: List<String>) {
        val app = application as VeyroApplication
        app.container.shareTextToPcs(text, target) { reached ->
            val message = when {
                reached == 0 -> "No device connected"
                reached == 1 && names.size == 1 -> "Sent to ${names.single()}"
                reached == 1 -> "Sent"
                else -> "Sent to $reached devices"
            }
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Shared text, with the subject in front when an app sends one (some put the title there). */
    private fun textOf(intent: Intent): String? {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text
        return intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()
    }

    @Suppress("DEPRECATION")
    private fun streamsOf(intent: Intent): List<Uri> {
        val uris = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            else -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                },
            )
        }.orEmpty()
        // content:// only: a file:// URI would read through our own permissions, not the sender's.
        return uris.filter { it.scheme == "content" }
    }

    /**
     * Sends in the background: the user stays in the app they shared from. The read grant goes
     * with the service's start intent, so it lasts for the whole transfer (see [ShareSendService]).
     * Started while this Activity is still visible, which a dataSync service requires.
     */
    private fun forwardFiles(uris: List<Uri>, targets: List<String>, names: List<String>) {
        if (!ShareSendService.start(this, uris, targets)) {
            // Rare (OEM restrictions): send on the app scope; the grant holds for a short send.
            val files = uris.mapNotNull { SharedUris.describe(this, it) }
            (application as VeyroApplication).container.sendFiles(targets, files)
        }
        val what = if (uris.size == 1) "file" else "${uris.size} files"
        val to = when {
            names.size == 1 -> names.single()
            names.size > 1 -> "${names.size} devices"
            else -> "your device"
        }
        Toast.makeText(applicationContext, "Sending $what to $to", Toast.LENGTH_SHORT).show()
    }
}
