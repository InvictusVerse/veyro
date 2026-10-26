package com.veyro.core.transfer

import java.util.ArrayDeque

/**
 * Calculates live transfer throughput over a rolling time window.
 *
 * Avoids cumulative-average bias where early stalls skew the speed metric forever,
 * while preventing single-sample micro-fluctuations.
 *
 * @param windowMillis Window duration in milliseconds (default: 2000ms = 2s).
 */
public class RollingSpeedCalculator(
    private val windowMillis: Long = 2000L,
) {
    private data class Sample(val timestampMillis: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()
    private val lock = Any()

    /** Records a progress snapshot. */
    public fun record(timestampMillis: Long, bytesTransferred: Long) {
        synchronized(lock) {
            samples.addLast(Sample(timestampMillis, bytesTransferred))
            prune(timestampMillis)
        }
    }

    /**
     * Returns the measured throughput in bytes per second over the rolling window.
     * Returns 0 if there is insufficient duration or if a stall occurs.
     */
    public fun speedBytesPerSecond(nowMillis: Long = System.currentTimeMillis()): Long {
        synchronized(lock) {
            prune(nowMillis)
            if (samples.size < 2) {
                // If only 1 sample or empty, check if we have an initial single point
                return 0L
            }
            val oldest = samples.first
            val newest = samples.last

            val elapsedMillis = newest.timestampMillis - oldest.timestampMillis
            if (elapsedMillis < 100L) {
                return 0L
            }

            // If the latest sample is older than the window, the transfer is stalled
            if (nowMillis - newest.timestampMillis > windowMillis) {
                return 0L
            }

            val byteDelta = (newest.bytes - oldest.bytes).coerceAtLeast(0L)
            return (byteDelta * 1000L) / elapsedMillis
        }
    }

    /** Clears all recorded samples. */
    public fun reset() {
        synchronized(lock) {
            samples.clear()
        }
    }

    /**
     * Estimates remaining time in seconds based on current rolling throughput.
     * Returns null if speed is 0 or remaining bytes is non-positive.
     */
    public fun estimateEtaSeconds(remainingBytes: Long, nowMillis: Long = System.currentTimeMillis()): Long? {
        val speed = speedBytesPerSecond(nowMillis)
        if (speed <= 0L || remainingBytes <= 0L) return null
        return (remainingBytes + speed - 1) / speed
    }

    private fun prune(cutoffMillis: Long) {
        val minTime = cutoffMillis - windowMillis
        while (samples.size > 2 && samples.first.timestampMillis < minTime) {
            samples.removeFirst()
        }
    }
}
