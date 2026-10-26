package com.veyro.core.transfer

import com.veyro.core.model.TransferItem
import com.veyro.core.model.VerificationResult
import com.veyro.core.protocol.RelativePath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Chunk arithmetic, the resume bitmap, and the part-file lifecycle.
 *
 * The emphasis is on the things that are wrong silently: 32-bit offset overflow on large files,
 * padding bits making an incomplete transfer look complete, and a part file being published before
 * its hash is checked.
 */
class ChunkMathTest {

    @Test
    fun `chunk count rounds up`() {
        assertEquals(1, ChunkMath.chunkCount(1, 1024))
        assertEquals(1, ChunkMath.chunkCount(1024, 1024))
        assertEquals(2, ChunkMath.chunkCount(1025, 1024))
        assertEquals(4, ChunkMath.chunkCount(4096, 1024))
        assertEquals(5, ChunkMath.chunkCount(4097, 1024))
    }

    @Test
    fun `an empty file still has one chunk`() {
        // Otherwise a zero-byte file would have nothing to send and would never complete.
        assertEquals(1, ChunkMath.chunkCount(0, 1024))
        assertEquals(0, ChunkMath.lengthOf(0, 0, 1024))
    }

    @Test
    fun `offsets use 64-bit arithmetic`() {
        // The large-file bug: chunkIndex * chunkSize in Int arithmetic wraps past 2 GB, so a 50 GB
        // transfer (NFR-07) would silently write chunks at negative offsets.
        val chunkSize = 4 * 1024 * 1024
        val index = 2000 // 2000 * 4 MiB = 8 GB, far past Int.MAX_VALUE
        val offset = ChunkMath.offsetOf(index, chunkSize)
        assertEquals(8_388_608_000L, offset)
        assertTrue("offset must not overflow", offset > Int.MAX_VALUE)
    }

    @Test
    fun `a 50 GB file produces sane chunk arithmetic`() {
        val size = 50L * 1024 * 1024 * 1024
        val chunkSize = 4 * 1024 * 1024
        val count = ChunkMath.chunkCount(size, chunkSize)
        assertEquals(12800, count)
        // The last chunk must end exactly at the file size.
        val last = count - 1
        assertEquals(size, ChunkMath.offsetOf(last, chunkSize) + ChunkMath.lengthOf(last, size, chunkSize))
    }

    @Test
    fun `the final chunk is short`() {
        assertEquals(1024, ChunkMath.lengthOf(0, 2500, 1024))
        assertEquals(1024, ChunkMath.lengthOf(1, 2500, 1024))
        assertEquals(452, ChunkMath.lengthOf(2, 2500, 1024))
        assertEquals(0, ChunkMath.lengthOf(3, 2500, 1024))
    }

    @Test
    fun `chunk lengths sum to the file size`() {
        for (size in longArrayOf(0, 1, 1023, 1024, 1025, 999_999)) {
            val chunkSize = 1024
            val count = ChunkMath.chunkCount(size, chunkSize)
            val sum = (0 until count).sumOf { ChunkMath.lengthOf(it, size, chunkSize).toLong() }
            assertEquals("size $size", size, sum)
        }
    }

    @Test
    fun `invalid inputs are rejected`() {
        for (block in listOf<() -> Unit>(
            { ChunkMath.chunkCount(10, 0) },
            { ChunkMath.chunkCount(-1, 1024) },
            { ChunkMath.offsetOf(-1, 1024) },
        )) {
            var threw = false
            try { block() } catch (e: IllegalArgumentException) { threw = true }
            assertTrue(threw)
        }
    }
}

class ChunkBitmapTest {

    @Test
    fun `bits set and clear`() {
        val map = ChunkBitmap(20)
        assertFalse(map[5])
        map[5] = true
        assertTrue(map[5])
        map[5] = false
        assertFalse(map[5])
    }

    @Test
    fun `counting and completeness`() {
        val map = ChunkBitmap(10)
        assertEquals(0, map.countSet())
        assertFalse(map.isComplete())
        for (i in 0 until 10) map[i] = true
        assertEquals(10, map.countSet())
        assertTrue(map.isComplete())
    }

    @Test
    fun `missing lists the gaps in order`() {
        val map = ChunkBitmap(8)
        map[0] = true
        map[3] = true
        map[7] = true
        assertEquals(listOf(1, 2, 4, 5, 6), map.missing())
    }

    @Test
    fun `out of range reads are false and writes are rejected`() {
        val map = ChunkBitmap(8)
        assertFalse(map[-1])
        assertFalse(map[8])
        var threw = false
        try { map[8] = true } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `round trips through bytes`() {
        val map = ChunkBitmap(100)
        for (i in intArrayOf(0, 1, 7, 8, 63, 64, 99)) map[i] = true
        val restored = ChunkBitmap.fromBytes(map.toBytes(), 100)
        for (i in 0 until 100) assertEquals("bit $i", map[i], restored[i])
    }

    @Test
    fun `padding bits cannot fake completeness`() {
        // The attack: a chunk count of 100 occupies 13 bytes, leaving 4 unused bits. A peer that
        // sets them must not make isComplete() true for a transfer that is missing chunks - that
        // would commit a file with holes in it.
        val hostile = ByteArray(13) { 0xFF.toByte() }
        val map = ChunkBitmap.fromBytes(hostile, 100)
        assertEquals(100, map.countSet())
        assertTrue(map.isComplete())

        // And with a genuine gap it must not be complete.
        hostile[0] = 0xFE.toByte()
        val gapped = ChunkBitmap.fromBytes(hostile, 100)
        assertFalse(gapped.isComplete())
        assertEquals(listOf(0), gapped.missing())
    }

    @Test
    fun `fromBytes tolerates a short input`() {
        val map = ChunkBitmap.fromBytes(ByteArray(1) { 0x01 }, 100)
        assertTrue(map[0])
        assertFalse(map[50])
    }

    @Test
    fun `allSet is complete`() {
        assertTrue(ChunkBitmap.allSet(37).isComplete())
        assertEquals(37, ChunkBitmap.allSet(37).countSet())
    }

    @Test
    fun `the bitmap is compact for a large transfer`() {
        // 12800 chunks (a 50 GB file at 4 MiB) must be ~1.6 KB, not tens of KB of integers, since
        // it travels in a control message on every reconnect.
        assertEquals(1600, ChunkBitmap(12800).toBytes().size)
    }
}

class PartFileTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private lateinit var dest: File

    @Before
    fun setUp() {
        dest = temp.newFolder("dest")
    }

    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun writeAll(part: PartFile, data: ByteArray, chunkSize: Int) {
        val count = ChunkMath.chunkCount(data.size.toLong(), chunkSize)
        for (i in 0 until count) {
            val offset = ChunkMath.offsetOf(i, chunkSize).toInt()
            val length = ChunkMath.lengthOf(i, data.size.toLong(), chunkSize)
            part.writeChunk(i, data.copyOfRange(offset, offset + length))
        }
    }

    @Test
    fun `a verified file is published under its real name`() {
        val data = payload(5000)
        val part = PartFile.open(dest, "photo.jpg", data.size.toLong(), 1024)
        writeAll(part, data, 1024)

        val result = part.verifyAndCommit(ChunkMath.hashChunk(data))
        assertEquals(VerificationResult.VERIFIED, result)

        val published = File(dest, "photo.jpg")
        assertTrue(published.exists())
        assertArrayEquals(data, published.readBytes())
        assertFalse("the part file must be gone", part.partFile.exists())
        assertFalse("the sidecar must be gone", part.metaFile.exists())
    }

    @Test
    fun `nothing appears under the real name until it verifies`() {
        // The central REQ-T07 property. A half-written file carrying the real name is worse than no
        // file, because a dropped connection is indistinguishable from a completed one.
        val data = payload(5000)
        val part = PartFile.open(dest, "photo.jpg", data.size.toLong(), 1024)
        part.writeChunk(0, data.copyOfRange(0, 1024))

        assertFalse("must not exist mid-transfer", File(dest, "photo.jpg").exists())
        assertTrue("the part file carries the data", part.partFile.exists())
    }

    @Test
    fun `a hash mismatch refuses to publish and keeps the part file`() {
        val data = payload(3000)
        val part = PartFile.open(dest, "doc.pdf", data.size.toLong(), 1024)
        writeAll(part, data, 1024)

        val result = part.verifyAndCommit("0".repeat(64))
        assertEquals(VerificationResult.HASH_MISMATCH, result)
        assertFalse("a failed file must never be published", File(dest, "doc.pdf").exists())
        assertTrue("the part file is kept so a retry is possible", part.partFile.exists())
    }

    @Test
    fun `an incomplete transfer refuses to commit`() {
        val data = payload(3000)
        val part = PartFile.open(dest, "doc.pdf", data.size.toLong(), 1024)
        part.writeChunk(0, data.copyOfRange(0, 1024))

        assertEquals(VerificationResult.NOT_VERIFIED, part.verifyAndCommit(ChunkMath.hashChunk(data)))
        assertFalse(File(dest, "doc.pdf").exists())
    }

    @Test
    fun `resume re-verifies from disk and clears corrupted chunks`() {
        // REQ-T06. The sidecar says a chunk was written; the bytes on disk disagree. Trusting the
        // record would let bytes injected in an earlier session survive into the final file.
        val data = payload(4096)
        val part = PartFile.open(dest, "big.bin", data.size.toLong(), 1024)
        writeAll(part, data, 1024)
        assertTrue(part.receivedChunks.isComplete())

        // Corrupt chunk 2 on disk behind the engine's back.
        java.io.RandomAccessFile(part.partFile, "rw").use { raf ->
            raf.seek(2L * 1024)
            raf.write(byteArrayOf(0, 0, 0, 0))
        }

        val missing = part.verifyAndRepair()
        assertEquals("the corrupted chunk must be re-requested", listOf(2), missing)
        assertFalse(part.receivedChunks.isComplete())
    }

    @Test
    fun `resume keeps chunks that still verify`() {
        val data = payload(4096)
        val part = PartFile.open(dest, "big.bin", data.size.toLong(), 1024)
        writeAll(part, data, 1024)
        assertTrue("an untouched part file must verify completely", part.verifyAndRepair().isEmpty())
    }

    @Test
    fun `a missing part file means everything is needed`() {
        val part = PartFile.open(dest, "gone.bin", 4096, 1024)
        assertEquals(listOf(0, 1, 2, 3), part.verifyAndRepair())
    }

    @Test
    fun `a name collision is resolved by renaming`() {
        // REQ-T12 default. Overwriting on a remote peer's say-so would be destructive.
        File(dest, "photo.jpg").writeText("the original")
        val data = payload(100)
        val part = PartFile.open(dest, "photo.jpg", data.size.toLong(), 1024)
        writeAll(part, data, 1024)
        assertEquals(VerificationResult.VERIFIED, part.verifyAndCommit(ChunkMath.hashChunk(data)))

        assertEquals("the original must be untouched", "the original", File(dest, "photo.jpg").readText())
        assertTrue("the new file must be renamed", File(dest, "photo (1).jpg").exists())
    }

    @Test
    fun `repeated collisions keep counting`() {
        File(dest, "a.txt").writeText("x")
        File(dest, "a (1).txt").writeText("y")
        val data = payload(10)
        val part = PartFile.open(dest, "a.txt", data.size.toLong(), 1024)
        writeAll(part, data, 1024)
        part.verifyAndCommit(ChunkMath.hashChunk(data))
        assertTrue(File(dest, "a (2).txt").exists())
    }

    @Test
    fun `nested paths create their directories`() {
        val data = payload(50)
        val part = PartFile.open(dest, "Holiday/2026/pic.jpg", data.size.toLong(), 1024)
        writeAll(part, data, 1024)
        assertEquals(VerificationResult.VERIFIED, part.verifyAndCommit(ChunkMath.hashChunk(data)))
        assertTrue(File(dest, "Holiday/2026/pic.jpg").exists())
    }

    @Test
    fun `a traversal path is refused before anything touches the filesystem`() {
        // The single most important check in the receive path (REQ-T11). PartFile.open is the only
        // route a peer-supplied name reaches the disk, so validation lives there.
        for (hostile in listOf(
            "../escape.txt", "/etc/passwd", "C:/Windows/x.dll", "a/../../b",
            "con", "nul.txt", "has\\backslash", "trailing. ", "file.txt:stream",
        )) {
            var rejected = false
            try {
                PartFile.open(dest, hostile, 10, 1024)
            } catch (e: PathRejectedException) {
                rejected = true
            }
            assertTrue("'$hostile' must be rejected", rejected)
        }
        assertEquals("nothing may have been created", 0, dest.listFiles()!!.size)
    }

    @Test
    fun `the rejection carries the specific reason`() {
        var reason: RelativePath.Reason? = null
        try {
            PartFile.open(dest, "../x", 10, 1024)
        } catch (e: PathRejectedException) {
            reason = e.reason
        }
        assertEquals(RelativePath.Reason.TRAVERSAL, reason)
    }

    @Test
    fun `discard removes the partial data`() {
        val part = PartFile.open(dest, "x.bin", 2048, 1024)
        part.writeChunk(0, payload(1024))
        assertTrue(part.partFile.exists())
        part.discard()
        assertFalse(part.partFile.exists())
        assertFalse(part.metaFile.exists())
    }

    @Test
    fun `a wrong-size chunk is refused`() {
        val part = PartFile.open(dest, "x.bin", 2048, 1024)
        var threw = false
        try { part.writeChunk(0, payload(500)) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue("a short non-final chunk must be refused", threw)
    }

    @Test
    fun `an out-of-range chunk index is refused`() {
        val part = PartFile.open(dest, "x.bin", 2048, 1024)
        var threw = false
        try { part.writeChunk(5, payload(1024)) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `an empty file round trips`() {
        val part = PartFile.open(dest, "empty.txt", 0, 1024)
        part.writeChunk(0, ByteArray(0))
        assertEquals(VerificationResult.VERIFIED, part.verifyAndCommit(ChunkMath.hashChunk(ByteArray(0))))
        assertTrue(File(dest, "empty.txt").exists())
        assertEquals(0, File(dest, "empty.txt").length())
    }

    @Test
    fun `chunks may arrive out of order`() {
        // Resume sends only the missing indices, so arrival order is not sequential.
        val data = payload(4096)
        val part = PartFile.open(dest, "x.bin", data.size.toLong(), 1024)
        for (i in listOf(3, 0, 2, 1)) {
            val offset = i * 1024
            part.writeChunk(i, data.copyOfRange(offset, offset + 1024))
        }
        assertEquals(VerificationResult.VERIFIED, part.verifyAndCommit(ChunkMath.hashChunk(data)))
        assertArrayEquals(data, File(dest, "x.bin").readBytes())
    }

    @Test
    fun `the manifest round trips the received state`() {
        val data = payload(4096)
        val part = PartFile.open(dest, "x.bin", data.size.toLong(), 1024)
        part.writeChunk(0, data.copyOfRange(0, 1024))
        part.writeChunk(2, data.copyOfRange(2048, 3072))

        val manifest = part.toManifest("t1", "peer", 0, 1000L)
        assertEquals(setOf(0, 2), manifest.completedChunkIndices)

        val reopened = PartFile.open(dest, "x.bin", data.size.toLong(), 1024)
        reopened.restore(manifest)
        assertEquals(listOf(1, 3), reopened.receivedChunks.missing())
    }

    @Test
    fun `a streamed file hash matches a buffer hash`() {
        val data = payload(300_000)
        val f = File(dest, "h.bin")
        f.writeBytes(data)
        assertEquals(ChunkMath.hashChunk(data), ChunkMath.hashFile(f))
    }
}

