package com.veyro.core.link

import com.veyro.core.transfer.ChunkMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** ADR-064: a folder's files are read straight from their streams while they are sent. */
class SendSourceTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val bytes = ByteArray(10_000) { (it % 251).toByte() }

    private class CountingOpener(private val data: ByteArray) {
        var opens = 0
        var closes = 0
        fun open(): InputStream {
            opens++
            return object : ByteArrayInputStream(data) {
                override fun close() {
                    closes++
                    super.close()
                }
            }
        }
    }

    @Test
    fun `chunks read in order come from one stream`() {
        val opener = CountingOpener(bytes)
        val source = SendSource.ofStream(bytes.size.toLong(), opener::open)
        val out = ByteArray(bytes.size)
        source.use {
            for (offset in 0 until bytes.size step 4096) {
                val chunk = ByteArray(minOf(4096, bytes.size - offset))
                it.read(offset.toLong(), chunk)
                chunk.copyInto(out, offset)
            }
        }
        assertArrayEquals(bytes, out)
        assertEquals(1, opener.opens)
        assertEquals(1, opener.closes)
    }

    @Test
    fun `a resumed send skips forward and hashes the whole file`() {
        val opener = CountingOpener(bytes)
        val source = SendSource.ofStream(bytes.size.toLong(), opener::open)
        val tail = ByteArray(bytes.size - 8192)
        source.read(8192, tail)
        assertArrayEquals(bytes.copyOfRange(8192, bytes.size), tail)
        // Going back opens the stream again.
        val head = ByteArray(10)
        source.read(0, head)
        assertArrayEquals(bytes.copyOf(10), head)
        source.close()
        val file = tempDir.newFile().apply { writeBytes(bytes) }
        assertEquals(ChunkMath.hashFile(file), source.sha256())
    }

    @Test
    fun `a file that grew since it was listed fails instead of arriving cut short`() {
        val source = SendSource.ofStream(5, { ByteArrayInputStream(bytes) })
        assertThrows(IOException::class.java) { source.read(0, ByteArray(5)) }
        assertThrows(IOException::class.java) { source.sha256() }
    }

    @Test
    fun `a file that shrank since it was listed fails`() {
        val source = SendSource.ofStream(bytes.size + 1L, { ByteArrayInputStream(bytes) })
        assertThrows(IOException::class.java) { source.read(0, ByteArray(bytes.size + 1)) }
    }

    @Test
    fun `an empty file reads as empty`() {
        val source = SendSource.ofStream(0, { ByteArrayInputStream(ByteArray(0)) })
        source.read(0, ByteArray(0))
        assertEquals(ChunkMath.hashFile(tempDir.newFile()), source.sha256())
    }

    @Test
    fun `a staged file is read by position and a missing one has no source`() {
        val file = tempDir.newFile().apply { writeBytes(bytes) }
        val source = SendSource.of(file)!!
        val middle = ByteArray(100)
        source.use { it.read(5000, middle) }
        assertArrayEquals(bytes.copyOfRange(5000, 5100), middle)
        assertEquals(bytes.size.toLong(), source.length)
        assertNull(SendSource.of(java.io.File(tempDir.root, "missing.bin")))
    }
}
