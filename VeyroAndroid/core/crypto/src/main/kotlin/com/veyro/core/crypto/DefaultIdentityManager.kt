package com.veyro.core.crypto

import com.veyro.core.common.Bytes
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.DeviceId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

/**
 * Default [IdentityManager]: generates the identity once and keeps it for the life of the install.
 *
 * Behaviour that is specified rather than left to chance:
 *
 *  - **Generated once.** [ensureIdentity] is idempotent and safe to call from anywhere. Concurrent
 *    first-run callers must not produce two identities, so it is serialised.
 *  - **Never regenerated implicitly.** If a record exists it is used as-is. A new identity is
 *    created only by [resetIdentity], which is deliberate, destructive and user-initiated. That is
 *    what makes `deviceId` stable across restarts and updates (REQ-I04).
 *  - **Verified on load.** The stored seed must still derive the stored `deviceId`. A mismatch
 *    means corruption or tampering, and raises [IdentityUnavailableException] rather than silently
 *    minting a new identity - because a silent new identity presents as "my PC forgot me", with no
 *    explanation and nothing to distinguish it from a bug.
 *  - **The private key never becomes a return value.** [Identity] carries only the public half.
 *    Only [agree] touches the seed, and only to compute a shared secret.
 *
 * ## Why a Mutex and not `synchronized`
 *
 * The public API is `suspend`, and the Kotlin compiler rejects a suspension point inside a
 * `synchronized` block (`SUSPENSION_POINT_INSIDE_CRITICAL_SECTION`) - correctly, because a thread
 * suspended while holding a JVM monitor can deadlock against a thread waiting for that same
 * monitor. [Mutex] is the coroutine-aware equivalent and is safe to hold across a suspension.
 *
 * The store itself is synchronous, so the critical sections here do no blocking I/O; the Mutex is
 * about excluding concurrent callers, not about waiting on disk.
 */
public class DefaultIdentityManager(
    private val seedStore: SeedStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) : IdentityManager {

    private companion object {
        const val TAG = "Identity"
        const val SEED_SIZE = 32
    }

    private val mutex = Mutex()
    private var cachedPublic: Identity? = null
    private var cachedSeed: ByteArray? = null

    override suspend fun ensureIdentity(): Identity = mutex.withLock {
        cachedPublic?.let { return it }

        val record = readRecordLocked()
        if (record == null) {
            VeyroLog.i(TAG) { "No identity present; generating one" }
            generateLocked()
        } else {
            loadLocked(record)
        }
    }

    override suspend fun currentIdentity(): Identity? = mutex.withLock { cachedPublic }

    override suspend fun agree(peerPublicKey: ByteArray): ByteArray {
        require(peerPublicKey.size == X25519.KEY_SIZE) {
            "peer public key must be ${X25519.KEY_SIZE} bytes"
        }
        val seed = mutex.withLock {
            if (cachedPublic == null) {
                val record = readRecordLocked()
                if (record == null) generateLocked() else loadLocked(record)
            }
            cachedSeed?.copyOf()
        } ?: throw IdentityUnavailableException("No identity is loaded")

        return try {
            X25519.agree(seed, peerPublicKey)
        } finally {
            Bytes.wipe(seed)
        }
    }

    override suspend fun deriveSharedKey(
        peerPublicKey: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 16..64) { "derived key length must be 16..64, was $length" }
        val shared = agree(peerPublicKey)
        return try {
            Hkdf.deriveKey(ikm = shared, salt = salt, info = info, length = length)
        } finally {
            Bytes.wipe(shared)
        }
    }

    override suspend fun deriveLocalKey(info: String, length: Int): ByteArray {
        require(info.isNotBlank()) { "a derived key needs a non-empty domain string" }
        require(length in 16..64) { "derived key length must be 16..64, was $length" }

        val seed = mutex.withLock {
            if (cachedPublic == null) {
                val record = readRecordLocked()
                if (record == null) generateLocked() else loadLocked(record)
            }
            cachedSeed?.copyOf()
        } ?: throw IdentityUnavailableException("No identity is loaded")

        return try {
            // Salt is empty and the domain goes in `info`, matching how the protocol derives its
            // own values (04-PROTOCOL.md section 4.3/4.4) so there is one convention, not two.
            Hkdf.deriveKey(
                ikm = seed,
                salt = ByteArray(0),
                info = info.toByteArray(Charsets.US_ASCII),
                length = length,
            )
        } finally {
            Bytes.wipe(seed)
        }
    }

    override suspend fun resetIdentity(): Identity = mutex.withLock {
        VeyroLog.w(TAG) { "Identity reset requested; every existing pairing becomes invalid" }
        seedStore.destroy()
        cachedSeed?.let { Bytes.wipe(it) }
        cachedSeed = null
        cachedPublic = null
        generateLocked()
    }

    // ── internals. All of these assume the mutex is held. ──────────────────────────────────

    /**
     * Reads the stored record, translating any failure into [IdentityUnavailableException].
     *
     * The realistic Android failure is a `KeyStoreException` or a GCM tag mismatch, because the
     * Keystore key that sealed the seed no longer exists - it is invalidated by a device-credential
     * change or by clearing app data. That is a recoverable state with a defined remedy ("your
     * identity was lost, re-pair your devices"), not a crash, so it must never escape as a raw
     * platform exception.
     */
    private fun readRecordLocked(): IdentityRecord? = try {
        seedStore.read()
    } catch (t: Throwable) {
        VeyroLog.w(TAG, t) { "Identity store could not be read" }
        throw IdentityUnavailableException(
            "The stored identity could not be read. Its sealing key is missing or invalid, " +
                "so the identity must be recreated and every paired device re-paired.",
            t,
        )
    }

    private fun generateLocked(): Identity {
        val seed = X25519.generatePrivateKey(random)
        try {
            require(seed.size == SEED_SIZE) { "seed must be $SEED_SIZE bytes" }
            val publicKey = X25519.publicKeyOf(seed)
            val identity = Identity(
                deviceId = DeviceId.fromPublicKey(publicKey),
                staticPublicKey = publicKey,
                createdAtEpochMillis = clock(),
            )

            // Persist before returning. Handing out an identity that does not survive a restart
            // would let the caller pair with a device that then vanishes.
            seedStore.write(
                IdentityRecord(
                    seed = seed.copyOf(),
                    deviceId = identity.deviceId,
                    createdAtEpochMillis = identity.createdAtEpochMillis,
                ),
            )

            cachedSeed = seed.copyOf()
            cachedPublic = identity
            return identity
        } finally {
            Bytes.wipe(seed)
        }
    }

    private fun loadLocked(record: IdentityRecord): Identity {
        // Verify before trusting. A seed that no longer derives its own recorded deviceId is
        // corrupt or has been swapped; using it would silently present a different device.
        val expectedId = record.deviceId
        val actualId = try {
            DeviceId.fromPublicKey(X25519.publicKeyOf(record.seed))
        } catch (t: Throwable) {
            Bytes.wipe(record.seed)
            throw IdentityUnavailableException("Stored identity is unreadable", t)
        }

        if (actualId != expectedId) {
            Bytes.wipe(record.seed)
            VeyroLog.w(TAG) { "Stored identity does not match its recorded deviceId; refusing it" }
            throw IdentityUnavailableException(
                "Stored identity does not match the device id recorded with it. " +
                    "The identity file is corrupt or has been replaced; a reset is required.",
            )
        }

        val identity = Identity(
            deviceId = expectedId,
            staticPublicKey = X25519.publicKeyOf(record.seed),
            createdAtEpochMillis = record.createdAtEpochMillis,
        )
        cachedSeed = record.seed.copyOf()
        cachedPublic = identity
        return identity
    }
}
