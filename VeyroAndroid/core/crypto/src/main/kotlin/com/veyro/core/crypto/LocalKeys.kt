package com.veyro.core.crypto

/**
 * Domain strings for keys derived from the identity seed via [IdentityManager.deriveLocalKey].
 *
 * Collected in one place so that two components cannot accidentally share a domain. Each string is
 * part of the on-disk contract: changing one makes everything previously sealed under it
 * unreadable, which for the trust store means "every device must be paired again".
 *
 * Append only. Never reuse or repurpose a string.
 */
public object LocalKeys {

    /** HMAC key authenticating the trust store file. */
    public const val TRUST_STORE_MAC: String = "veyro-truststore-mac-v1"

    /** Reserved for the transfer-history file if it ever needs authenticating. */
    public const val HISTORY_MAC: String = "veyro-history-mac-v1"

    /**
     * The X25519 static key used by the Noise handshake.
     *
     * Derived from the identity seed rather than being the seed itself, so that `IdentityManager`
     * can hand the handshake a usable key without ever exposing the seed - which is what keeps
     * "the private key never leaves the device" (REQ-I02) an enforceable property rather than a
     * convention.
     *
     * **This string is part of the wire format.** The public key published in the presence record
     * and committed to by `deviceId` must be the public half of *this* derived key, not of the raw
     * seed. Changing the string changes every device''s identity.
     */
    public const val NOISE_STATIC: String = "veyro-noise-static-v1"
}