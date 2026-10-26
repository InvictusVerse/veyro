package com.veyro.core.model

import kotlinx.serialization.Serializable

/**
 * A device the user has paired with. This is the authority for "may I talk to you".
 *
 * Persisted in app-private storage, authenticated with an HMAC so that an attacker who can write
 * to the filesystem cannot silently insert themselves as trusted
 * (ProjectInfo/05-SECURITY.md section 4.4).
 */
@Serializable
public data class TrustedPeer(
    val deviceId: DeviceId,

    /** The peer's 32-byte X25519 static public key. This is what authenticates every session. */
    val staticPublicKey: ByteArray,

    /**
     * Derived from the pairing handshake transcript. Used for rotating presence tokens so paired
     * devices can recognise each other without broadcasting a stable identifier (section 3.4).
     *
     * It is NOT an authentication credential - authentication always comes from
     * [staticPublicKey]. Treating this as a second way in would be a downgrade path.
     */
    val pairingSecret: ByteArray,

    val name: String,
    val platform: Platform,
    val pairedAtEpochMillis: Long,
    val lastConnectedEpochMillis: Long = 0L,

    /**
     * Skip the per-transfer prompt for this device.
     *
     * Per-device, default false, and there is deliberately no global equivalent (REQ-T03).
     * Cleared on revoke-and-re-pair so that trust is never silently inherited.
     */
    val autoAcceptTransfers: Boolean = false,

    val revoked: Boolean = false,
) {
    init {
        require(staticPublicKey.size == 32) { "static public key must be 32 bytes" }
        require(pairingSecret.size == 32) { "pairing secret must be 32 bytes" }
    }

    /** Guards against a trust record whose id does not actually commit to its key. */
    public fun isSelfConsistent(): Boolean = DeviceId.matches(deviceId, staticPublicKey)

    // ByteArray in a data class needs structural equals/hashCode, or two records with identical
    // keys compare unequal and the trust store grows duplicates.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedPeer) return false
        return deviceId == other.deviceId &&
            staticPublicKey.contentEquals(other.staticPublicKey) &&
            pairingSecret.contentEquals(other.pairingSecret) &&
            name == other.name &&
            platform == other.platform &&
            pairedAtEpochMillis == other.pairedAtEpochMillis &&
            lastConnectedEpochMillis == other.lastConnectedEpochMillis &&
            autoAcceptTransfers == other.autoAcceptTransfers &&
            revoked == other.revoked
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + staticPublicKey.contentHashCode()
        result = 31 * result + pairingSecret.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + platform.hashCode()
        result = 31 * result + pairedAtEpochMillis.hashCode()
        result = 31 * result + lastConnectedEpochMillis.hashCode()
        result = 31 * result + autoAcceptTransfers.hashCode()
        result = 31 * result + revoked.hashCode()
        return result
    }

    /** Never let a key or secret reach a log (AGENTS.md). */
    override fun toString(): String =
        "TrustedPeer(deviceId=$deviceId, name=$name, platform=$platform, " +
            "autoAccept=$autoAcceptTransfers, revoked=$revoked)"
}
