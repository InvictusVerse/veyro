package com.veyro

import android.app.Activity
import android.content.Intent
import com.veyro.di.ShareShortcuts
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.veyro.core.common.VeyroLog

/**
 * "Send to PC" in the share sheet and the text-selection menu (ADR-043).
 *
 *  - Text or a link (`ACTION_SEND` without a stream, or `ACTION_PROCESS_TEXT`): sent to every
 *    connected PC, which puts it on its clipboard and offers Open for a link.
 *  - Files (`ACTION_SEND` / `ACTION_SEND_MULTIPLE` with streams): sent in the background by
 *    [ShareSendService]; Veyro does not open.
 *  - Picked from the Direct Share row ("Send to <PC>", [ShareShortcuts]): to that PC only.
 *
 * No UI and no layout: it finishes in [onCreate]. Everything in the intent comes from another app
 * and is treated as untrusted (length-capped by the bridge, files validated by the receiver).
 */
public class ShareToPcActivity : Activity() {

    private companion object {
        const val TAG = "ShareToPc"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { handle(intent) }
            .onFailure { VeyroLog.w(TAG) { "Share not handled: ${it.javaClass.simpleName}" } }
        if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
            // Not replacing the selection: the calling app keeps its text as it was.
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT ->
                sendText(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val streams = streamsOf(intent)
                if (streams.isNotEmpty()) forwardFiles(streams) else sendText(textOf(intent))
            }
        }
    }

    private fun sendText(text: String?) {
        if (text.isNullOrBlank()) return
        val app = application as VeyroApplication
        val target = ShareShortcuts.targetOf(intent)
        target?.let { ShareShortcuts.reportUsed(this, it) }
        app.container.shareTextToPcs(text, target) { reached ->
            val message = if (reached > 0) "Sent to PC" else "No PC connected"
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
     */
    private fun forwardFiles(uris: List<Uri>) {
        val target = ShareShortcuts.targetOf(intent)
        ShareSendService.start(this, uris, target)
        target?.let { ShareShortcuts.reportUsed(this, it) }
        val what = if (uris.size == 1) "file" else "${uris.size} files"
        Toast.makeText(applicationContext, "Sending $what to your PC", Toast.LENGTH_SHORT).show()
    }
}
