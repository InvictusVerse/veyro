package com.veyro.core.common

/**
 * Where log lines go.
 *
 * This exists so that library code never touches `android.util.Log` directly. That is not
 * indirection for its own sake: a direct `android.util.Log` call makes any class that logs
 * impossible to exercise in a plain JVM unit test, because the Android stub throws
 * `RuntimeException: Method i in android.util.Log not mocked`. That would mean either giving up
 * JVM tests for the protocol and crypto layers (which NFR-13 requires) or configuring
 * `unitTests.isReturnDefaultValues`, which silently swallows every Android call in every test and
 * hides real defects.
 *
 * So the rule is: the library defines the sink, the app installs the Android implementation.
 * Until one is installed, logging is a no-op.
 */
public fun interface LogSink {
    public fun log(level: Int, tag: String, message: String, throwable: Throwable?)
}

/** Level constants. Deliberately not `android.util.Log`'s, so this file needs no Android at all. */
public object LogLevel {
    public const val DEBUG: Int = 2
    public const val INFO: Int = 4
    public const val WARN: Int = 5
    public const val ERROR: Int = 6
    public const val NONE: Int = 7
}

/**
 * Logging facade.
 *
 * It exists for one reason beyond indirection: to make the "never log key material" rule
 * (AGENTS.md) auditable by grepping for a single symbol. Keys, seeds, `pairingSecret` and file
 * contents must never be passed to these functions - not even in debug builds, because logcat is
 * readable over ADB and by any privileged process on the device.
 */
public object VeyroLog {

    private const val PREFIX = "Veyro"

    @Volatile
    private var sink: LogSink? = null

    @Volatile
    public var minLevel: Int = LogLevel.DEBUG

    /** Installs the platform sink. Called once from the application, ignored in tests. */
    public fun install(sink: LogSink) {
        this.sink = sink
    }

    /** Removes the sink. Used by tests that want to assert on silence. */
    public fun uninstall() {
        sink = null
    }

    public fun d(tag: String, message: () -> String) {
        if (minLevel <= LogLevel.DEBUG) emit(LogLevel.DEBUG, tag, message())
    }

    public fun i(tag: String, message: () -> String) {
        if (minLevel <= LogLevel.INFO) emit(LogLevel.INFO, tag, message())
    }

    public fun w(tag: String, throwable: Throwable? = null, message: () -> String) {
        if (minLevel <= LogLevel.WARN) emit(LogLevel.WARN, tag, message(), throwable)
    }

    public fun e(tag: String, throwable: Throwable? = null, message: () -> String) {
        if (minLevel <= LogLevel.ERROR) emit(LogLevel.ERROR, tag, message(), throwable)
    }

    private fun emit(level: Int, tag: String, message: String, throwable: Throwable? = null) {
        // No sink installed (unit tests, or before Application.onCreate): stay silent rather than
        // fail. A missing log line must never be the reason something breaks.
        sink?.log(level, "$PREFIX.$tag", message, throwable)
    }
}
