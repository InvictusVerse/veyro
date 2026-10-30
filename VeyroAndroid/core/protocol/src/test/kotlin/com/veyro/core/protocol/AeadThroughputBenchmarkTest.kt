package com.veyro.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * NFR-3 (ADR-058): ChaCha20-Poly1305 seal and open throughput through [AeadStreamFramer] at the
 * 512 KiB and 4 MiB frame sizes, in MB/s, printed as `VEYRO-BENCH` lines. No pass/fail threshold:
 * the numbers depend on the machine, and JVM numbers do not represent Conscrypt on a phone.
 */
class AeadThroughputBenchmarkTest {
    private fun bench(frameSize: Int, totalBytes: Long) {
        val key = ByteArray(32) { it.toByte() }
        val aad = ByteArray(64) { 7 }
        val plain = ByteArray(frameSize) { (it * 31).toByte() }
        val sealed = ByteArray(frameSize + ChaChaPoly.TAG_SIZE)
        val opened = ByteArray(frameSize)
        val frames = (totalBytes / frameSize).toInt()

        // Warm-up so the JIT compiles the cipher before the timed loop.
        run {
            val s = AeadStreamFramer(key, 1, AeadStreamFramer.Role.SEAL)
            val o = AeadStreamFramer(key, 1, AeadStreamFramer.Role.OPEN)
            repeat(8) {
                s.seal(aad, aad.size, plain, 0, plain.size, sealed, 0)
                o.open(aad, aad.size, sealed, 0, sealed.size, opened, 0)
            }
        }

        val sealer = AeadStreamFramer(key, 1, AeadStreamFramer.Role.SEAL)
        val opener = AeadStreamFramer(key, 1, AeadStreamFramer.Role.OPEN)
        var sealNs = 0L
        var openNs = 0L
        repeat(frames) {
            val t0 = System.nanoTime()
            val n = sealer.seal(aad, aad.size, plain, 0, plain.size, sealed, 0)
            val t1 = System.nanoTime()
            assertEquals(sealed.size, n)
            opener.open(aad, aad.size, sealed, 0, sealed.size, opened, 0)
            val t2 = System.nanoTime()
            sealNs += t1 - t0
            openNs += t2 - t1
        }
        assertArrayEquals(plain, opened)
        val mb = frames.toDouble() * frameSize / (1024.0 * 1024.0)
        println(
            String.format(
                Locale.US, "VEYRO-BENCH jvm aead frame=%dKiB seal=%.1f MB/s open=%.1f MB/s",
                frameSize / 1024, mb / (sealNs / 1e9), mb / (openNs / 1e9),
            ),
        )
    }

    @Test
    fun `seal and open throughput at 512 KiB frames`() = bench(512 * 1024, 256L * 1024 * 1024)

    @Test
    fun `seal and open throughput at 4 MiB frames`() = bench(4 * 1024 * 1024, 256L * 1024 * 1024)
}
