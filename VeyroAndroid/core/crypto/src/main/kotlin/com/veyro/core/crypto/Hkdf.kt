package com.veyro.core.crypto

import com.veyro.core.common.Bytes
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256, RFC 5869.
 *
 * Implemented here rather than taken from a library because the platform has no HKDF, and
 * because the whole function is 20 lines of `extract` + `expand` around a platform `Mac`. The
 * risk of a hand-rolled KDF is real but is confined to the loop structure, not to any primitive,
 * and it is pinned by the RFC's own test vectors in [HkdfTest].
 *
 * Written from the RFC, not from memory:
 *
 * ```
 * HKDF-Extract(salt, IKM) -> PRK
 *     PRK = HMAC-Hash(salt, IKM)
 *     if salt is not provided: salt = HashLen zero bytes
 *
 * HKDF-Expand(PRK, info, L) -> OKM
 *     N   = ceil(L / HashLen)
 *     T(0) = empty
 *     T(i) = HMAC-Hash(PRK, T(i-1) || info || i)      for i = 1..N
 *     OKM  = first L bytes of T(1) || T(2) || ... || T(N)
 * ```
 *
 * Two details that are easy to get wrong and are asserted in tests:
 *  - an absent salt means `HashLen` zeros, *not* an empty key (HMAC would pad it identically, but
 *    only for HMAC - being explicit avoids the confusion entirely);
 *  - the counter `i` is a single byte starting at 1, and `L` must not exceed `255 * HashLen`.
 */
public object Hkdf {

    private const val HASH_LEN = 32 // SHA-256
    private const val MAX_OUTPUT = 255 * HASH_LEN
    private const val ALGORITHM = "HmacSHA256"

    /** RFC 5869 section 2.2. `salt` may be empty, meaning "HashLen zeros". */
    public fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmac(effectiveSalt, ikm)
    }

    /** RFC 5869 section 2.3. `info` may be empty. */
    public fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..MAX_OUTPUT) {
            "HKDF output length must be 1..$MAX_OUTPUT, was $length"
        }
        require(prk.size >= HASH_LEN) {
            "HKDF PRK must be at least $HASH_LEN bytes, was ${prk.size}"
        }

        val blocks = (length + HASH_LEN - 1) / HASH_LEN
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0

        for (i in 1..blocks) {
            val mac = newMac(prk)
            mac.update(previous)
            mac.update(info)
            mac.update(i.toByte())
            previous = mac.doFinal()

            val copy = minOf(HASH_LEN, length - offset)
            System.arraycopy(previous, 0, out, offset, copy)
            offset += copy
        }
        return out
    }

    /** Extract-then-expand in one call. */
    public fun deriveKey(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = newMac(key)
        return mac.doFinal(message)
    }

    private fun newMac(key: ByteArray): Mac =
        Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }
}

/**
 * The protocol's derived secrets, in one place so the info strings cannot drift apart.
 *
 * These strings are part of the wire format. Changing any of them changes every derived key and
 * therefore breaks compatibility with any other implementation - it requires a `VLP_VERSION` bump.
 */
public object VlpKdf {

    private const val INFO_SAS = "veyro-sas-v1"
    private const val INFO_PAIRING_SECRET = "veyro-pair-v1"
    private const val INFO_PRESENCE = "veyro-presence-v1"
    private const val INFO_SESSION_ID_SALT = "veyro-session-v1"

    /** 16-byte session identifier derived from the handshake hash. */
    public fun sessionId(handshakeHash: ByteArray): ByteArray =
        Hkdf.deriveKey(handshakeHash, ByteArray(0), INFO_SESSION_ID_SALT.toByteArray(Charsets.US_ASCII), 16)

    /**
     * The 6-digit short authentication string shown on both screens during pairing
     * (04-PROTOCOL.md section 4.3).
     *
     * Derived from the handshake transcript, so both sides show the same digits only if they
     * really did complete the same handshake. That is what makes it a MITM check rather than
     * decoration: an attacker who spliced two half-handshakes produces two different transcripts
     * and therefore two different 6-digit strings on the two screens.
     */
    public fun shortAuthString(handshakeHash: ByteArray): String {
        val raw = Hkdf.deriveKey(handshakeHash, ByteArray(0), INFO_SAS.toByteArray(Charsets.US_ASCII), 4)
        val value = Bytes.readU32be(raw, 0).toLong() and 0xFFFFFFFFL
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    /** 32-byte pairing secret stored in the trust record. Used for presence tokens only. */
    public fun pairingSecret(handshakeHash: ByteArray): ByteArray =
        Hkdf.deriveKey(handshakeHash, ByteArray(0), INFO_PAIRING_SECRET.toByteArray(Charsets.US_ASCII), 32)

    /**
     * Rotating presence token (04-PROTOCOL.md section 3.4).
     *
     * HMAC rather than HKDF, deliberately: this is a keyed *identification* tag that a peer
     * recomputes from a stored secret, not key material that gets expanded. Using HKDF here would
     * work but would suggest the output is a key, and it is not.
     *
     * `epoch` is `floor(unixSeconds / 60)`. Browsers test the current and previous epoch to
     * tolerate clock skew.
     */
    public fun presenceToken(pairingSecret: ByteArray, epoch: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(pairingSecret, "HmacSHA256"))
        }
        mac.update(INFO_PRESENCE.toByteArray(Charsets.US_ASCII))
        val full = mac.doFinal(Bytes.u64be(epoch))
        return full.copyOf(8)
    }

    /** The epoch a presence token belongs to. */
    public fun epochOf(unixSeconds: Long): Long = unixSeconds / 60L
}
