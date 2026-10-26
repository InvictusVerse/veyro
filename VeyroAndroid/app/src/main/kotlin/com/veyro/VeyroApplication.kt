package com.veyro

import android.app.Application
import android.content.pm.ApplicationInfo
import com.veyro.di.VeyroContainer

/**
 * Process entry point.
 *
 * Deliberately almost empty. Nothing is started here: no sockets, no discovery, no service, no
 * background work. Veyro''s idle cost must be zero (NFR-01/NFR-02/NFR-03), and the surest way to
 * guarantee that is for `onCreate` to do nothing that can send a packet or hold a lock.
 *
 * The one thing it does is install logging, because library code must never call `android.util.Log`
 * itself if the protocol and crypto layers are to stay JVM-testable (NFR-13).
 */
public class VeyroApplication : Application() {

    public val container: VeyroContainer by lazy { VeyroContainer(this) }

    override fun onCreate() {
        super.onCreate()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        container.installLogging(isDebuggable = debuggable)
    }
}