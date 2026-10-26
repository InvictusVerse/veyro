package com.veyro.core.protocol

/**
 * Veyro Link Protocol constants.
 *
 * This file is normative and is mirrored by the Windows client. Every value here also appears in
 * `ProjectInfo/04-PROTOCOL.md`; if you change one, change both, and bump [VLP_VERSION] if it
 * affects bytes on the wire.
 */
public object Vlp {

    /**
     * Wire-format version. A single integer, deliberately: there is no minor version and no
     * feature negotiation beyond the capability bits, so there is nothing to downgrade.
     * Implementations refuse other versions rather than guessing (REQ-S08).
     */
    public const val VERSION: Int = 1

    // ── Discovery ──────────────────────────────────────────────────────────────────────────
    public const val MDNS_SERVICE_TYPE: String = "_veyro._tcp"
    public const val MDNS_DOMAIN: String = "local."
    public const val UDP_DISCOVERY_PORT: Int = 47653
    public const val UDP_MULTICAST_GROUP: String = "239.255.42.99"

    /** Beacon rate while a discovery window is open. Zero packets when it is closed (NFR-02). */
    public const val UDP_BEACON_INTERVAL_MILLIS: Long = 1_000L

    /** `"VEYR"` - the first four bytes of every UDP beacon. */
    public val UDP_MAGIC: ByteArray = byteArrayOf(0x56, 0x45, 0x59, 0x52)

    public const val BEACON_TYPE_QUERY: Byte = 0x01
    public const val BEACON_TYPE_ANNOUNCE: Byte = 0x02

    // ── Secure channel ─────────────────────────────────────────────────────────────────────
    public const val NOISE_PATTERN_SESSION: String = "Noise_KK_25519_ChaChaPoly_SHA256"
    public const val NOISE_PATTERN_PAIRING: String = "Noise_XK_25519_ChaChaPoly_SHA256"

    public const val PROLOGUE_PREFIX: String = "veyro-vlp-v1"
    public const val MODE_SESSION: Byte = 0x01
    public const val MODE_PAIRING: Byte = 0x02

    /** HKDF info strings. Changing any of these is a wire-breaking change. */
    public const val INFO_SAS: String = "veyro-sas-v1"
    public const val INFO_PAIRING_SECRET: String = "veyro-pair-v1"
    public const val INFO_PRESENCE_TOKEN: String = "veyro-presence-v1"

    /** A single Noise transport message carries at most 65535 bytes including the 16-byte tag. */
    public const val MAX_NOISE_MESSAGE: Int = 65_535
    public const val MAX_NOISE_PLAINTEXT: Int = 65_519

    public const val HANDSHAKE_TIMEOUT_MILLIS: Long = 10_000L
    public const val IDLE_SESSION_TIMEOUT_MILLIS: Long = 60_000L
    public const val KEEPALIVE_INTERVAL_MILLIS: Long = 20_000L

    /** How long an inbound pairing window may stay open (04-PROTOCOL.md section 4.6). */
    public const val PAIRING_WINDOW_MILLIS: Long = 120_000L

    // ── Framing ────────────────────────────────────────────────────────────────────────────
    public const val FRAME_CONTROL: Byte = 0x01
    public const val FRAME_BULK: Byte = 0x02
    public const val FRAME_KEEPALIVE: Byte = 0x03

    public const val MAX_CONTROL_FRAME_BYTES: Int = 1 shl 20 // 1 MiB

    // ── Bulk transfer ──────────────────────────────────────────────────────────────────────
    public const val DEFAULT_CHUNK_SIZE: Int = 4 * 1024 * 1024
    public const val MIN_CHUNK_SIZE: Int = 256 * 1024
    public const val MAX_CHUNK_SIZE: Int = 16 * 1024 * 1024

    public const val PART_SUFFIX: String = ".veyropart"
    public const val META_SUFFIX: String = ".veyrometa"

    // ── Limits ─────────────────────────────────────────────────────────────────────────────
    public const val MAX_DISPLAY_NAME_BYTES: Int = 32
    public const val MAX_RELATIVE_PATH_BYTES: Int = 1024

    /** Picks the chunk size both peers can handle. */
    public fun negotiateChunkSize(ours: Int, theirs: Int): Int =
        minOf(ours, theirs).coerceIn(MIN_CHUNK_SIZE, MAX_CHUNK_SIZE)
}

/**
 * Protocol-level error codes (04-PROTOCOL.md section 9).
 *
 * `fatal` means the session must be closed. Non-fatal errors fail one item and let the rest of
 * the transfer continue, which matters when one file in a 2000-file folder has a bad name.
 */
public enum class VlpError(public val code: String, public val fatal: Boolean) {
    VERSION_UNSUPPORTED("VERSION_UNSUPPORTED", fatal = true),
    NOT_PAIRED("NOT_PAIRED", fatal = true),
    HANDSHAKE_FAILED("HANDSHAKE_FAILED", fatal = true),
    AUTH_TIMEOUT("AUTH_TIMEOUT", fatal = true),
    FRAME_INVALID("FRAME_INVALID", fatal = true),
    OFFER_REJECTED("OFFER_REJECTED", fatal = false),
    PATH_REJECTED("PATH_REJECTED", fatal = false),
    HASH_MISMATCH("HASH_MISMATCH", fatal = false),
    NO_SPACE("NO_SPACE", fatal = false),
    CANCELLED("CANCELLED", fatal = false),
    INTERNAL("INTERNAL", fatal = false),
    ;

    public companion object {
        public fun fromWire(code: String?): VlpError =
            entries.firstOrNull { it.code == code } ?: INTERNAL
    }
}
