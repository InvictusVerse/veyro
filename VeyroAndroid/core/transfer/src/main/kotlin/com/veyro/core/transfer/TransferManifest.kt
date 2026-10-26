package com.veyro.core.transfer

import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferItem
import kotlinx.serialization.Serializable

/**
 * What is written to disk for a transfer that has not yet been verified.
 *
 * The data file lives at `destDir/relPath.veyropart` and this manifest lives next to it as
 * `destDir/relPath.veyrometa` (CBOR). Nothing is ever published under its final name until
 * verification passes and the part file is atomically renamed (REQ-T07, 04-PROTOCOL.md section 6.3).
 */
@Serializable
public data class TransferManifest(
    val transferId: String,
    val peerDeviceId: String,
    val itemIndex: Int,
    val relativePath: String,
    val sizeBytes: Long,
    val chunkSize: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** SHA-256 hex per chunk, null for chunks not yet verified. */
    val chunkHashes: List<String?>,
    /** Indices that have been verified against `chunkHashes` and trusted for resume (REQ-T06). */
    val completedChunkIndices: Set<Int>,
    /** Final SHA-256 hex of the whole file, known only after the last chunk arrives. */
    val expectedFileSha256: String? = null,
    val direction: TransferDirection = TransferDirection.RECEIVE,
)

/** Decision before dialing - should we even start? */
public enum class OfferDecision { ACCEPT, DECLINE, DEFERRED }

public data class TransferOffer(
    val transferId: String,
    val items: List<TransferItem>,
    val totalBytes: Long,
    val senderName: String,
)

/**
 * The set of decisions a `TransferDecision` carries back to the sender:
 * accept/reject per item plus the resume bitmap for accepted items that already have a sidecar
 * on disk (04-PROTOCOL.md section 5.3, REQ-T05/T06).
 */
public data class TransferDecision(
    val transferId: String,
    val accepted: Boolean,
    val perItemAccept: BooleanArray? = null,
    /** For each accepted item, the chunk indices the receiver still needs. */
    val missingChunksByItem: Map<Int, Set<Int>>? = null,
)