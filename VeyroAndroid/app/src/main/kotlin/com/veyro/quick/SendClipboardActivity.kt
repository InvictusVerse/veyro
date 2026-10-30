package com.veyro.quick

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.veyro.VeyroApplication

/**
 * The Send clipboard tile's and widget button's window (ADR-062). Android 10+ lets an app read
 * the clipboard only while one of its windows has focus, so this transparent activity waits for
 * focus, starts the send (which reads the clipboard first), shows a toast and closes. The send runs
 * on the app scope, so closing the window never cuts it short. The clipboard text is never logged.
 */
public class SendClipboardActivity : Activity() {

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated window (rotation) must not send twice.
        started = savedInstanceState?.getBoolean(STATE_STARTED, false) == true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started) return
        started = true
        val app = application as VeyroApplication
        app.container.sendClipboardFromShortcut { message ->
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
            if (!isFinishing) finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_STARTED, started)
    }

    private companion object {
        const val STATE_STARTED = "started"
    }
}
