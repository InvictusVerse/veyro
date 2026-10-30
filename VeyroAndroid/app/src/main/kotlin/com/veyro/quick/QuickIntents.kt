package com.veyro.quick

import android.content.Context
import android.content.Intent
import com.veyro.MainActivity

/** The intents the tiles and the widget start (ADR-062). Explicit, to Veyro's own components only. */
internal object QuickIntents {

    fun openApp(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Home's Send files: the system picker, then straight to [deviceId] (or the selected device). */
    fun sendFiles(context: Context, deviceId: String?): Intent =
        openApp(context).apply {
            action = MainActivity.ACTION_SEND_FILES
            if (deviceId != null) putExtra(MainActivity.EXTRA_SEND_TO, deviceId)
        }

    fun sendClipboard(context: Context): Intent =
        Intent(context, SendClipboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
}
