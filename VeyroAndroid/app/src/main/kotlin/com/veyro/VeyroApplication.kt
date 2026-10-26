package com.veyro

import android.app.Application
import android.content.pm.ApplicationInfo
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
 */
public class VeyroApplication : Application() {

    public val container: VeyroContainer by lazy { VeyroContainer(this) }

    override fun onCreate() {
        super.onCreate()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        container.installLogging(isDebuggable = debuggable)
        container.notificationMirror.start()
    }
}
