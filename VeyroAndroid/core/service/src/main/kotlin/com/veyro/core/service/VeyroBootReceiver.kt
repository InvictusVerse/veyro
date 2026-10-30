package com.veyro.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the background connection after boot, only when the user turned on both "Run in
 * background" and "Start on boot" (off by default) and a PC is paired; the host decides
 * (ADR-036). `connectedDevice` services may start from `BOOT_COMPLETED` on Android 15, which
 * `dataSync` ones may not.
 *
 * ADR-061: also after Veyro itself is updated (`MY_PACKAGE_REPLACED`), with the same rules, since
 * an update kills the running service and nothing else would bring it back until the app is
 * opened. Both broadcasts are exempt from the background foreground-service start limits.
 */
public class VeyroBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val host = BackgroundHost.from(context) ?: return
        val pending = goAsync()
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            host.onAppUpdated(context.applicationContext) { pending.finish() }
        } else {
            host.onBoot(context.applicationContext) { pending.finish() }
        }
    }
}
