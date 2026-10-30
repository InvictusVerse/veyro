package com.veyro.core.data

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Platform
import com.veyro.core.model.TrustedPeer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.io.File

/**
 * CBOR-on-disk [TrustStore] (REQ-I08, REQ-I09, ADR-006).
 *
 * ## Why not Room
 *
 * The dataset is tens of records with no queries. Room would add KSP and Dagger-style codegen to
 * the build for a list that fits in memory. The interface keeps the decision reversible: if history
 * or peers ever need real queries, swap the implementation, not the callers.
 *
 * ## Why CBOR and not JSON
 *
 * Every record holds two raw 32-byte values. JSON can only express those as arrays of integers,
 * which is roughly four times the bytes and much slower to parse. CBOR has a native byte-string
 * type.
 *
 * ## Corruption policy
 *
 * A trust file that fails its HMAC is treated as **empty**, and the failure is surfaced through
 * [lastLoadError] rather than swallowed. That choice is deliberate and worth stating:
 *
 *  - Continuing with a partially-parsed list could silently *drop* a trusted device (annoying) or,
 *    if an attacker controlled the bytes, silently *add* one (dangerous). The HMAC exists to make
 *    the second impossible; treating failure as empty makes the first the only outcome.
 *  - Failing closed means the user must re-pair. That is recoverable and obvious. Failing open is
 *    neither.
 */
@OptIn(ExperimentalSerializationApi::class)
public class FileTrustStore(
    directory: File,
    macKey: ByteArray,
    private val dispatchers: AppDispatchers,
    private val clock: () -> Long = System::currentTimeMillis,
) : TrustStore {

    public companion object {
        private const val TAG = "TrustStore"
        private const val FILE_NAME = "trust.cbor"

        /** Devices removed here whose `unpair` is still owed (ADR-057). A file of its own. */
        private const val REMOVED_FILE_NAME = "removed.cbor"

        /** How long a removed device's secret is kept to tell it (ADR-057). */
        public const val PENDING_UNPAIR_KEEP_MS: Long = 90L * 24 * 60 * 60 * 1000

        /** At most this many owed notices; the oldest go first. */
        internal const val MAX_PENDING_UNPAIRS = 32
    }

    @Serializable
    private data class StoredRemoved(
        val deviceId: String,
        val pairingSecret: ByteArray,
        val removedAt: Long,
    )

    @Serializable
    private data class StoredRemovedList(
        val version: Int = 1,
        val removed: List<StoredRemoved> = emptyList(),
    )

    @Serializable
    private data class StoredPeer(
        val deviceId: String,
        val staticPublicKey: ByteArray,
        val pairingSecret: ByteArray,
        val name: String,
        val platform: String,
        val pairedAt: Long,
        val lastConnected: Long = 0L,
        val autoAccept: Boolean = false,
        val revoked: Boolean = false,
    )

    @Serializable
    private data class StoredTrust(
        val version: Int = 1,
        val peers: List<StoredPeer> = emptyList(),
    )

    private val file = AuthenticatedFile(File(directory, FILE_NAME), macKey)

    // Same key and the same tamper check as the trust file: these secrets are pairing secrets.
    private val removedFile = AuthenticatedFile(File(directory, REMOVED_FILE_NAME), macKey)
    private var removed: MutableList<StoredRemoved>? = null
    private val cbor = Cbor { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val state = MutableStateFlow<List<TrustedPeer>>(emptyList())
    private var loaded = false

    /** Non-null when the last load failed authentication or parsing. Surfaced in the UI. */
    private val _lastLoadError = MutableStateFlow<String?>(null)
    public val lastLoadError: Flow<String?> = _lastLoadError.asStateFlow()

    override val peers: Flow<List<TrustedPeer>> = state.asStateFlow()

    override suspend fun snapshot(): List<TrustedPeer> = mutex.withLock { loadIfNeeded() }

    override suspend fun find(deviceId: DeviceId): TrustedPeer? =
        mutex.withLock { loadIfNeeded().firstOrNull { it.deviceId == deviceId } }

    override suspend fun put(peer: TrustedPeer) = mutex.withLock {
        require(peer.isSelfConsistent()) {
            // A record whose deviceId is not the commitment to its key would let a caller insert a
            // peer under a name that does not match its key - the exact confusion deviceId exists
            // to prevent.
            "refusing to store a peer whose deviceId does not match its public key"
        }
        val current = loadIfNeeded()
        persist(current.filterNot { it.deviceId == peer.deviceId } + peer)
        // Paired again: the old removal no longer stands, and its secret is no longer the key.
        dropRemoved(peer.deviceId)
    }

    override suspend fun removeAndNotify(deviceId: DeviceId): Unit = mutex.withLock {
        val current = loadIfNeeded()
        val peer = current.firstOrNull { it.deviceId == deviceId } ?: return@withLock
        val list = loadRemoved()
        list.removeAll { it.deviceId == deviceId.value }
        list += StoredRemoved(deviceId.value, peer.pairingSecret.copyOf(), clock())
        while (list.size > MAX_PENDING_UNPAIRS) list.removeAt(0)
        persistRemoved(list)
        persist(current.filterNot { it.deviceId == deviceId })
    }

    override suspend fun pendingUnpairSecret(deviceId: DeviceId): ByteArray? = mutex.withLock {
        loadRemoved().firstOrNull { it.deviceId == deviceId.value }?.pairingSecret?.copyOf()
    }

    override suspend fun clearPendingUnpair(deviceId: DeviceId): Unit = mutex.withLock {
        dropRemoved(deviceId)
    }

    override suspend fun remove(deviceId: DeviceId) = mutex.withLock {
        val current = loadIfNeeded()
        if (current.none { it.deviceId == deviceId }) return@withLock
        persist(current.filterNot { it.deviceId == deviceId })
    }

    override suspend fun setAutoAccept(deviceId: DeviceId, autoAccept: Boolean) = mutex.withLock {
        val current = loadIfNeeded()
        persist(
            current.map {
                if (it.deviceId == deviceId) it.copy(autoAcceptTransfers = autoAccept) else it
            },
        )
    }

    override suspend fun markConnected(deviceId: DeviceId, atMillis: Long) = mutex.withLock {
        val current = loadIfNeeded()
        // Deliberately not persisted on every connection: this is a cosmetic "last seen" value,
        // and rewriting the whole trust file on each handshake is unnecessary write amplification.
        // It is held in memory and flushed by the next real mutation.
        state.value = current.map {
            if (it.deviceId == deviceId) it.copy(lastConnectedEpochMillis = atMillis) else it
        }
    }

    /** Forces a reload. Used after an identity reset, which invalidates every record. */
    public suspend fun clear(): Unit = mutex.withLock {
        file.delete()
        removedFile.delete()
        removed = mutableListOf()
        state.value = emptyList()
        loaded = true
        _lastLoadError.value = null
    }

    // ── internals, all under the mutex ─────────────────────────────────────────────────────

    private fun loadIfNeeded(): List<TrustedPeer> {
        if (loaded) return state.value

        val peers = try {
            val payload = file.read()
            if (payload == null) {
                emptyList()
            } else {
                cbor.decodeFromByteArray<StoredTrust>(payload).peers.mapNotNull { it.toDomain() }
            }
        } catch (e: AuthenticatedFile.CorruptException) {
            // Fail closed. See the class note on corruption policy.
            VeyroLog.e(TAG, e) { "Trust store failed authentication; treating it as empty" }
            _lastLoadError.value =
                "The paired-device list could not be verified and was not loaded. " +
                    "Your devices need to be paired again."
            emptyList()
        } catch (t: Throwable) {
            VeyroLog.e(TAG, t) { "Trust store could not be parsed; treating it as empty" }
            _lastLoadError.value =
                "The paired-device list is unreadable. Your devices need to be paired again."
            emptyList()
        }

        state.value = peers
        loaded = true
        return peers
    }

    private fun persist(peers: List<TrustedPeer>) {
        val stored = StoredTrust(peers = peers.map { it.toStored() })
        file.write(cbor.encodeToByteArray(stored))
        state.value = peers
        _lastLoadError.value = null
    }

    /**
     * Owed notices, expired ones dropped. A file that fails its check or does not parse counts as
     * empty: the cost is only that a removed device is not told (ADR-057), never a wrong trust.
     */
    private fun loadRemoved(): MutableList<StoredRemoved> {
        removed?.let { return it }
        val read = try {
            removedFile.read()?.let { cbor.decodeFromByteArray<StoredRemovedList>(it).removed }.orEmpty()
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "Removed-device list unreadable; treating it as empty (${t.javaClass.simpleName})" }
            emptyList()
        }
        val now = clock()
        val live = read.filter {
            it.pairingSecret.size == 32 && DeviceId.parseOrNull(it.deviceId) != null &&
                now - it.removedAt in 0..PENDING_UNPAIR_KEEP_MS
        }.toMutableList()
        removed = live
        if (live.size != read.size) persistRemoved(live)
        return live
    }

    private fun persistRemoved(list: MutableList<StoredRemoved>) {
        if (list.isEmpty()) removedFile.delete() else removedFile.write(cbor.encodeToByteArray(StoredRemovedList(removed = list)))
        removed = list
    }

    private fun dropRemoved(deviceId: DeviceId) {
        val list = loadRemoved()
        if (list.removeAll { it.deviceId == deviceId.value }) persistRemoved(list)
    }

    private fun TrustedPeer.toStored() = StoredPeer(
        deviceId = deviceId.value,
        staticPublicKey = staticPublicKey,
        pairingSecret = pairingSecret,
        name = name,
        platform = platform.toWire(),
        pairedAt = pairedAtEpochMillis,
        lastConnected = lastConnectedEpochMillis,
        autoAccept = autoAcceptTransfers,
        revoked = revoked,
    )

    /**
     * Converts a stored record, dropping anything structurally invalid.
     *
     * Returning null rather than throwing means one bad record cannot take out the whole list. The
     * self-consistency check matters: a record whose deviceId does not commit to its key is either
     * corrupt or forged, and either way must not be presented as a trusted device.
     */
    private fun StoredPeer.toDomain(): TrustedPeer? {
        val id = DeviceId.parseOrNull(deviceId) ?: run {
            VeyroLog.w(TAG) { "Dropping a trust record with an unparsable deviceId" }
            return null
        }
        if (staticPublicKey.size != 32 || pairingSecret.size != 32) {
            VeyroLog.w(TAG) { "Dropping trust record ${id.short}: wrong key size" }
            return null
        }
        if (!DeviceId.matches(id, staticPublicKey)) {
            VeyroLog.w(TAG) { "Dropping trust record ${id.short}: id does not match its key" }
            return null
        }
        return TrustedPeer(
            deviceId = id,
            staticPublicKey = staticPublicKey,
            pairingSecret = pairingSecret,
            name = name,
            platform = Platform.fromWire(platform),
            pairedAtEpochMillis = pairedAt,
            lastConnectedEpochMillis = lastConnected,
            autoAcceptTransfers = autoAccept,
            revoked = revoked,
        )
    }

    /** Runs a blocking load off the caller's thread. Used by the repository layer at startup. */
    public suspend fun warmUp(): List<TrustedPeer> = withContext(dispatchers.io) { snapshot() }
}
