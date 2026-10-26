package com.veyro.core.link

import com.veyro.core.common.VeyroLog
import com.veyro.core.model.TransferRoute
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * High-precision monotonic throughput instrumentation for raw TCP transfers.
 *
 * Records nanoTime intervals and actual payload byte counts across:
 * - Source file disk reads
 * - TCP socket payload writes
 * - Receiver TCP payload reads
 * - Receiver temporary part-file disk writes
 * - SHA-256 integrity verification
 * - MediaStore / storage publication
 *
 * Produces structured diagnostic logs with effective MB/s breakdowns.
 */
public class TransferThroughputMetrics(
    public val transferId: String,
    public val role: String, // "SENDER" or "RECEIVER"
    public val route: TransferRoute,
    public val chunkSize: Int,
    public val destinationStorageType: String = "APP_INTERNAL",
) {
    private companion object {
        private const val TAG = "TransferThroughput"
        private const val NANOS_PER_SEC = 1_000_000_000.0
        private const val BYTES_PER_MB = 1024.0 * 1024.0
    }

    private val overallStartNanos = System.nanoTime()
    private var transferEndNanos: Long = 0L

    // Byte counts
    public val sourceBytesRead: AtomicLong = AtomicLong(0L)
    public val tcpBytesSubmitted: AtomicLong = AtomicLong(0L)
    public val tcpBytesRead: AtomicLong = AtomicLong(0L)
    public val diskBytesWritten: AtomicLong = AtomicLong(0L)

    // Elapsed cumulative nanoseconds
    public val sourceReadDurationNanos: AtomicLong = AtomicLong(0L)
    public val socketWriteDurationNanos: AtomicLong = AtomicLong(0L)
    public val tcpReadDurationNanos: AtomicLong = AtomicLong(0L)
    public val diskWriteDurationNanos: AtomicLong = AtomicLong(0L)
    public val sha256DurationNanos: AtomicLong = AtomicLong(0L)
    public val publishDurationNanos: AtomicLong = AtomicLong(0L)

    // Chunk and buffer counts
    public val chunkCount: AtomicInteger = AtomicInteger(0)
    public val flushCount: AtomicInteger = AtomicInteger(0)
    public var sendBufferSize: Int = -1
    public var receiveBufferSize: Int = -1

    public inline fun <T> recordSourceRead(bytes: Long, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            val dt = System.nanoTime() - t0
            sourceReadDurationNanos.addAndGet(dt)
            sourceBytesRead.addAndGet(bytes)
        }
    }

    public inline fun <T> recordSocketWrite(bytes: Long, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            val dt = System.nanoTime() - t0
            socketWriteDurationNanos.addAndGet(dt)
            tcpBytesSubmitted.addAndGet(bytes)
            chunkCount.incrementAndGet()
        }
    }

    public inline fun <T> recordTcpRead(bytes: Long, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            val dt = System.nanoTime() - t0
            tcpReadDurationNanos.addAndGet(dt)
            tcpBytesRead.addAndGet(bytes)
            chunkCount.incrementAndGet()
        }
    }

    public fun recordTcpReadDuration(nanos: Long, bytes: Long) {
        tcpReadDurationNanos.addAndGet(nanos)
        tcpBytesRead.addAndGet(bytes)
        chunkCount.incrementAndGet()
    }

    public fun recordFlush() {
        flushCount.incrementAndGet()
    }

    public inline fun <T> recordDiskWrite(bytes: Long, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            val dt = System.nanoTime() - t0
            diskWriteDurationNanos.addAndGet(dt)
            diskBytesWritten.addAndGet(bytes)
        }
    }

    public inline fun <T> recordSha256(block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            sha256DurationNanos.addAndGet(System.nanoTime() - t0)
        }
    }

    public inline fun <T> recordPublish(block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            publishDurationNanos.addAndGet(System.nanoTime() - t0)
        }
    }

    public fun markTransferComplete() {
        if (transferEndNanos == 0L) {
            transferEndNanos = System.nanoTime()
        }
    }

    public fun effectiveThroughputMbPerSec(): Double {
        val endNanos = if (transferEndNanos != 0L) transferEndNanos else System.nanoTime()
        val totalElapsedSec = (endNanos - overallStartNanos) / NANOS_PER_SEC
        val payloadBytes = if (role == "SENDER") tcpBytesSubmitted.get() else tcpBytesRead.get()
        val totalMb = payloadBytes / BYTES_PER_MB
        return if (totalElapsedSec > 0.0) totalMb / totalElapsedSec else 0.0
    }

    public fun logBreakdown() {
        val endNanos = if (transferEndNanos != 0L) transferEndNanos else System.nanoTime()
        val totalElapsedSec = (endNanos - overallStartNanos) / NANOS_PER_SEC
        val payloadBytes = if (role == "SENDER") tcpBytesSubmitted.get() else tcpBytesRead.get()
        val totalMb = payloadBytes / BYTES_PER_MB
        val effectiveSpeedMBps = if (totalElapsedSec > 0.0) totalMb / totalElapsedSec else 0.0

        val sourceReadSec = sourceReadDurationNanos.get() / NANOS_PER_SEC
        val socketWriteSec = socketWriteDurationNanos.get() / NANOS_PER_SEC
        val tcpReadSec = tcpReadDurationNanos.get() / NANOS_PER_SEC
        val diskWriteSec = diskWriteDurationNanos.get() / NANOS_PER_SEC
        val sha256Sec = sha256DurationNanos.get() / NANOS_PER_SEC
        val publishSec = publishDurationNanos.get() / NANOS_PER_SEC

        val socketWriteSpeedMBps = if (socketWriteSec > 0.0) totalMb / socketWriteSec else 0.0
        val diskWriteSpeedMBps = if (diskWriteSec > 0.0) totalMb / diskWriteSec else 0.0

        VeyroLog.i(TAG) {
            buildString {
                appendLine("[$transferId] ── THROUGHPUT BREAKDOWN ($role) ──")
                appendLine("  Route: $route | ChunkSize: ${chunkSize / 1024} KiB | Chunks: ${chunkCount.get()} | Flushes: ${flushCount.get()}")
                appendLine("  Storage: $destinationStorageType | Buffers: SND=${sendBufferSize}B RCV=${receiveBufferSize}B")
                appendLine("  Payload: ${String.format(Locale.US, "%.2f", totalMb)} MB ($payloadBytes bytes) in ${String.format(Locale.US, "%.3f", totalElapsedSec)}s")
                appendLine("  Effective Speed: ${String.format(Locale.US, "%.2f", effectiveSpeedMBps)} MB/s")
                if (role == "SENDER") {
                    appendLine("  ├─ Source Read:    ${String.format(Locale.US, "%.3f", sourceReadSec)}s (${String.format(Locale.US, "%.2f", if (sourceReadSec > 0) totalMb / sourceReadSec else 0.0)} MB/s)")
                    appendLine("  ├─ Socket Write:   ${String.format(Locale.US, "%.3f", socketWriteSec)}s (${String.format(Locale.US, "%.2f", socketWriteSpeedMBps)} MB/s)")
                } else {
                    appendLine("  ├─ TCP Socket Read:${String.format(Locale.US, "%.3f", tcpReadSec)}s (${String.format(Locale.US, "%.2f", if (tcpReadSec > 0) totalMb / tcpReadSec else 0.0)} MB/s)")
                    appendLine("  ├─ PartFile Write: ${String.format(Locale.US, "%.3f", diskWriteSec)}s (${String.format(Locale.US, "%.2f", diskWriteSpeedMBps)} MB/s)")
                }
                appendLine("  ├─ SHA-256 Digest: ${String.format(Locale.US, "%.3f", sha256Sec)}s")
                if (publishSec > 0.0) {
                    appendLine("  └─ MediaStore Pub: ${String.format(Locale.US, "%.3f", publishSec)}s")
                }
            }
        }
    }
}
