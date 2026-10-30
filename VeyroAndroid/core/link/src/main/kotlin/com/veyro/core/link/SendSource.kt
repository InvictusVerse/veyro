package com.veyro.core.link

import com.veyro.core.common.hex
import com.veyro.core.transfer.ChunkMath
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Where the bytes of one outgoing item come from while [TransferRunner] sends it.
 *
 * A staged copy is a [File]. A folder's files are read straight from their `content://` streams
 * instead ([ofStream], ADR-064): staging a folder of gigabytes would double the space it takes,
 * and copying an item just before it is sent would leave the data socket silent long enough for
 * the receiver's read timeout (30 s on Android, 60 s on Windows) to end the transfer.
 *
 * Reads are in chunk order for a full send, so a stream only ever moves forward; a resumed send
 * skips forward, and only [sha256] reads from the start again. Messages never name the file.
 */
public interface SendSource : Closeable {
    /** Bytes the item has, as offered. */
    public val length: Long

    /** Fills [buffer] with the bytes at [offset]. Throws when the source cannot supply them. */
    public fun read(offset: Long, buffer: ByteArray)

    /** SHA-256 hex of the whole item, for a resumed send that did not read every chunk. */
    public fun sha256(): String

    public companion object {
        /** A regular file, or null when there is none (the runner reports "source missing"). */
        public fun of(file: File): SendSource? = if (file.isFile) FileSource(file) else null

        /**
         * The [length] bytes [open] streams. The stream must end there: a file that grew or
         * shrank since it was listed fails the send rather than arriving cut short or padded.
         */
        public fun ofStream(length: Long, open: () -> InputStream): SendSource = StreamSource(length, open)
    }
}

private class FileSource(private val file: File) : SendSource {
    private var raf: RandomAccessFile? = null

    override val length: Long = file.length()

    override fun read(offset: Long, buffer: ByteArray) {
        val handle = raf ?: RandomAccessFile(file, "r").also { raf = it }
        handle.seek(offset)
        handle.readFully(buffer)
    }

    override fun sha256(): String = ChunkMath.hashFile(file)

    override fun close() {
        runCatching { raf?.close() }
        raf = null
    }
}

private class StreamSource(override val length: Long, private val open: () -> InputStream) : SendSource {
    private var stream: InputStream? = null
    private var position = 0L

    override fun read(offset: Long, buffer: ByteArray) {
        require(offset >= 0 && offset + buffer.size <= length) { "read past the end of the item" }
        if (stream == null || offset < position) reopen()
        val input = stream!!
        skipFully(input, offset - position)
        readFully(input, buffer)
        position = offset + buffer.size
        if (position == length) expectEnd(input)
    }

    override fun sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        open().use { input ->
            val buffer = ByteArray(256 * 1024)
            var left = length
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) throw EOFException("The file got shorter while it was being sent")
                digest.update(buffer, 0, read)
                left -= read
            }
            expectEnd(input)
        }
        return digest.digest().hex()
    }

    override fun close() {
        runCatching { stream?.close() }
        stream = null
    }

    private fun reopen() {
        close()
        stream = open()
        position = 0L
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val scratch by lazy(LazyThreadSafetyMode.NONE) { ByteArray(64 * 1024) }
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            // skip() may do nothing on some streams; reading always moves.
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (read < 0) throw EOFException("The file got shorter while it was being sent")
            left -= read
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var at = 0
        while (at < buffer.size) {
            val read = input.read(buffer, at, buffer.size - at)
            if (read < 0) throw EOFException("The file got shorter while it was being sent")
            at += read
        }
    }

    private fun expectEnd(input: InputStream) {
        if (input.read() >= 0) throw IOException("The file got larger while it was being sent")
    }
}
