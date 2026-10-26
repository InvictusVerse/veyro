package com.veyro.core.transfer

import com.veyro.core.model.VerificationResult

/**
 * The outcome of one item in a transfer.
 *
 * Lives in its own file (not inside a transfer-engine class) because it is shared by the file
 * logic here and by the link runner that drives it. [verification] is the honest result: an item
 * that failed its hash is reported, never silently presented as delivered (REQ-T04).
 */
public data class ItemResult(
    val itemIndex: Int,
    val relativePath: String,
    val bytesWritten: Long,
    val verification: VerificationResult,
    val errorMessage: String? = null,
    val destinationUri: String? = null,
) {
    public val isVerified: Boolean get() = verification == VerificationResult.VERIFIED
}
