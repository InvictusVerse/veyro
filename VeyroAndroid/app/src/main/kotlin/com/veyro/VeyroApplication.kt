package com.veyro

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.veyro.core.service.BackgroundHost
import com.veyro.di.VeyroContainer

/**
 * Process entry point.
 *
 * Deliberately almost empty. Nothing is started here that can send a packet or hold a lock:
 * no sockets, no discovery, no service. Veyro's idle cost must be zero (NFR-01/NFR-02/NFR-03).
 *
 * It installs logging, because library code must never call `android.util.Log` itself if the
 * protocol and crypto layers are to stay JVM-testable (NFR-13). It also starts the notification
 * mirror's observer, which only waits on the setting: with mirroring off (the default) it never
 * reaches the connection layer, and with it on it only uses a session something else opened
 * (ADR-025).
 *
 * Background running (ADR-036) starts from the first visible Activity, from boot, or from the
 * service's own sticky restart, never from here: a process started for the notification listener
 * must not try to start a foreground service, which Android 12+ refuses from the background.
 */
public class VeyroApplication : Application(), BackgroundHost.Provider {

    public val container: VeyroContainer by lazy { VeyroContainer(this) }

    override val backgroundHost: BackgroundHost get() = container.backgroundController

    override fun onCreate() {
        super.onCreate()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        container.installLogging(isDebuggable = debuggable)
        container.notificationMirror.start()
        // ADR-026. Both only follow a connection something else opened; neither builds the
        // connection layer itself, and phone media is shared only after an explicit opt-in.
        container.mediaMirror.start()
        container.deviceStatusPublisher.start()
        // ADR-027. Same rule: they follow a session something else opened, and serve nothing
        // until "Messages and calls on PC" is switched on.
        container.smsBridge.start()
        container.callBridge.start()
        // ADR-034. Same rule, behind "Photos on PC".
        container.photoBridge.start()
        // ADR-038. Same rule: wallpapers only travel over a session something else opened.
        container.wallpaperBridge.start()
        // ADR-043. Same rule: clips only travel over a session something else opened.
        container.clipboardBridge.start()
        // ADR-043: "Send to <PC>" in the share sheet's top row.
        container.startShareShortcuts()
        // ADR-044: start reading "Clear notifications automatically" now, so the first notification has it.
        container.notificationAutoClearMs
        // ADR-044: received files live in one Download/Veyro folder; bring older ones in once.
        container.moveOlderReceivedFilesOnce()
        registerActivityLifecycleCallbacks(VisibilityTracker())
    }

    /** Counts started Activities, so the offer notification knows when the prompt cannot be seen. */
    private inner class VisibilityTracker : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started++
            if (started == 1) {
                container.appVisible.value = true
                container.backgroundController.onAppVisible()
                container.offerNotifier.start()
                container.transferNotifier.start()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) container.appVisible.value = false
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
