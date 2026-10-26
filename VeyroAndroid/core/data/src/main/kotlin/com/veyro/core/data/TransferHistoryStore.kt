package com.veyro.core.data

import com.veyro.core.model.Transfer
import kotlinx.coroutines.flow.Flow

/** Append-mostly log of past transfers (REQ-T13). Querying happens in memory (ADR-006). */
public interface TransferHistoryStore {
    public val history: Flow<List<Transfer>>
    public suspend fun append(transfer: Transfer)
    public suspend fun update(transfer: Transfer)
    public suspend fun clear()

    /** Drops one entry ("Delete from history" on Home, ADR-038). The file itself is untouched. */
    public suspend fun remove(transferId: String) {}
}