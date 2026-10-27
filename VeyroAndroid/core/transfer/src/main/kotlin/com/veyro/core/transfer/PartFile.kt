package com.veyro.core.transfer

import com.veyro.core.common.VeyroLog
import com.veyro.core.model.VerificationResult
import com.veyro.core.protocol.RelativePath
import com.veyro.core.protocol.ProtocolConstants
import java.io.File
import java.io.RandomAccessFile

/**
 * The receive side's on-disk state for one item: the `.veyropart` data file and its `.veyrometa`
 * sidecar (04-PROTOCOL.md section 6.2).
 *
 * ## Why nothing is ever written under its final name
 *
 * A partially-written file that looks complete is worse than no file at all. A connection that
 * drops mid-transfer is indistinguishable from one that finished - the read loop simply ends and
 * nothing throws - so the only safe design is: write to a temporary name, verify the whole-file
 * hash, and only then rename (REQ-T07). The rename is atomic within a filesystem, so there is no
 * window where a half-file carries the real name.
 *
 * ## Why resume re-verifies from disk
 *
 * The sidecar records which chunks were written, but trusting that record blindly would let bytes
 * injected during an earlier session survive into the final file. On resume, every chunk the
 * sidecar claims is re-read and re-hashed; anything that disagrees is cleared and re-fetched
 * (REQ-T06). That is the difference between resume being a convenience and resume being a hole.
 */
public class PartFile private constructor(
    public val finalFile: File,
    public val partFile: File,
    public val metaFile: File,
    public val sizeBytes: Long,
    public val chunkSize: Int,
) : java.io.Closeable {

    public var committedFile: File? = null
        private set

    public val chunkCount: Int = ChunkMath.chunkCount(sizeBytes, chunkSize)

    /** Per-chunk hashes, used only for resume verification. Null entries are not yet written. */
    private val chunkHashes = arrayOfNulls<String>(chunkCount)

    private val received = ChunkBitmap(chunkCount)

    public val receivedChunks: ChunkBitmap get() = received

    @Volatile
    private var openRaf: RandomAccessFile? = null

    private fun getOrCreateRaf(): RandomAccessFile {
        return openRaf ?: synchronized(this) {
            openRaf ?: RandomAccessFile(partFile, "rw").also { raf ->
                if (raf.length() < sizeBytes) raf.setLength(sizeBytes)
                openRaf = raf
            }
        }
    }

    private fun closeRaf() {
        synchronized(this) {
            runCatching { openRaf?.close() }
            openRaf = null
        }
    }

    override fun close() {
        closeRaf()
    }

    public companion object {
        private const val TAG = "PartFile"

        /**
         * Resolves the destination and opens the part file.
         *
         * @throws PathRejectedException if [relativePath] fails validation. That check happens here
         * rather than at the call site so there is exactly one place a peer-supplied name can
         * reach the filesystem (REQ-T11).
         */
        public fun open(
            destinationDir: File,
            relativePath: String,
            sizeBytes: Long,
            chunkSize: Int = ProtocolConstants.DEFAULT_CHUNK_SIZE,
        ): PartFile {
            val validation = RelativePath.validate(relativePath)
            if (validation is RelativePath.Result.Rejected) {
                throw PathRejectedException(relativePath, validation.reason, validation.detail)
            }
            val normalized = (validation as RelativePath.Result.Valid).normalized

            val target = File(destinationDir, normalized)

            // Belt and braces after validation: canonicalise and confirm the resolved path is still
            // inside the destination. Validation should already guarantee it, but a symlink in the
            // destination tree is outside what string validation can see.
            val canonicalDest = destinationDir.canonicalFile
            val canonicalTarget = target.canonicalFile
            if (!canonicalTarget.path.startsWith(canonicalDest.path + File.separator) &&
                canonicalTarget.path != canonicalDest.path
            ) {
                throw PathRejectedException(
                    relativePath,
                    RelativePath.Reason.TRAVERSAL,
                    "resolved outside the destination directory",
                )
            }

            target.parentFile?.mkdirs()

            return PartFile(
                finalFile = target,
                partFile = File(target.parentFile, target.name + ProtocolConstants.PART_SUFFIX),
                metaFile = File(target.parentFile, target.name + ProtocolConstants.META_SUFFIX),
                sizeBytes = sizeBytes,
                chunkSize = chunkSize,
            )
        }
    }

    /** Writes one chunk at its offset and records its hash. */
    public fun writeChunk(chunkIndex: Int, data: ByteArray) {
        require(chunkIndex in 0 until chunkCount) { "chunk index $chunkIndex out of range" }
        val expectedLength = ChunkMath.lengthOf(chunkIndex, sizeBytes, chunkSize)
        require(data.size == expectedLength) {
            "chunk $chunkIndex should be $expectedLength bytes, got ${data.size}"
        }

        val raf = getOrCreateRaf()
        synchronized(raf) {
            raf.seek(ChunkMath.offsetOf(chunkIndex, chunkSize))
            raf.write(data)
        }

        chunkHashes[chunkIndex] = ChunkMath.hashChunk(data)
        received[chunkIndex] = true
    }

    /**
     * Re-reads and re-hashes every chunk the sidecar claims, clearing any that disagree.
     *
     * @return the chunks still needed.
     */
    public fun verifyAndRepair(): List<Int> {
        if (!partFile.exists()) {
            received.clear()
            return (0 until chunkCount).toList()
        }
        var repaired = 0
        for (index in 0 until chunkCount) {
            if (!received[index]) continue
            val expected = chunkHashes[index]
            if (expected == null) {
                received[index] = false
                repaired++
                continue
            }
            val actual = runCatching {
                ChunkMath.hashChunk(ChunkMath.readChunk(partFile, index, chunkSize))
            }.getOrNull()
            if (actual != expected) {
                received[index] = false
                chunkHashes[index] = null
                repaired++
            }
        }
        if (repaired > 0) {
            VeyroLog.w(TAG) { "Resume check cleared $repaired chunk(s) that did not verify" }
        }
        return received.missing()
    }

    /**
     * Verifies the whole file against [expectedSha256] and, on success, atomically publishes it.
     *
     * The order is deliberate and is the heart of REQ-T04: hash first, rename second. Renaming
     * first and verifying after would briefly expose an unverified file under its real name, and a
     * crash in that window would leave it there permanently.
     */
    public fun verifyAndCommit(expectedSha256: String): VerificationResult {
        closeRaf()
        if (!received.isComplete()) {
            VeyroLog.w(TAG) { "Commit refused: ${received.missing().size} chunk(s) missing" }
            return VerificationResult.NOT_VERIFIED
        }
        if (partFile.length() != sizeBytes) {
            VeyroLog.w(TAG) { "Commit refused: size is ${partFile.length()}, expected $sizeBytes" }
            return VerificationResult.HASH_MISMATCH
        }

        val actual = runCatching { ChunkMath.hashFile(partFile) }.getOrNull()
        if (actual == null) {
            return VerificationResult.WRITE_FAILED
        }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            VeyroLog.w(TAG) {
                "Commit refused for ${finalFile.name}: hash mismatch. The part file is kept for a retry."
            }
            return VerificationResult.HASH_MISMATCH
        }

        val published = resolveCollision(finalFile)
        if (!partFile.renameTo(published)) {
            // Rename can fail across filesystems or if the target exists. Fall back to a copy, then
            // remove the source - slower, but it must not silently lose a verified file.
            return if (runCatching { partFile.copyTo(published, overwrite = false) }.isSuccess) {
                val copiedHash = runCatching { ChunkMath.hashFile(published) }.getOrNull()
                if (copiedHash != null && copiedHash.equals(actual, ignoreCase = true)) {
                    partFile.delete()
                    metaFile.delete()
                    committedFile = published
                    VerificationResult.VERIFIED
                } else {
                    published.delete()
                    VerificationResult.WRITE_FAILED
                }
            } else {
                VerificationResult.WRITE_FAILED
            }
        }
        metaFile.delete()
        committedFile = published
        return VerificationResult.VERIFIED
    }

    /** Removes the partial data and its sidecar. Used on cancel (REQ-T10). */
    public fun discard() {
        closeRaf()
        partFile.delete()
        metaFile.delete()
    }

    /** Restores recorded state from a sidecar. Call before [verifyAndRepair]. */
    public fun restore(manifest: TransferManifest) {
        require(manifest.chunkSize == chunkSize) { "chunk size changed between sessions" }
        for ((index, hash) in manifest.chunkHashes.withIndex()) {
            if (index >= chunkCount) break
            chunkHashes[index] = hash
        }
        received.clear()
        for (index in manifest.completedChunkIndices) {
            if (index in 0 until chunkCount) received[index] = true
        }
    }

    /** Snapshot for persisting as the sidecar. */
    public fun toManifest(transferId: String, peerDeviceId: String, itemIndex: Int, nowMillis: Long):
        TransferManifest = TransferManifest(
        transferId = transferId,
        peerDeviceId = peerDeviceId,
        itemIndex = itemIndex,
        relativePath = finalFile.name,
        sizeBytes = sizeBytes,
        chunkSize = chunkSize,
        createdAtMillis = nowMillis,
        updatedAtMillis = nowMillis,
        chunkHashes = chunkHashes.toList(),
        completedChunkIndices = (0 until chunkCount).filter { received[it] }.toSet(),
    )

    /**
     * `photo.jpg` -> `photo (1).jpg` when the target exists (REQ-T12, default RENAME).
     *
     * Overwriting by default would be the wrong choice: a transfer is initiated by someone else, so
     * silently replacing a local file is destructive on their say-so.
     */
    private fun resolveCollision(target: File): File {
        if (!target.exists()) return target
        val name = target.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        while (counter < 10_000) {
            val candidate = File(target.parentFile, "$stem ($counter)$extension")
            if (!candidate.exists()) return candidate
            counter++
        }
        return File(target.parentFile, "$stem (${System.currentTimeMillis()})$extension")
    }
}

/** Thrown when a peer-supplied path fails validation. Carries the reason for the error reply. */
public class PathRejectedException(
    public val relativePath: String,
    public val reason: RelativePath.Reason,
    detail: String,
) : Exception("path rejected ($reason): $detail")
