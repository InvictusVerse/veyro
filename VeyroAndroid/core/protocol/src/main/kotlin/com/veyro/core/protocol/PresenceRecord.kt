package com.veyro.core.protocol

import com.veyro.core.model.Capabilities
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Platform
import com.veyro.core.model.encoding.hexToBytesOrNull
import com.veyro.core.model.encoding.toHex

/**
 * The presence record carried by both discovery mechanisms (04-PROTOCOL.md section 3).
 *
 * **Everything in this type is attacker-controlled.** It answers one question only: *which IP:port
 * might be a Veyro peer?* It carries no authority whatsoever. An attacker can broadcast a record
 * claiming your PC's `deviceId` all day; the handshake then fails because they lack the private
 * key (REQ-D07).
 *
 * ## One encoding for both transports
 *
 * mDNS TXT records are natively a list of `key=value` byte strings, so that codec is needed
 * regardless. Using the same `key=value` form for the UDP beacon payload - rather than the CBOR
 * the protocol draft originally specified - means **one** encoding to implement, test and mirror on
 * Windows instead of two representations of the same record. It is also readable in a packet
 * capture, which matters when diagnosing a hotspot that silently drops traffic.
 *
 * Short keys because mDNS packets are small and the TXT record is re-sent on every response.
 */
public data class PresenceRecord(
    val version: Int,
    val deviceId: DeviceId,
    val platform: Platform,
    val capabilities: Capabilities,
    /** `SHA-256("veyro-pk-v1" ‖ staticPublic)[0..7]`. Lets a browser pre-filter cheaply. */
    val publicKeyHash: ByteArray,
    /** Rotating presence token (section 3.4). 8 bytes. */
    val presenceToken: ByteArray,
    /** Display name. **Absent in private mode** so a stranger cannot harvest it (REQ-D06). */
    val name: String?,
    /** TCP port the peer is listening on. Ephemeral and advertised, never fixed. */
    val port: Int,
    /**
     * The full X25519 static public key, base64url.
     *
     * This is what makes pairing possible without a camera. `Noise_XK` needs the responder's static
     * key before the handshake starts, and the original design got it from a scanned QR code. A TXT
     * attribute carries the same 43 characters and needs no permission, no camera and no library.
     *
     * Publishing it is safe: a public key is public, and knowing it does not let anyone impersonate
     * the device - that needs the private half. What it does *not* do is authenticate it, which is
     * exactly why the 6-digit SAS confirmation exists. An attacker who rewrites this attribute to
     * their own key produces a different transcript, so the two screens show different digits and
     * the user sees the mismatch.
     *
     * Absent in private mode: there is no reason to publish it once both sides are paired.
     */
    val publicKey: ByteArray?,
) {
    init {
        require(publicKeyHash.size == HASH_LENGTH) { "publicKeyHash must be $HASH_LENGTH bytes" }
        require(presenceToken.size == TOKEN_LENGTH) { "presenceToken must be $TOKEN_LENGTH bytes" }
        require(port in 1..65535) { "port out of range: $port" }
        publicKey?.let { require(it.size == 32) { "public key must be 32 bytes" } }
    }

    override fun equals(other: Any?): Boolean =
        other is PresenceRecord &&
            version == other.version &&
            deviceId == other.deviceId &&
            platform == other.platform &&
            capabilities.bits == other.capabilities.bits &&
            publicKeyHash.contentEquals(other.publicKeyHash) &&
            presenceToken.contentEquals(other.presenceToken) &&
            name == other.name &&
            port == other.port &&
            (publicKey?.contentEquals(other.publicKey) ?: (other.publicKey == null))

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + deviceId.hashCode()
        r = 31 * r + platform.hashCode()
        r = 31 * r + capabilities.bits
        r = 31 * r + publicKeyHash.contentHashCode()
        r = 31 * r + presenceToken.contentHashCode()
        r = 31 * r + (name?.hashCode() ?: 0)
        r = 31 * r + port
        r = 31 * r + (publicKey?.contentHashCode() ?: 0)
        return r
    }

    public companion object {
        public const val HASH_LENGTH: Int = 8
        public const val TOKEN_LENGTH: Int = 8

        // Wire keys. Changing one is a wire-breaking change and requires a VLP_VERSION bump.
        public const val KEY_VERSION: String = "v"
        public const val KEY_DEVICE_ID: String = "id"
        public const val KEY_PLATFORM: String = "pf"
        public const val KEY_CAPABILITIES: String = "cap"
        public const val KEY_PUBKEY_HASH: String = "pk8"
        public const val KEY_TOKEN: String = "tok"
        public const val KEY_NAME: String = "n"
        public const val KEY_PORT: String = "p"
        public const val KEY_PUBLIC_KEY: String = "pk"
    }
}

/**
 * Encodes and decodes [PresenceRecord] as `key=value` pairs.
 *
 * The decoder is a parser for hostile input and is written that way: every field is bounds-checked,
 * unknown keys are ignored (forward compatibility, section 11), duplicate keys are rejected, and a
 * malformed record yields a typed failure rather than a partially-populated object.
 */
public object PresenceCodec {

    /** Mirrors the mDNS TXT record size reality; also caps a UDP beacon payload. */
    public const val MAX_ENCODED_BYTES: Int = 400

    public sealed interface Result {
        public data class Ok(val record: PresenceRecord) : Result
        public data class Invalid(val reason: Reason, val detail: String) : Result
    }

    public enum class Reason {
        EMPTY,
        TOO_LARGE,
        MALFORMED_PAIR,
        DUPLICATE_KEY,
        MISSING_REQUIRED,
        BAD_VERSION,
        BAD_DEVICE_ID,
        BAD_CAPABILITIES,
        BAD_HASH,
        BAD_TOKEN,
        BAD_PORT,
        NAME_TOO_LONG,
        NAME_INVALID,
        BAD_PUBLIC_KEY,
    }

    /** The `key=value` pairs, suitable for mDNS TXT attributes. */
    public fun encodeToPairs(record: PresenceRecord): List<Pair<String, String>> = buildList {
        add(PresenceRecord.KEY_VERSION to record.version.toString())
        add(PresenceRecord.KEY_DEVICE_ID to record.deviceId.value)
        add(PresenceRecord.KEY_PLATFORM to record.platform.toWire())
        add(PresenceRecord.KEY_CAPABILITIES to record.capabilities.bits.toString(16))
        add(PresenceRecord.KEY_PUBKEY_HASH to record.publicKeyHash.toHex())
        add(PresenceRecord.KEY_TOKEN to record.presenceToken.toHex())
        add(PresenceRecord.KEY_PORT to record.port.toString())
        // Omitted entirely in private mode, not sent empty: an empty value still tells an observer
        // that a name exists and is being withheld.
        record.name?.let { add(PresenceRecord.KEY_NAME to it) }
        record.publicKey?.let { add(PresenceRecord.KEY_PUBLIC_KEY to Base64Url.encode(it)) }
    }

    /** Newline-separated form, used as the UDP beacon payload. */
    public fun encodeToBytes(record: PresenceRecord): ByteArray =
        encodeToPairs(record)
            .joinToString("\n") { (k, v) -> "$k=$v" }
            .toByteArray(Charsets.UTF_8)

    public fun decodeFromBytes(bytes: ByteArray): Result {
        if (bytes.isEmpty()) return Result.Invalid(Reason.EMPTY, "no bytes")
        if (bytes.size > MAX_ENCODED_BYTES) {
            return Result.Invalid(Reason.TOO_LARGE, "${bytes.size} bytes")
        }
        val text = try {
            bytes.toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            return Result.Invalid(Reason.MALFORMED_PAIR, "not valid UTF-8")
        }
        val pairs = mutableListOf<Pair<String, String>>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) return Result.Invalid(Reason.MALFORMED_PAIR, "no '=' in a pair")
            pairs += line.substring(0, eq) to line.substring(eq + 1)
        }
        return decodeFromPairs(pairs)
    }

    public fun decodeFromPairs(pairs: List<Pair<String, String>>): Result {
        if (pairs.isEmpty()) return Result.Invalid(Reason.EMPTY, "no pairs")

        val map = HashMap<String, String>(pairs.size)
        for ((key, value) in pairs) {
            // A duplicate key means two conflicting claims. Picking either silently would let an
            // attacker get a different reading on the two platforms.
            if (map.put(key, value) != null) {
                return Result.Invalid(Reason.DUPLICATE_KEY, key)
            }
        }

        val version = map[PresenceRecord.KEY_VERSION]?.toIntOrNull()
            ?: return Result.Invalid(Reason.MISSING_REQUIRED, PresenceRecord.KEY_VERSION)
        if (version != Vlp.VERSION) {
            // Refuse rather than guess (REQ-S08). Note this is not an error to show the user for
            // every stray packet - it just means "not a peer we can talk to".
            return Result.Invalid(Reason.BAD_VERSION, "version $version")
        }

        val deviceId = DeviceId.parseOrNull(map[PresenceRecord.KEY_DEVICE_ID])
            ?: return Result.Invalid(Reason.BAD_DEVICE_ID, map[PresenceRecord.KEY_DEVICE_ID] ?: "absent")

        val capsRaw = map[PresenceRecord.KEY_CAPABILITIES]
            ?: return Result.Invalid(Reason.MISSING_REQUIRED, PresenceRecord.KEY_CAPABILITIES)
        val caps = capsRaw.toIntOrNull(16)
            ?: return Result.Invalid(Reason.BAD_CAPABILITIES, capsRaw)

        val hash = map[PresenceRecord.KEY_PUBKEY_HASH]?.hexToBytesOrNull()
            ?.takeIf { it.size == PresenceRecord.HASH_LENGTH }
            ?: return Result.Invalid(Reason.BAD_HASH, map[PresenceRecord.KEY_PUBKEY_HASH] ?: "absent")

        val token = map[PresenceRecord.KEY_TOKEN]?.hexToBytesOrNull()
            ?.takeIf { it.size == PresenceRecord.TOKEN_LENGTH }
            ?: return Result.Invalid(Reason.BAD_TOKEN, map[PresenceRecord.KEY_TOKEN] ?: "absent")

        val portRaw = map[PresenceRecord.KEY_PORT]
            ?: return Result.Invalid(Reason.MISSING_REQUIRED, PresenceRecord.KEY_PORT)
        val port = portRaw.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return Result.Invalid(Reason.BAD_PORT, portRaw)

        val publicKey = map[PresenceRecord.KEY_PUBLIC_KEY]?.let { encoded ->
            Base64Url.decodeOrNull(encoded)?.takeIf { it.size == 32 }
                ?: return Result.Invalid(Reason.BAD_PUBLIC_KEY, "not a 32-byte key")
        }

        val name = map[PresenceRecord.KEY_NAME]
        if (name != null) {
            if (name.toByteArray(Charsets.UTF_8).size > Vlp.MAX_DISPLAY_NAME_BYTES) {
                return Result.Invalid(Reason.NAME_TOO_LONG, "${name.length} chars")
            }
            // Control characters in a name that gets rendered in a list is a UI-spoofing vector:
            // a newline or a bidi override can make a hostile device look like a trusted one.
            if (name.any { it.code < 0x20 || it.code == 0x7F || it in BIDI_OVERRIDES }) {
                return Result.Invalid(Reason.NAME_INVALID, "control or bidi character")
            }
        }

        return Result.Ok(
            PresenceRecord(
                version = version,
                deviceId = deviceId,
                platform = Platform.fromWire(map[PresenceRecord.KEY_PLATFORM]),
                capabilities = Capabilities(caps),
                publicKeyHash = hash,
                presenceToken = token,
                name = name,
                port = port,
                publicKey = publicKey,
            ),
        )
    }

    /**
     * Unicode bidirectional overrides.
     *
     * These reorder rendered text, so a device could advertise a name that *displays* as a trusted
     * one while being something else. Cheap to reject, and REQ-U04 is about devices being
     * distinguishable.
     */
    private val BIDI_OVERRIDES = charArrayOf(
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069',
    )
}
