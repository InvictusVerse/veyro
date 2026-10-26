package com.veyro.core.transfer

import java.io.File

/**
 * Storage publication abstraction for finalized transfer payloads (REQ-T12).
 *
 * Distinct from receiving and hashing: only once all bytes have been received and
 * verified against their SHA-256 does a file cross into its final destination.
 *
 * On Android, images and media publish to MediaStore collections (Pictures/Veyro, etc.)
 * with an `IS_PENDING` lifecycle and canonical `content://` URIs.
 * On JVM / non-media / test environments, a filesystem publisher stores files in a target directory.
 */
public interface TransferStoragePublisher {
    public suspend fun publish(
        sourceFile: File,
        relativePath: String,
        mimeType: String?,
        sizeBytes: Long,
    ): StoragePublishResult
}

public sealed interface StoragePublishResult {
    public data class Success(
        val destinationUri: String,
        val displayName: String,
        val mimeType: String,
        val bytesWritten: Long,
    ) : StoragePublishResult

    public data class Failed(
        val reason: String,
        val throwable: Throwable? = null,
    ) : StoragePublishResult
}
