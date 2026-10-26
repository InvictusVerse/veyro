package com.veyro.core.data

import com.veyro.core.common.VeyroLog
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Transfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferItem
import com.veyro.core.model.TransferState
import com.veyro.core.model.VerificationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Transfer history, newest first, capped at [MAX_ENTRIES].
 *
 * JSON and **not authenticated**, deliberately. Unlike the trust store this file is not a security
 * boundary: tampering with it can make the app *display* a transfer that never happened, which is a
 * cosmetic lie rather than a way in. An HMAC here would be ceremony protecting nothing.
 *
 * Capped because an append-only log on a device that transfers daily grows without bound, and the
 * oldest entries are the least useful. Trimming happens on write, so the file never grows past the
 * cap in the first place.
 *
 * `Transfer` is stored with just the item **paths** and a count, not full item records: history is
 * a record of what happened, and re-listing every file of a 10,000-file folder would triple the
 * file size to reproduce information nobody scrolls to.
 */
public class FileTransferHistoryStore(
    directory: File,
    private val maxEntries: Int = MAX_ENTRIES,
) : TransferHistoryStore {

    public companion object {
        public const val MAX_ENTRIES: Int = 200
        private const val TAG = "History"
        private const val FILE_NAME = "history.json"
    }

    @Serializable
    private data class Stored(
        val version: Int = 1,
        val transfers: List<StoredTransfer> = emptyList(),
    )

    @Serializable
    private data class StoredItem(
        val index: Int = 0,
        val relativePath: String,
        val sizeBytes: Long = 0,
        val mimeType: String? = null,
        val destinationUri: String? = null,
        val verification: String? = null,
    )

    @Serializable
    private data class StoredTransfer(
        val id: String,
        val peerDeviceId: String,
        val peerDisplayName: String? = null,
        val direction: String,
        val state: String,
        val totalBytes: Long,
        val startedAt: Long,
        val finishedAt: Long? = null,
        val itemPaths: List<String> = emptyList(),
        val itemCount: Int = 0,
        val items: List<StoredItem> = emptyList(),
        val destinationUri: String? = null,
    )

    private val file = File(directory, FILE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val mutex = Mutex()
    private val state = MutableStateFlow(load())

    override val history: Flow<List<Transfer>> = state.asStateFlow()

    override suspend fun append(transfer: Transfer) = mutex.withLock {
        // Newest first, and a repeated transferId replaces rather than duplicates: the same
        // transfer is recorded both when it starts and when it finishes.
        val without = state.value.filterNot { it.id == transfer.id }
        persist((listOf(transfer) + without).take(maxEntries))
    }

    override suspend fun update(transfer: Transfer) = append(transfer)

    override suspend fun remove(transferId: String) = mutex.withLock {
        val remaining = state.value.filterNot { it.id == transferId }
        if (remaining.size != state.value.size) persist(remaining)
    }

    override suspend fun clear() = mutex.withLock {
        if (file.exists() && !file.delete()) {
            VeyroLog.w(TAG) { "Could not delete the history file" }
        }
        state.value = emptyList()
    }

    private fun load(): List<Transfer> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<Stored>(file.readText()).transfers.mapNotNull { it.toDomain() }
        } catch (t: Throwable) {
            // Not security-relevant, so losing it is recoverable. Logged rather than silent,
            // because repeatedly losing it would be a bug worth noticing.
            VeyroLog.w(TAG, t) { "History file unreadable; starting empty" }
            emptyList()
        }
    }

    private fun persist(transfers: List<Transfer>) {
        file.parentFile?.mkdirs()
        val stored = Stored(transfers = transfers.map { it.toStored() })
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(stored))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                VeyroLog.w(TAG) { "Could not persist history" }
                return
            }
        }
        state.value = transfers
    }

    private fun Transfer.toStored() = StoredTransfer(
        id = id,
        peerDeviceId = peerDeviceId.value,
        peerDisplayName = peerDisplayName,
        direction = direction.name,
        state = state.name,
        totalBytes = totalBytes,
        startedAt = startedAtEpochMillis,
        finishedAt = finishedAtEpochMillis,
        itemPaths = items.map { it.relativePath },
        itemCount = items.count { !it.isDirectory },
        items = items.map {
            StoredItem(
                index = it.index,
                relativePath = it.relativePath,
                sizeBytes = it.sizeBytes,
                mimeType = it.mimeType,
                destinationUri = it.destinationUri,
                verification = it.verification?.name,
            )
        },
        destinationUri = destinationUri ?: items.firstOrNull { it.destinationUri != null }?.destinationUri,
    )

    private fun StoredTransfer.toDomain(): Transfer? {
        val peer = DeviceId.parseOrNull(peerDeviceId) ?: DeviceId(peerDeviceId)
        val domainItems = if (items.isNotEmpty()) {
            items.map { storedItem ->
                TransferItem(
                    index = storedItem.index,
                    relativePath = storedItem.relativePath,
                    sizeBytes = storedItem.sizeBytes,
                    lastModifiedEpochMillis = finishedAt ?: startedAt,
                    isDirectory = false,
                    mimeType = storedItem.mimeType,
                    destinationUri = storedItem.destinationUri,
                    verification = storedItem.verification?.let {
                        runCatching { VerificationResult.valueOf(it) }.getOrNull()
                    },
                )
            }
        } else {
            itemPaths.mapIndexed { index, path ->
                TransferItem(
                    index = index,
                    relativePath = path,
                    sizeBytes = 0,
                    lastModifiedEpochMillis = 0,
                    isDirectory = false,
                    destinationUri = destinationUri,
                )
            }
        }
        return Transfer(
            id = id,
            peerDeviceId = peer,
            peerDisplayName = peerDisplayName,
            direction = runCatching { TransferDirection.valueOf(direction) }
                .getOrDefault(TransferDirection.RECEIVE),
            items = domainItems,
            totalBytes = totalBytes,
            state = runCatching { TransferState.valueOf(state) }
                .getOrDefault(TransferState.FAILED),
            startedAtEpochMillis = startedAt,
            finishedAtEpochMillis = finishedAt,
            destinationUri = destinationUri,
        )
    }
}
