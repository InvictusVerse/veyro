package com.veyro.core.crypto

import com.veyro.core.model.DeviceId

/**
 * The persisted identity record: the seed, plus the public facts needed to detect corruption.
 *
 * Keeping [deviceId] and [createdAtEpochMillis] alongside the seed is not redundant bookkeeping -
 * it is what makes two failure modes detectable that would otherwise be silent:
 *
 *  - A seed that no longer derives [deviceId] means the stored bytes were corrupted or swapped.
 *    Without the recorded id, that would simply look like a *different* device, and the app would
 *    quietly present a new fingerprint while the user believes they are looking at their old one.
 *  - [createdAtEpochMillis] would otherwise be recomputed as "now" on every process start, so the
 *    "paired since" date would drift forward each launch.
 */
public data class IdentityRecord(
    val seed: ByteArray,
    val deviceId: DeviceId,
    val createdAtEpochMillis: Long,
) {
    init {
        require(seed.size == 32) { "identity seed must be 32 bytes, was ${seed.size}" }
    }

    override fun equals(other: Any?): Boolean =
        other is IdentityRecord &&
            seed.contentEquals(other.seed) &&
            deviceId == other.deviceId &&
            createdAtEpochMillis == other.createdAtEpochMillis

    override fun hashCode(): Int =
        31 * (31 * seed.contentHashCode() + deviceId.hashCode()) + createdAtEpochMillis.hashCode()

    /** Never let the seed reach a log. */
    override fun toString(): String =
        "IdentityRecord(deviceId=$deviceId, createdAt=$createdAtEpochMillis, seed=<redacted>)"
}

/**
 * Where the identity physically lives.
 *
 * Split from [IdentityManager] so the identity logic - generate, persist, verify, reset - is
 * testable on the JVM with a fake store, while the Android implementation owns the one thing that
 * genuinely needs a device: the hardware-backed Keystore key.
 *
 * Implementations MUST NOT log the seed, and MUST authenticate what they store so that tampering is
 * detected rather than producing a different identity (the Android implementation uses
 * AES-256-GCM, whose tag provides exactly that).
 */
public interface SeedStore {

    /** Reads the stored record, or null if this is a first run. */
    public fun read(): IdentityRecord?

    /** Seals and persists [record], replacing any existing one. */
    public fun write(record: IdentityRecord)

    /** Destroys the record *and* the key that seals it, so it cannot be recovered. */
    public fun destroy()
}

/**
 * Thrown when the identity cannot be used and must be recreated by the user.
 *
 * A distinct type because the response differs from any other failure: tell the user the identity
 * was lost and that every pairing must be redone. Retrying or silently minting a new identity
 * would both be wrong. This is the planned recovery path for `G1` in `05-SECURITY.md` section 7.
 */
public class IdentityUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * In-memory [SeedStore]. Test-only, but it lives in `main` so JVM tests of the identity logic need
 * no Robolectric and no emulator. It is explicitly not a production store: nothing is sealed and
 * nothing survives the process.
 */
public class InMemorySeedStore(initial: IdentityRecord? = null) : SeedStore {
    private var record: IdentityRecord? = initial

    override fun read(): IdentityRecord? = record

    override fun write(record: IdentityRecord) {
        this.record = record
    }

    override fun destroy() {
        record?.seed?.fill(0)
        record = null
    }
}
