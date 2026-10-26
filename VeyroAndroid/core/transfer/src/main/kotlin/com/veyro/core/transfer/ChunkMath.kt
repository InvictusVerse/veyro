package com.veyro.core.transfer

import com.veyro.core.common.hex
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Chunk arithmetic and hashing, kept pure so the parts that are easy to get subtly wrong are
 * testable without a socket or a transfer.
 *
 * Everything here is 64-bit. A 50 GB file (NFR-07) has roughly 12800 chunks at 4 MiB, which fits
 * an `Int` index comfortably, but the *offsets* do not fit a 32-bit integer and silently wrapping
 * at 2 GB is the classic large-file bug.
 */
public object ChunkMath {

    /** Number of chunks for a file, with the last one usually short. Zero-length files have one. */
    public fun chunkCount(sizeBytes: Long, chunkSize: Int): Int {
        require(chunkSize > 0) { "chunk size must be positive" }
        require(sizeBytes >= 0) { "size must not be negative" }
        if (sizeBytes == 0L) return 1 // an empty file still needs one (empty) chunk to be "sent"
        val count = (sizeBytes + chunkSize - 1) / chunkSize
        require(count <= Int.MAX_VALUE) { "file too large for an Int chunk index" }
        return count.toInt()
    }

    /** Byte offset of a chunk. `Long` arithmetic throughout - see the class note. */
    public fun offsetOf(chunkIndex: Int, chunkSize: Int): Long {
        require(chunkIndex >= 0) { "chunk index must not be negative" }
        return chunkIndex.toLong() * chunkSize.toLong()
    }

    /** Length of a chunk, accounting for a short final chunk. */
    public fun lengthOf(chunkIndex: Int, sizeBytes: Long, chunkSize: Int): Int {
        val offset = offsetOf(chunkIndex, chunkSize)
        if (offset >= sizeBytes) return 0
        val remaining = sizeBytes - offset
        return if (remaining < chunkSize) remaining.toInt() else chunkSize
    }

    /** SHA-256 of a byte range, hex-encoded. */
    public fun hashChunk(data: ByteArray, offset: Int = 0, length: Int = data.size): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data, offset, length)
        return digest.digest().hex()
    }

    /**
     * Streaming SHA-256 of a whole file.
     *
     * Streamed in fixed buffers rather than read whole, because NFR-06 requires peak heap to be
     * independent of file size. Reading a 50 GB file into memory to hash it would be the single
     * easiest way to break that.
     */
    public fun hashFile(file: File, bufferSize: Int = 1 shl 16): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(bufferSize)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().hex()
    }

    /** Reads one chunk from a file at the right offset. Used by the sender and by resume checks. */
    public fun readChunk(file: File, chunkIndex: Int, chunkSize: Int): ByteArray {
        val offset = offsetOf(chunkIndex, chunkSize)
        val length = lengthOf(chunkIndex, file.length(), chunkSize)
        if (length == 0) return ByteArray(0)
        val out = ByteArray(length)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(out)
        }
        return out
    }
}

/**
 * The resume bitmap.
 *
 * A plain `Set<Int>` would work for small files but a 50 GB transfer has ~12800 chunks, and the set
 * has to be serialised into a control message on every reconnect. A bitset is one bit per chunk -
 * 1.6 KB for that file instead of tens of KB of integers - and gives O(1) membership.
 */
public class ChunkBitmap(public val chunkCount: Int) {

    private val bits = ByteArray((chunkCount + 7) / 8)

    public operator fun get(index: Int): Boolean {
        if (index !in 0 until chunkCount) return false
        return (bits[index / 8].toInt() shr (index % 8)) and 1 == 1
    }

    public operator fun set(index: Int, value: Boolean) {
        require(index in 0 until chunkCount) { "chunk index $index out of range" }
        val byteIndex = index / 8
        val mask = 1 shl (index % 8)
        bits[byteIndex] = if (value) {
            (bits[byteIndex].toInt() or mask).toByte()
        } else {
            (bits[byteIndex].toInt() and mask.inv()).toByte()
        }
    }

    public fun countSet(): Int = (0 until chunkCount).count { this[it] }

    public fun isComplete(): Boolean = countSet() == chunkCount

    public fun missing(): List<Int> = (0 until chunkCount).filter { !this[it] }

    public fun toBytes(): ByteArray = bits.copyOf()

    public fun clear() {
        bits.fill(0)
    }

    public companion object {
        public fun fromBytes(bytes: ByteArray, chunkCount: Int): ChunkBitmap {
            val map = ChunkBitmap(chunkCount)
            val copy = minOf(bytes.size, map.bits.size)
            bytes.copyInto(map.bits, 0, 0, copy)
            // Bits past chunkCount in the final byte are padding. They must be cleared, or a
            // peer that sets them would make `isComplete()` true for a transfer that is not -
            // which would commit a file with missing chunks.
            for (i in chunkCount until map.bits.size * 8) {
                val byteIndex = i / 8
                val mask = 1 shl (i % 8)
                map.bits[byteIndex] = (map.bits[byteIndex].toInt() and mask.inv()).toByte()
            }
            return map
        }

        public fun allSet(chunkCount: Int): ChunkBitmap =
            ChunkBitmap(chunkCount).also { for (i in 0 until chunkCount) it[i] = true }
    }
}
