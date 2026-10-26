package com.veyro.core.common

import java.util.ArrayDeque

/**
 * A [LogSink] that keeps the most recent lines in memory so a debug screen can show them without
 * `adb`.
 *
 * Why this exists: most of what goes wrong in this app goes wrong on a phone that is not plugged
 * into a laptop - a hotspot that silently blocks multicast, a transfer that stalls on a different
 * subnet. Being able to read the last few hundred log lines from inside the app turns "it did not
 * work" into an actionable report.
 *
 * It is bounded and non-blocking. It never holds more than [capacity] lines, so it cannot grow
 * without limit during a long transfer, and it deliberately stores no throwable stack traces
 * beyond their message - a ring buffer of stack traces is a memory leak with extra steps.
 *
 * Install it alongside the platform sink with [CompositeLogSink].
 */
public class RecordingLogSink(
    private val capacity: Int = 400,
    private val clock: () -> Long = System::currentTimeMillis,
) : LogSink {

    public data class Entry(
        val timestampMillis: Long,
        val level: Int,
        val tag: String,
        val message: String,
        val throwableSummary: String?,
    ) {
        public val levelChar: Char
            get() = when (level) {
                LogLevel.DEBUG -> 'D'
                LogLevel.INFO -> 'I'
                LogLevel.WARN -> 'W'
                LogLevel.ERROR -> 'E'
                else -> 'V'
            }
    }

    private val buffer = ArrayDeque<Entry>(capacity)
    private val lock = Any()

    override fun log(level: Int, tag: String, message: String, throwable: Throwable?) {
        val entry = Entry(
            timestampMillis = clock(),
            level = level,
            tag = tag,
            message = message,
            // Message only, not the stack trace: see the class note on memory.
            throwableSummary = throwable?.let { "${it::class.java.simpleName}: ${it.message}" },
        )
        synchronized(lock) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(entry)
        }
    }

    /** Oldest first. */
    public fun entries(): List<Entry> = synchronized(lock) { buffer.toList() }

    /** Oldest first, filtered to [minLevel] and above. */
    public fun entries(minLevel: Int): List<Entry> =
        synchronized(lock) { buffer.filter { it.level >= minLevel } }

    public fun clear(): Unit = synchronized(lock) { buffer.clear() }

    /** A plain-text dump suitable for the clipboard or a bug report. */
    public fun dump(minLevel: Int = LogLevel.DEBUG): String =
        entries(minLevel).joinToString("\n") { e ->
            buildString {
                append(e.levelChar).append('/').append(e.tag).append(": ").append(e.message)
                e.throwableSummary?.let { append("  <- ").append(it) }
            }
        }
}

/** Fans one log line out to several sinks, e.g. logcat plus the in-memory recorder. */
public class CompositeLogSink(private vararg val sinks: LogSink) : LogSink {
    override fun log(level: Int, tag: String, message: String, throwable: Throwable?) {
        for (sink in sinks) sink.log(level, tag, message, throwable)
    }
}