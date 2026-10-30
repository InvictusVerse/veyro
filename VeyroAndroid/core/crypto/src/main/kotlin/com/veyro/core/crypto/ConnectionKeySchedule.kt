package com.veyro.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Which key schedule a connection runs (04-PROTOCOL.md 4, ADR-058). */
public enum class LinkMode { SESSION, PAIR_CONFIRM, PAIR_QR }

/** The dialler is the initiator; the side that answered the preamble is the responder. */
public enum class LinkRole { INITIATOR, RESPONDER }

/**
 * A per-connection X25519 key pair. Generated for one connection, never stored, never reused.
 * The private half never leaves this object; [wipe] zeroes it.
 */
public class EphemeralKey internal constructor(private val privateKey: ByteArray) {

    /** The public half (`epk` on the wire). */
    public val publicKey: ByteArray = X25519.publicKeyOf(privateKey)

    @Volatile
    private var wiped = false

    /** `X25519(private, peerEpk)`. Throws [IllegalArgumentException] for a low-order point. */
    public fun agree(peerEpk: ByteArray): ByteArray {
        check(!wiped) { "ephemeral key already wiped" }
        return X25519.agree(privateKey, peerEpk)
    }

    public fun wipe() {
        wiped = true
        privateKey.fill(0)
    }

    override fun toString(): String = "EphemeralKey(<redacted>)"

    public companion object {
        private val random = SecureRandom()

        public fun generate(): EphemeralKey = EphemeralKey(X25519.generatePrivateKey(random))
    }
}

/** A fixed ephemeral key, for the test vectors only. */
internal fun EphemeralKey.Companion.fromPrivate(scalar: ByteArray): EphemeralKey {
    require(scalar.size == X25519.KEY_SIZE) { "X25519 scalar must be 32 bytes" }
    return EphemeralKey(scalar.copyOf())
}

/**
 * The keys of one connection in one phase (bootstrap or session), from this side's point of view.
 *
 * [sendKey]/[recvKey] are the direction keys this side seals and opens with. [chainKey] exists
 * only in pairing modes and feeds [ConnectionKeySchedule.afterPairing]; [expSecret] only in
 * session mode and feeds the exporter. Never logged: [toString] is redacted.
 */
public class LinkKeys internal constructor(
    public val mode: LinkMode,
    public val role: LinkRole,
    public val localId: String,
    public val peerId: String,
    public val initiatorId: String,
    public val responderId: String,
    public val transcript: ByteArray,
    public val sendKey: ByteArray,
    public val recvKey: ByteArray,
    public val chainKey: ByteArray?,
    public val expSecret: ByteArray?,
) {
    /** Zeroes every key this object holds (best effort on the JVM). */
    public fun wipe() {
        sendKey.fill(0)
        recvKey.fill(0)
        chainKey?.fill(0)
        expSecret?.fill(0)
    }

    override fun toString(): String = "LinkKeys(mode=$mode, role=$role, <redacted>)"
}

/**
 * The v3 connection key schedule (04-PROTOCOL.md 4.1-4.6, ADR-058). Pure functions over fixed
 * primitives: SHA-256, HKDF-SHA256 (RFC 5869), HMAC-SHA256. Android and Windows compute these
 * byte for byte; the `-v3.json` files under `protocol/testvectors/link` pin them.
 *
 * Inputs are not wiped here (the vectors reuse them); `ikm` and `prk` are. Callers wipe `dh`,
 * `staticKey` and `qrMix` once the keys exist.
 */
public object ConnectionKeySchedule {

    public const val LABEL_SESSION: String = "veyro-link-v3"
    public const val LABEL_PAIR_CONFIRM: String = "veyro-pair-confirm-v3"
    public const val LABEL_PAIR_QR: String = "veyro-pair-qr-v3"
    public const val LABEL_STATIC: String = "veyro-static-v3"
    public const val LABEL_EXPORT: String = "veyro-export-v3"
    public const val LABEL_QR_SALT: String = "veyro-qr-v3"
    public const val LABEL_QR_PROOF: String = "veyro-qr-proof-v3"
    public const val LABEL_QR_MIX: String = "veyro-qr-mix-v3"

    public const val ROLE_I2R: String = "i2r"
    public const val ROLE_R2I: String = "r2i"
    public const val ROLE_EXP: String = "exp"
    public const val ROLE_CHAIN: String = "chain"

    public const val KEY_SIZE: Int = 32
    public const val MAX_EXPORT_LENGTH: Int = 8160
    public const val MAX_EXPORT_LABEL: Int = 64
    public const val MAX_EXPORT_CONTEXT: Int = 1024

    /** `SHA-256(u32be(len P) || P || u32be(len A) || A)` over the exact preamble and ack bytes. */
    public fun transcript(preamble: ByteArray, ack: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(u32be(preamble.size))
            update(preamble)
            update(u32be(ack.size))
            update(ack)
            digest()
        }

    /** `utf8("veyro-static-v3") || 0x00 || utf8(idI) || 0x00 || utf8(idR)`. */
    public fun staticInfo(idI: String, idR: String): ByteArray =
        joined(LABEL_STATIC, idI, idR)

    /** `utf8(label) || 0x00 || utf8(role) || 0x00 || utf8(idI) || 0x00 || utf8(idR)`. */
    public fun info(label: String, role: String, idI: String, idR: String): ByteArray =
        joined(label, role, idI, idR)

    /** Session mode: `ikm = dh || pairingSecret`, salt = transcript. */
    public fun session(
        role: LinkRole,
        idI: String,
        idR: String,
        transcript: ByteArray,
        dh: ByteArray,
        pairingSecret: ByteArray,
    ): LinkKeys {
        requireSize(dh, "dh")
        requireSize(pairingSecret, "pairingSecret")
        val ikm = dh + pairingSecret
        return derive(LinkMode.SESSION, LABEL_SESSION, role, idI, idR, transcript, transcript, ikm)
    }

    /** Confirm pairing: `ikm = dh || staticKey`, salt = `SHA-256(transcript || nA || nB)`. */
    public fun pairConfirm(
        role: LinkRole,
        idI: String,
        idR: String,
        transcript: ByteArray,
        nA: ByteArray,
        nB: ByteArray,
        dh: ByteArray,
        staticKey: ByteArray,
    ): LinkKeys {
        requireSize(dh, "dh")
        requireSize(staticKey, "staticKey")
        requireSize(nA, "nA")
        requireSize(nB, "nB")
        val salt = MessageDigest.getInstance("SHA-256").run {
            update(transcript)
            update(nA)
            update(nB)
            digest()
        }
        val ikm = dh + staticKey
        return derive(LinkMode.PAIR_CONFIRM, LABEL_PAIR_CONFIRM, role, idI, idR, transcript, salt, ikm)
    }

    /** QR pairing: `ikm = dh || staticKey || qrMix`, salt = transcript. */
    public fun pairQr(
        role: LinkRole,
        idI: String,
        idR: String,
        transcript: ByteArray,
        dh: ByteArray,
        staticKey: ByteArray,
        qrMix: ByteArray,
    ): LinkKeys {
        requireSize(dh, "dh")
        requireSize(staticKey, "staticKey")
        requireSize(qrMix, "qrMix")
        val ikm = dh + staticKey + qrMix
        return derive(LinkMode.PAIR_QR, LABEL_PAIR_QR, role, idI, idR, transcript, transcript, ikm)
    }

    /**
     * The session keys after `pairOk`: `prk = Extract(transcript, chainKey || newPairingSecret)`,
     * `veyro-link-v3` labels. Wipes `boot.chainKey`; the caller wipes the rest of [boot].
     */
    public fun afterPairing(boot: LinkKeys, newPairingSecret: ByteArray): LinkKeys {
        val chain = requireNotNull(boot.chainKey) { "afterPairing needs bootstrap (pairing-mode) keys" }
        requireSize(newPairingSecret, "pairingSecret")
        val ikm = chain + newPairingSecret
        val keys = derive(
            LinkMode.SESSION, LABEL_SESSION, boot.role, boot.initiatorId, boot.responderId,
            boot.transcript, boot.transcript, ikm,
        )
        chain.fill(0)
        return keys
    }

    /**
     * The key exporter (04-PROTOCOL.md 5.4): `HKDF-Expand(expSecret, info, length)` with
     * `info = u16be(15) || "veyro-export-v3" || u16be(len label) || label || u16be(len ctx) || ctx`.
     */
    public fun export(expSecret: ByteArray, label: String, context: ByteArray, length: Int): ByteArray {
        val labelBytes = label.toByteArray(Charsets.US_ASCII)
        require(labelBytes.size in 1..MAX_EXPORT_LABEL && label.all { it.code in 0x21..0x7E }) {
            "export label must be 1..$MAX_EXPORT_LABEL printable ASCII characters"
        }
        require(context.size <= MAX_EXPORT_CONTEXT) { "export context must be at most $MAX_EXPORT_CONTEXT bytes" }
        require(length in 1..MAX_EXPORT_LENGTH) { "export length must be 1..$MAX_EXPORT_LENGTH" }
        val prefix = LABEL_EXPORT.toByteArray(Charsets.US_ASCII)
        val info = u16be(prefix.size) + prefix + u16be(labelBytes.size) + labelBytes + u16be(context.size) + context
        return Hkdf.expand(expSecret, info, length)
    }

    /** `qrPrk = HKDF-Extract(utf8("veyro-qr-v3"), qrSecret)`. */
    public fun qrPrk(qrSecret: ByteArray): ByteArray =
        Hkdf.extract(LABEL_QR_SALT.toByteArray(Charsets.US_ASCII), qrSecret)

    /** `proofKey = HKDF-Expand(qrPrk, "veyro-qr-proof-v3", 32)`. */
    public fun qrProofKey(qrSecret: ByteArray): ByteArray {
        val prk = qrPrk(qrSecret)
        return try {
            Hkdf.expand(prk, LABEL_QR_PROOF.toByteArray(Charsets.US_ASCII), KEY_SIZE)
        } finally {
            prk.fill(0)
        }
    }

    /** `qrMix = HKDF-Expand(qrPrk, "veyro-qr-mix-v3", 32)`; enters the QR pairing `ikm`. */
    public fun qrMix(qrSecret: ByteArray): ByteArray {
        val prk = qrPrk(qrSecret)
        return try {
            Hkdf.expand(prk, LABEL_QR_MIX.toByteArray(Charsets.US_ASCII), KEY_SIZE)
        } finally {
            prk.fill(0)
        }
    }

    /**
     * `HMAC-SHA256(proofKey, "veyro-qr-proof-v3" || 0x00 || idPhone || 0x00 || idPc || 0x00 || transcript)`.
     */
    public fun qrProof(proofKey: ByteArray, idPhone: String, idPc: String, transcript: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(proofKey, "HmacSHA256")) }
        mac.update(joined(LABEL_QR_PROOF, idPhone, idPc))
        mac.update(0)
        mac.update(transcript)
        return mac.doFinal()
    }

    private fun derive(
        mode: LinkMode,
        label: String,
        role: LinkRole,
        idI: String,
        idR: String,
        transcript: ByteArray,
        salt: ByteArray,
        ikm: ByteArray,
    ): LinkKeys {
        val prk = Hkdf.extract(salt, ikm)
        try {
            val i2r = Hkdf.expand(prk, info(label, ROLE_I2R, idI, idR), KEY_SIZE)
            val r2i = Hkdf.expand(prk, info(label, ROLE_R2I, idI, idR), KEY_SIZE)
            val exp = if (mode == LinkMode.SESSION) Hkdf.expand(prk, info(label, ROLE_EXP, idI, idR), KEY_SIZE) else null
            val chain = if (mode != LinkMode.SESSION) Hkdf.expand(prk, info(label, ROLE_CHAIN, idI, idR), KEY_SIZE) else null
            val initiator = role == LinkRole.INITIATOR
            return LinkKeys(
                mode = mode,
                role = role,
                localId = if (initiator) idI else idR,
                peerId = if (initiator) idR else idI,
                initiatorId = idI,
                responderId = idR,
                transcript = transcript.copyOf(),
                sendKey = if (initiator) i2r else r2i,
                recvKey = if (initiator) r2i else i2r,
                chainKey = chain,
                expSecret = exp,
            )
        } finally {
            prk.fill(0)
            ikm.fill(0)
        }
    }

    private fun joined(vararg parts: String): ByteArray {
        var size = parts.size - 1
        val encoded = parts.map { it.toByteArray(Charsets.UTF_8).also { b -> size += b.size } }
        val out = ByteArray(size)
        var offset = 0
        for ((i, part) in encoded.withIndex()) {
            if (i > 0) out[offset++] = 0
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    private fun u32be(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun u16be(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun requireSize(value: ByteArray, name: String) {
        require(value.size == KEY_SIZE) { "$name must be $KEY_SIZE bytes" }
    }
}
