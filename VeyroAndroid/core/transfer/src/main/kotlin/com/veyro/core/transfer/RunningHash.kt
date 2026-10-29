package com.veyro.core.transfer

import com.veyro.core.common.hex
import java.io.IOException
import java.security.MessageDigest

/**
 * The whole-item SHA-256, built from the chunks as they are written (ADR-049).
 *
 * Chunks normally arrive in order, so the hash is ready the moment the last one is written and the
 * commit does not read the item back (that took 13 s for 4 GB on a phone). A chunk that arrives
 * ahead of a gap is read back from storage at the end. A chunk written again after it was hashed
 * makes the whole item be read back, because bytes already in the hash may have changed. Either
 * way the result is the hash of exactly the bytes in storage, so REQ-T04 holds.
 */
internal class RunningHash(private val sizeBytes: Long, private val chunkSize: Int) {

    private var digest: MessageDigest = MessageDigest.getInstance(ALGORITHM)

    /** Chunks 0 until this index are in [digest], in order. */
    private var hashedChunks = 0

    /** True when [digest] can no longer be trusted and [finish] must read everything back. */
    private var stale = false

    /** Call after chunk [chunkIndex] holding [data] was written to storage. */
    @Synchronized
    fun onWritten(chunkIndex: Int, data: ByteArray) {
        when {
            stale -> Unit
            chunkIndex == hashedChunks -> {
                digest.update(data)
                hashedChunks++
            }
            chunkIndex < hashedChunks -> stale = true
            else -> Unit // ahead of a gap: read back by finish()
        }
    }

    /**
     * The finished hash, hex. [read] fills `buffer[0 until length]` from storage at `position` and
     * returns the bytes read; it is called only for what was not hashed in order.
     */
    @Synchronized
    fun finish(read: (position: Long, buffer: ByteArray, length: Int) -> Int): String {
        val active = if (stale) MessageDigest.getInstance(ALGORITHM) else digest
        var position = if (stale) 0L else minOf(sizeBytes, hashedChunks.toLong() * chunkSize)
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (position < sizeBytes) {
            val length = minOf(buffer.size.toLong(), sizeBytes - position).toInt()
            val n = read(position, buffer, length)
            if (n <= 0) throw IOException("storage ended at byte $position of $sizeBytes")
            active.update(buffer, 0, n)
            position += n
        }
        // The digest is spent: a second finish (a retry after a mismatch) reads everything back.
        digest = MessageDigest.getInstance(ALGORITHM)
        hashedChunks = 0
        stale = true
        return active.digest().hex()
    }

    private companion object {
        const val ALGORITHM = "SHA-256"
        const val READ_BUFFER_BYTES = 1 shl 16
    }
}
