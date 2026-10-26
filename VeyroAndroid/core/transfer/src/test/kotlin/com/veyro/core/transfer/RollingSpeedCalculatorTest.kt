package com.veyro.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingSpeedCalculatorTest {

    @Test
    fun `returns zero when no samples recorded`() {
        val calc = RollingSpeedCalculator(windowMillis = 2000L)
        assertEquals(0L, calc.speedBytesPerSecond())
        assertNull(calc.estimateEtaSeconds(10_000L))
    }

    @Test
    fun `returns zero when insufficient time has elapsed`() {
        val calc = RollingSpeedCalculator(windowMillis = 2000L)
        calc.record(timestampMillis = 1000L, bytesTransferred = 100L)
        calc.record(timestampMillis = 1050L, bytesTransferred = 200L) // 50ms < 100ms
        assertEquals(0L, calc.speedBytesPerSecond(nowMillis = 1050L))
    }

    @Test
    fun `accurately measures speed over rolling window`() {
        val calc = RollingSpeedCalculator(windowMillis = 2000L)
        calc.record(timestampMillis = 1000L, bytesTransferred = 0L)
        calc.record(timestampMillis = 2000L, bytesTransferred = 1_000_000L) // 1MB in 1s = 1,000,000 B/s

        val speed = calc.speedBytesPerSecond(nowMillis = 2000L)
        assertEquals(1_000_000L, speed)

        // Estimated ETA for 3,000,000 bytes remaining should be exactly 3 seconds
        val eta = calc.estimateEtaSeconds(remainingBytes = 3_000_000L, nowMillis = 2000L)
        assertEquals(3L, eta)
    }

    @Test
    fun `prunes old samples outside rolling window`() {
        val calc = RollingSpeedCalculator(windowMillis = 2000L)
        calc.record(timestampMillis = 1000L, bytesTransferred = 0L)
        calc.record(timestampMillis = 2000L, bytesTransferred = 1_000_000L)
        calc.record(timestampMillis = 3500L, bytesTransferred = 4_000_000L)

        // At t=3500, oldest sample inside window is at t=2000 (delta: 1500ms, bytes: 3,000,000)
        // 3,000,000 / 1.5s = 2,000,000 B/s
        val speed = calc.speedBytesPerSecond(nowMillis = 3500L)
        assertEquals(2_000_000L, speed)
    }

    @Test
    fun `truthfully detects stalled connection`() {
        val calc = RollingSpeedCalculator(windowMillis = 2000L)
        calc.record(timestampMillis = 1000L, bytesTransferred = 0L)
        calc.record(timestampMillis = 2000L, bytesTransferred = 1_000_000L)

        // Now t=5000 (3s after latest sample, exceeds 2s window)
        val speed = calc.speedBytesPerSecond(nowMillis = 5000L)
        assertEquals(0L, speed)
        assertNull(calc.estimateEtaSeconds(remainingBytes = 1_000_000L, nowMillis = 5000L))
    }

    @Test
    fun `reset clears all recorded samples`() {
        val calc = RollingSpeedCalculator(windowMillis = 2000L)
        calc.record(timestampMillis = 1000L, bytesTransferred = 0L)
        calc.record(timestampMillis = 2000L, bytesTransferred = 1_000_000L)
        calc.reset()
        assertEquals(0L, calc.speedBytesPerSecond(nowMillis = 2000L))
    }
}
