package com.veyro.core.crypto

import com.veyro.core.model.DeviceId

/**
 * Owns this installation's long-term identity (REQ-I01 .. REQ-I04).
 *
 * The private key is generated once, sealed by a hardware-backed Android Keystore key, and never
 * leaves the device. Note that this interface deliberately exposes no way to read the private
 * key: callers can only ask for operations that use it. That is what keeps "the private key
 * never leaves the device" an enforceable property rather than a convention.
 */
public interface IdentityManager {

    /** Generates the identity on first use. Idempotent. */
    public suspend fun ensureIdentity(): Identity

    /** The current identity, or null if one has not been loaded yet. Never generates. */
    public suspend fun currentIdentity(): Identity?

    /**
     * Raw X25519 Diffie-Hellman against a peer's static public key.
     * Used by the handshake; nothing else should call it.
     */
    public suspend fun agree(peerPublicKey: ByteArray): ByteArray

    /**
     * `HKDF-SHA256(ikm = X25519(identityPrivate, peerPublicKey), salt, info, length)`.
     *
     * The raw Diffie-Hellman output is wiped before this returns, so callers only ever hold the
     * derived key. Confirm-code pairing (04-PROTOCOL.md section 4.3) derives its bootstrap key
     * this way.
     *
     * @throws IllegalArgumentException for a key of the wrong size or a low-order point.
     */
    public suspend fun deriveSharedKey(
        peerPublicKey: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 32,
    ): ByteArray

    /**
     * Derives a local-use key from the identity seed, domain-separated by [info].
     *
     * This exists so that other local secrets (the trust-store HMAC key, future at-rest keys) can
     * be bound to the identity **without the seed ever leaving this class**. It is the reason
     * [IdentityManager] has no seed accessor at all.
     *
     * The binding has a useful consequence rather than being merely tidy: after [resetIdentity],
     * every key derived this way changes, so the trust store stops authenticating and is treated
     * as empty. "Reset my identity" therefore genuinely means "all pairings are gone" (REQ-I10),
     * enforced by cryptography instead of by remembering to delete another file.
     *
     * [info] must be a stable, unique string per purpose. Reusing one across purposes would let
     * one component''s key be used in another''s context.
     */
    public suspend fun deriveLocalKey(info: String, length: Int = 32): ByteArray

    /**
     * Destroys the identity and therefore every pairing derived from it (REQ-I10).
     * Irreversible by design: there is no escrow and no backup (05-SECURITY.md section 4.4).
     */
    public suspend fun resetIdentity(): Identity
}

/** The public half of this device'"'"'s identity. The private half never appears in any type. */
public data class Identity(
    val deviceId: DeviceId,
    val staticPublicKey: ByteArray,
    val createdAtEpochMillis: Long,
) {
    init {
        require(staticPublicKey.size == 32) { "X25519 public key must be 32 bytes" }
    }

    override fun equals(other: Any?): Boolean =
        other is Identity &&
            deviceId == other.deviceId &&
            staticPublicKey.contentEquals(other.staticPublicKey) &&
            createdAtEpochMillis == other.createdAtEpochMillis

    override fun hashCode(): Int =
        31 * (31 * deviceId.hashCode() + staticPublicKey.contentHashCode()) +
            createdAtEpochMillis.hashCode()

    override fun toString(): String = "Identity(deviceId=$deviceId)"
}