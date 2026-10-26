package com.veyro.core.common

import android.util.Log

/**
 * The Android [LogSink], installed once from `VeyroApplication.onCreate`.
 *
 * This is the only file in the project that may import `android.util.Log`. Everything else logs
 * through [VeyroLog], which is what makes the protocol and crypto modules testable on a plain JVM
 * (NFR-13).
 */
public object AndroidLogSink : LogSink {

    override fun log(level: Int, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
            else -> Log.v(tag, message, throwable)
        }
    }

    /** Maps the Android build type onto a level, so release builds stay quiet. */
    public fun installFor(isDebuggable: Boolean) {
        VeyroLog.minLevel = if (isDebuggable) LogLevel.DEBUG else LogLevel.WARN
        VeyroLog.install(this)
    }
}
