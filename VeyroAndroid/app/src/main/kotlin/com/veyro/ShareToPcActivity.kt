package com.veyro

import android.app.Activity
import android.content.ClipData
import android.content.Intent
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
 *  - Files (`ACTION_SEND` / `ACTION_SEND_MULTIPLE` with streams): handed to [MainActivity], whose
 *    task outlives this one, so the read grant on the URIs lasts for the whole transfer.
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
        app.container.shareTextToPcs(text) { reached ->
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

    private fun forwardFiles(uris: List<Uri>) {
        val clip = ClipData.newRawUri("files", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val forward = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHARE_FILES)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        // The grant travels with the ClipData, so MainActivity can read the URIs too.
        forward.clipData = clip
        startActivity(forward)
    }
}
