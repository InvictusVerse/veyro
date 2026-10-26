package com.veyro.core.link

import com.veyro.core.model.TransferRoute
import com.veyro.core.transfer.ItemResult

/**
 * Result of an inbound transfer cycle.
 *
 * Implements [List]<[ItemResult]> by delegation so all callers and assertions expecting
 * a `List<ItemResult>` work transparently, while exposing transfer-level metadata needed
 * for history persistence and logging.
 */
public class ReceiveResult(
    public val transferId: String,
    public val senderName: String,
    public val totalBytes: Long,
    public val startedAtEpochMillis: Long,
    public val items: List<ItemResult>,
    public val route: TransferRoute? = null,
) : List<ItemResult> by items {

    public companion object {
        public val EMPTY: ReceiveResult = ReceiveResult(
            transferId = "",
            senderName = "",
            totalBytes = 0L,
            startedAtEpochMillis = 0L,
            items = emptyList(),
            route = null,
        )
    }
}
