package com.veyro.core.transfer

import com.veyro.core.common.VeyroLog
import com.veyro.core.model.VerificationResult
import com.veyro.core.protocol.RelativePath
import kotlin.coroutines.cancellation.CancellationException

/** One item being received: written chunk by chunk, then verified as a whole (REQ-T04). */
public interface ReceivingItem : java.io.Closeable {
    public val chunkSize: Int

    /** True once the item is in its final place under its own name. */
    public val isCommitted: Boolean

    public fun writeChunk(chunkIndex: Int, data: ByteArray)

    /**
     * Checks the whole-item hash. A [PartFile] also renames itself into place here; a [DirectPart]
     * is made visible by [DirectPart.publish] afterwards.
     */
    public fun verifyAndCommit(expectedSha256: String): VerificationResult

    /** Removes what was written. No-op once committed. */
    public fun discard()
}

/**
 * Storage an item is received straight into (ADR-049), hidden until [commit]. On Android this is
 * a MediaStore entry with `IS_PENDING = 1`: other apps and Gallery cannot see it, and nothing has
 * to be copied once the hash checks out.
 */
public interface DirectReceiveTarget : java.io.Closeable {
    public fun write(position: Long, data: ByteArray)

    /** Reads back into `buffer[0 until length]`; only used to hash chunks that arrived out of order. */
    public fun read(position: Long, buffer: ByteArray, length: Int): Int

    /** Makes the item visible under its name. On failure the entry is removed. */
    public suspend fun commit(): StoragePublishResult

    /** Removes the entry. No-op after a successful [commit]. */
    public fun abort()
}

/**
 * An item received into a [DirectReceiveTarget] (ADR-049). Same rules as [PartFile]: the path is
 * validated before anything is created (REQ-T11), and the item only becomes visible after the
 * whole-item hash matches (REQ-T04, REQ-T07). The difference is that verified bytes are already
 * where they belong, so a 4 GB video is done when its last chunk arrives instead of a minute later.
 */
public class DirectPart private constructor(
    private val target: DirectReceiveTarget,
    public val sizeBytes: Long,
    override val chunkSize: Int,
) : ReceivingItem {

    public val chunkCount: Int = ChunkMath.chunkCount(sizeBytes, chunkSize)

    private val received = ChunkBitmap(chunkCount)
    private val runningHash = RunningHash(sizeBytes, chunkSize)

    @Volatile private var verified = false
    @Volatile private var committed = false

    override val isCommitted: Boolean get() = committed

    public companion object {
        private const val TAG = "DirectPart"

        /**
         * Validates [relativePath], then asks [publisher] for a direct target. Null when it offers
         * none or could not create one; the caller then receives into a [PartFile].
         *
         * @throws PathRejectedException if [relativePath] fails validation.
         */
        public suspend fun open(
            publisher: TransferStoragePublisher,
            relativePath: String,
            mimeType: String?,
            sizeBytes: Long,
            chunkSize: Int,
        ): DirectPart? {
            val validation = RelativePath.validate(relativePath)
            if (validation is RelativePath.Result.Rejected) {
                throw PathRejectedException(relativePath, validation.reason, validation.detail)
            }
            val normalized = (validation as RelativePath.Result.Valid).normalized
            val target = try {
                publisher.openDirect(normalized, mimeType, sizeBytes)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                VeyroLog.w(TAG) { "No direct target (${t.javaClass.simpleName}); using a staging file" }
                null
            } ?: return null
            return DirectPart(target, sizeBytes, chunkSize)
        }
    }

    override fun writeChunk(chunkIndex: Int, data: ByteArray) {
        require(chunkIndex in 0 until chunkCount) { "chunk index $chunkIndex out of range" }
        val expectedLength = ChunkMath.lengthOf(chunkIndex, sizeBytes, chunkSize)
        require(data.size == expectedLength) {
            "chunk $chunkIndex should be $expectedLength bytes, got ${data.size}"
        }
        target.write(ChunkMath.offsetOf(chunkIndex, chunkSize), data)
        received[chunkIndex] = true
        runningHash.onWritten(chunkIndex, data)
    }

    override fun verifyAndCommit(expectedSha256: String): VerificationResult {
        if (!received.isComplete()) {
            VeyroLog.w(TAG) { "Verify refused: ${received.missing().size} chunk(s) missing" }
            return VerificationResult.NOT_VERIFIED
        }
        val actual = runCatching { runningHash.finish(target::read) }.getOrNull()
            ?: return VerificationResult.WRITE_FAILED
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            VeyroLog.w(TAG) { "Hash mismatch; the pending entry will be removed" }
            return VerificationResult.HASH_MISMATCH
        }
        verified = true
        return VerificationResult.VERIFIED
    }

    /** After [verifyAndCommit] returned VERIFIED: makes the item visible under its name. */
    public suspend fun publish(): StoragePublishResult {
        if (!verified) return StoragePublishResult.Failed("not verified")
        val result = target.commit()
        committed = result is StoragePublishResult.Success
        return result
    }

    override fun discard() {
        if (!committed) target.abort()
    }

    override fun close() {
        target.close()
    }
}
