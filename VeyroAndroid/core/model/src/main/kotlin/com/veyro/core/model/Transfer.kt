package com.veyro.core.model

import kotlinx.serialization.Serializable

/** One file (or directory marker) inside a transfer. */
@Serializable
public data class TransferItem(
    val index: Int,
    /**
     * Path relative to the transfer root, always with `/` separators.
     * Validated by `com.veyro.core.protocol.RelativePath` before any filesystem use (REQ-T11).
     */
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
    val isDirectory: Boolean = false,
    val mimeType: String? = null,
    val destinationUri: String? = null,
    val verification: VerificationResult? = null,
) {
    val fileName: String get() = relativePath.substringAfterLast('/')
}

/** A whole transfer, from offer to outcome. */
public data class Transfer(
    val id: String,
    val peerDeviceId: DeviceId,
    val peerDisplayName: String? = null,
    val direction: TransferDirection,
    val items: List<TransferItem>,
    val totalBytes: Long,
    val state: TransferState,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val destinationUri: String? = null,
) {
    val itemCount: Int get() = items.count { !it.isDirectory }
}

/**
 * The top folder of a folder transfer (ADR-064): every item sits under one first segment, and at
 * least one is nested or is a directory. Null for loose files or a mix. For wording only; a path
 * is validated (REQ-T11) before it is used for anything else.
 */
public fun List<TransferItem>.folderName(): String? {
    val top = firstOrNull()?.relativePath?.substringBefore('/')?.takeIf { it.isNotEmpty() } ?: return null
    val allUnder = all { it.relativePath.startsWith("$top/") || (it.isDirectory && it.relativePath == top) }
    return top.takeIf { allUnder && any { item -> item.isDirectory || '/' in item.relativePath } }
}

public enum class TransferDirection { SEND, RECEIVE }

public enum class TransferState {
    /** Offer sent, waiting for the peer's decision. */
    PENDING_AUTHORIZATION,
    CONNECTING,
    TRANSFERRING,

    /** Connection lost mid-transfer; resumable state is on disk (REQ-T05). */
    INTERRUPTED,

    /** All items transferred and hash-verified. */
    COMPLETED,

    /** Finished, but at least one item failed verification or was rejected. */
    COMPLETED_WITH_ERRORS,
    DECLINED,
    CANCELLED,
    FAILED,
    ;

    public val isTerminal: Boolean
        get() = this in setOf(COMPLETED, COMPLETED_WITH_ERRORS, DECLINED, CANCELLED, FAILED)
}

/** Live progress for one transfer. Emitted as a Flow; never stored. */
public data class TransferProgress(
    val transferId: String,
    val currentItemIndex: Int,
    val currentItemName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val state: TransferState,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesTransferred.toDouble() / totalBytes).toFloat()

    /** Null when the rate is not yet known, rather than a misleading zero. */
    val etaSeconds: Long?
        get() = if (bytesPerSecond <= 0L) null else (totalBytes - bytesTransferred) / bytesPerSecond
}

/** Outcome of verifying one item end to end (REQ-T04). */
public enum class VerificationResult {
    VERIFIED,
    HASH_MISMATCH,
    PATH_REJECTED,
    WRITE_FAILED,
    NOT_VERIFIED,
}

/** Distinct execution stages of an active transfer lifecycle. */
public enum class TransferStage {
    PREPARING,
    CONNECTING,
    AWAITING_CONSENT,
    TRANSFERRING,
    VERIFYING,
    PUBLISHING,
    RECORDING_HISTORY,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * Application-scoped canonical active transfer state for live UI tracking.
 */
public data class ActiveTransfer(
    val transferId: String,
    val direction: TransferDirection,
    val peerDeviceId: DeviceId? = null,
    val peerDisplayName: String? = null,
    val currentFileName: String = "",
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 1,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val currentItemBytesTransferred: Long = 0L,
    val currentItemTotalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    val stage: TransferStage = TransferStage.PREPARING,
    val statusText: String = "",
    val startedAtEpochMillis: Long = System.currentTimeMillis(),
    val elapsedMillis: Long = 0L,
    val errorMessage: String? = null,
    val destinationUri: String? = null,
    val verification: VerificationResult? = null,
    val isVerified: Boolean = false,
    val route: TransferRoute? = null,
    /**
     * Set when the transfer is one folder (ADR-064): the folder sent, or the name it was saved
     * under here ("Photos (2)" when "Photos" was taken). [totalFiles] counts its files.
     */
    val folderName: String? = null,
) {
    val currentItemName: String get() = currentFileName
    val currentItemIndex: Int get() = currentFileIndex
    val totalItems: Int get() = totalFiles

    val overallFraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesTransferred.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)

    val currentItemFraction: Float
        get() = if (currentItemTotalBytes <= 0L) 0f else (currentItemBytesTransferred.toDouble() / currentItemTotalBytes).toFloat().coerceIn(0f, 1f)

    val isTerminal: Boolean
        get() = stage in setOf(TransferStage.COMPLETED, TransferStage.FAILED, TransferStage.CANCELLED)

    val effectiveStatusText: String
        get() = if (statusText.isNotBlank()) statusText else when (stage) {
            TransferStage.PREPARING -> "Preparing files..."
            TransferStage.CONNECTING -> "Connecting to ${peerDisplayName ?: "peer"}..."
            TransferStage.AWAITING_CONSENT -> "Waiting for acceptance..."
            TransferStage.TRANSFERRING -> when (direction) {
                TransferDirection.SEND -> "Sending to ${peerDisplayName ?: "peer"}..."
                TransferDirection.RECEIVE -> "Receiving from ${peerDisplayName ?: "peer"}..."
            }
            TransferStage.VERIFYING -> "Verifying SHA-256 integrity..."
            TransferStage.PUBLISHING -> "Saving to device..."
            TransferStage.RECORDING_HISTORY -> "Recording history..."
            TransferStage.COMPLETED -> "Transfer completed"
            TransferStage.FAILED -> errorMessage ?: "Transfer failed"
            TransferStage.CANCELLED -> "Transfer cancelled"
        }
}
