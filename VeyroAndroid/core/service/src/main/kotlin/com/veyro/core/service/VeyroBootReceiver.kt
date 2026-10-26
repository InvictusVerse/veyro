package com.veyro.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the background connection after boot, only when the user turned on both "Run in
 * background" and "Start on boot" (off by default) and a PC is paired; the host decides
 * (ADR-036). `connectedDevice` services may start from `BOOT_COMPLETED` on Android 15, which
 * `dataSync` ones may not.
 */
public class VeyroBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val host = BackgroundHost.from(context) ?: return
        val pending = goAsync()
        host.onBoot(context.applicationContext) { pending.finish() }
    }
}
