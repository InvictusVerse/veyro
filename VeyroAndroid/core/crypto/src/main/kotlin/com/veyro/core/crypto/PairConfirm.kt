package com.veyro.core.crypto

import com.veyro.core.common.Bytes
import com.veyro.core.protocol.ProtocolConstants
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Confirm-code pairing (04-PROTOCOL.md section 4.3, ADR-031): the derivations both sides compute.
 *
 * A is the initiator (normally the phone), B the responder (normally the PC). Only fixed primitives
 * are used: SHA-256 for the commitment and the code, HKDF-SHA256 over an X25519 shared secret for
 * the bootstrap key. Nothing here is negotiated, so the two clients agree or the user sees two
 * different codes.
 *
 * The commitment is what makes a 6-digit code sufficient: B fixes nB before it sees nA, and A
 * reveals nA before it sees nB, so a man in the middle cannot grind keys or nonces until the two
 * codes match. Pinned by `protocol/testvectors/pair-confirm.json`.
 *
 * Nonces and codes are secrets until pairing ends and are never logged.
 */
public object PairConfirm {

    public const val INFO_COMMIT: String = "veyro-commit-v1"
    public const val INFO_CODE: String = ProtocolConstants.INFO_SAS
    public const val INFO_BOOTSTRAP: String = "veyro-pair-confirm-v1"

    public const val NONCE_SIZE: Int = 32
    public const val KEY_SIZE: Int = 32
    public const val COMMIT_SIZE: Int = 32

    private val random = SecureRandom()

    /** A fresh 32-byte nonce from the platform CSPRNG. */
    public fun newNonce(): ByteArray = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }

    /** `C = SHA-256("veyro-commit-v1" || pkB || pkA || nB)`. Computed by B, checked by A. */
    public fun commitment(pkB: ByteArray, pkA: ByteArray, nB: ByteArray): ByteArray {
        requireSize(pkB, KEY_SIZE, "pkB")
        requireSize(pkA, KEY_SIZE, "pkA")
        requireSize(nB, NONCE_SIZE, "nB")
        return sha256(INFO_COMMIT.toByteArray(Charsets.US_ASCII), pkB, pkA, nB)
    }

    /** Constant-time check of a received commitment. False for any malformed input. */
    public fun verifyCommitment(commit: ByteArray, pkB: ByteArray, pkA: ByteArray, nB: ByteArray): Boolean {
        if (commit.size != COMMIT_SIZE || pkB.size != KEY_SIZE || pkA.size != KEY_SIZE || nB.size != NONCE_SIZE) {
            return false
        }
        return MessageDigest.isEqual(commitment(pkB, pkA, nB), commit)
    }

    /**
     * The 6 digits both screens show: `d = SHA-256("veyro-sas-v1" || pkA || pkB || nA || nB)`,
     * `BE32(d[0..3]) mod 1000000`, zero-padded.
     */
    public fun code(pkA: ByteArray, pkB: ByteArray, nA: ByteArray, nB: ByteArray): String {
        requireSize(pkA, KEY_SIZE, "pkA")
        requireSize(pkB, KEY_SIZE, "pkB")
        requireSize(nA, NONCE_SIZE, "nA")
        requireSize(nB, NONCE_SIZE, "nB")
        val d = sha256(INFO_CODE.toByteArray(Charsets.US_ASCII), pkA, pkB, nA, nB)
        val value = Bytes.readU32be(d, 0).toLong() and 0xFFFFFFFFL
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    /** `123 456`, for display only. */
    public fun formatCode(code: String): String =
        if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code

    /** HKDF salt: `nA || nB`. */
    public fun bootstrapSalt(nA: ByteArray, nB: ByteArray): ByteArray {
        requireSize(nA, NONCE_SIZE, "nA")
        requireSize(nB, NONCE_SIZE, "nB")
        return nA + nB
    }

    /** HKDF info: `"veyro-pair-confirm-v1" || idA || idB`, US-ASCII DeviceId strings. */
    public fun bootstrapInfo(idA: String, idB: String): ByteArray =
        (INFO_BOOTSTRAP + idA + idB).toByteArray(Charsets.US_ASCII)

    /**
     * `K = HKDF-SHA256(ikm = sharedSecret, salt = nA || nB, info, 32)`.
     *
     * Exposed for the vectors and for callers that already hold a shared secret. Production code
     * goes through [IdentityManager.deriveSharedKey] so the X25519 output never leaves the identity.
     */
    public fun bootstrapKey(
        sharedSecret: ByteArray,
        nA: ByteArray,
        nB: ByteArray,
        idA: String,
        idB: String,
    ): ByteArray = Hkdf.deriveKey(
        ikm = sharedSecret,
        salt = bootstrapSalt(nA, nB),
        info = bootstrapInfo(idA, idB),
        length = KEY_SIZE,
    )

    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach { update(it) }
            digest()
        }

    private fun requireSize(value: ByteArray, size: Int, name: String) {
        require(value.size == size) { "$name must be $size bytes, was ${value.size}" }
    }
}
