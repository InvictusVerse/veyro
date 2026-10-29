package com.veyro.core.transfer

import com.veyro.core.common.hex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

/** ADR-049: the running hash equals the hash of the stored bytes, whatever order chunks came in. */
class RunningHashTest {

    private val chunk = 1000
    private val size = 4_500L // five chunks, the last one short
    private val data = Random(7).nextBytes(size.toInt())
    private val expected = MessageDigest.getInstance("SHA-256").digest(data).hex()

    /** Storage the hash can read back from, and a count of the bytes it had to read. */
    private class Store(size: Int) {
        val bytes = ByteArray(size)
        var readBack = 0L
        fun read(position: Long, buffer: ByteArray, length: Int): Int {
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, 0, position.toInt(), position.toInt() + n)
            readBack += n
            return n
        }
    }

    private fun chunkOf(source: ByteArray, index: Int): ByteArray =
        source.copyOfRange(index * chunk, minOf(source.size, (index + 1) * chunk))

    private fun write(hash: RunningHash, store: Store, index: Int, bytes: ByteArray) {
        bytes.copyInto(store.bytes, index * chunk)
        hash.onWritten(index, bytes)
    }

    @Test
    fun `in order needs no read back`() {
        val store = Store(size.toInt())
        val hash = RunningHash(size, chunk)
        for (i in 0 until 5) write(hash, store, i, chunkOf(data, i))
        assertEquals(expected, hash.finish(store::read))
        assertEquals(0L, store.readBack)
    }

    @Test
    fun `a gap reads back only from the gap on`() {
        val store = Store(size.toInt())
        val hash = RunningHash(size, chunk)
        for (i in listOf(0, 1, 3, 2, 4)) write(hash, store, i, chunkOf(data, i))
        assertEquals(expected, hash.finish(store::read))
        assertEquals(size - 3 * chunk, store.readBack)
    }

    @Test
    fun `a chunk rewritten after hashing reads everything back and hashes what is stored`() {
        val store = Store(size.toInt())
        val hash = RunningHash(size, chunk)
        val wrong = ByteArray(chunk) { 1 }
        write(hash, store, 0, wrong)
        for (i in 1 until 5) write(hash, store, i, chunkOf(data, i))
        write(hash, store, 0, chunkOf(data, 0)) // corrected after it was already hashed
        assertEquals(expected, hash.finish(store::read))
        assertEquals(size, store.readBack)
    }

    @Test
    fun `a second finish reads everything back, and an empty item works`() {
        val store = Store(size.toInt())
        val hash = RunningHash(size, chunk)
        for (i in 0 until 5) write(hash, store, i, chunkOf(data, i))
        hash.finish(store::read)
        assertEquals(expected, hash.finish(store::read))

        val empty = RunningHash(0L, chunk)
        empty.onWritten(0, ByteArray(0))
        assertEquals(MessageDigest.getInstance("SHA-256").digest(ByteArray(0)).hex(), empty.finish { _, _, _ -> 0 })
    }
}
