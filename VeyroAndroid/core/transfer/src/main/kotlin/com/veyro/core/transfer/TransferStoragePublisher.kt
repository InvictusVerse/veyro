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

    /**
     * A hidden destination to receive one item into, so the verified item needs no copy
     * (ADR-049). Null (the default) to receive into a staging file and [publish] it.
     * [relativePath] is already validated (REQ-T11).
     */
    public suspend fun openDirect(relativePath: String, mimeType: String?, sizeBytes: Long): DirectReceiveTarget? = null

    /**
     * Whether a received top folder named [name] would land on something that is already there
     * (ADR-064); [FolderReceive] then picks "name (2)". [name] is one validated segment.
     */
    public suspend fun folderExists(name: String): Boolean = false

    /**
     * Creates the empty directory [relativePath] of a received folder (already validated,
     * REQ-T11). False when this storage cannot hold an empty directory; the caller counts it.
     */
    public suspend fun createDirectory(relativePath: String): Boolean = false
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
