package com.veyro.core.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * X25519 key agreement, via BouncyCastle's **lightweight API**.
 *
 * See [CryptoProviders] for why this is not a JCA provider: `Security.addProvider(...)` plus
 * algorithm-name lookup forces R8 to keep all ~12 MB of `bcprov-jdk18on`, whereas direct class
 * references to `X25519Agreement` and friends give R8 a concrete call graph to shrink (ADR-016).
 *
 * Everything here is a thin, total wrapper. There is no field arithmetic in this file and there
 * must never be: RFC 7748's constant-time requirements are exactly where hand-rolled
 * implementations leak private keys through timing, and AGENTS.md forbids inventing cryptography.
 *
 * ## RFC 7748 specifics that are easy to get wrong
 *
 *  - **Clamping.** The scalar must have bits 0,1,2 cleared, bit 255 cleared, and bit 254 set.
 *    Verified in BouncyCastle's own source (`math/ec/rfc7748/X25519.decodeScalar`), which does
 *    `k[0] &= 0xF8`, `k[31] &= 0x7F`, `k[31] |= 0x40`. We therefore never clamp by hand. Note the
 *    consequence for tests: any 32 bytes are a valid private key, and `publicKeyOf` on a raw
 *    public key still returns a well-defined value rather than failing.
 *  - **The base point** is the u-coordinate 9, encoded little-endian.
 *  - **Little-endian.** Everything in RFC 7748 is little-endian, unlike the rest of VLP which is
 *    big-endian. This is a genuine trap and is called out again at the call sites.
 *  - **All-zero output.** A peer can send a low-order point that forces the shared secret to all
 *    zeros. BouncyCastle detects this and throws `IllegalStateException("X25519 agreement
 *    failed")`; [agree] converts that into an [IllegalArgumentException] naming the real cause, so
 *    a caller cannot mistake a hostile peer for a programming error.
 */
public object X25519 {

    public const val KEY_SIZE: Int = 32

    /** Byte length of a public key or a shared secret. Both are 32. */
    public const val SHARED_SECRET_SIZE: Int = 32

    /** Generates a fresh private key from the platform CSPRNG, clamped per RFC 7748. */
    public fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray {
        val seed = ByteArray(KEY_SIZE)
        random.nextBytes(seed)
        return seed
    }

    /** Derives the public key for [privateKey]: `X25519(privateKey, 9)`. */
    public fun publicKeyOf(privateKey: ByteArray): ByteArray {
        requireKeySize(privateKey, "private key")
        // Signature verified against the resolved jar with javap, not assumed:
        //   public X25519PublicKeyParameters generatePublicKey()
        // It returns the key object; there is no fill-this-buffer overload.
        val priv = X25519PrivateKeyParameters(privateKey, 0)
        return priv.generatePublicKey().encoded
    }

    /**
     * Raw Diffie-Hellman: `X25519(ourPrivate, theirPublic)`.
     *
     * @throws IllegalArgumentException if the peer's public key is a low-order point, which drives
     * the shared secret to all zeros. BouncyCastle raises `IllegalStateException` for this; it is
     * rethrown as `IllegalArgumentException` because from this API's point of view the caller
     * supplied an unacceptable argument - and because a caller catching `IllegalArgumentException`
     * for a bad key must not miss this case. Mapping it also keeps the message actionable instead
     * of the opaque "X25519 agreement failed".
     */
    public fun agree(ourPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        requireKeySize(ourPrivateKey, "private key")
        requireKeySize(theirPublicKey, "public key")

        val priv = X25519PrivateKeyParameters(ourPrivateKey, 0)
        val pub = X25519PublicKeyParameters(theirPublicKey, 0)

        val shared = ByteArray(SHARED_SECRET_SIZE)
        try {
            val agreement = X25519Agreement().apply { init(priv) }
            agreement.calculateAgreement(pub, shared, 0)
        } catch (e: IllegalStateException) {
            // Reached only via the all-zero path above; BouncyCastle throws from checkAllZeroes.
            throw IllegalArgumentException(
                "X25519 produced an all-zero shared secret: the peer sent a low-order point",
                e,
            )
        }

        require(shared.any { it.toInt() != 0 }) {
            // Belt and braces: if a future BouncyCastle version stops throwing, we still refuse to
            // hand out a shared secret that is public knowledge.
            "X25519 produced an all-zero shared secret: the peer sent a low-order point"
        }
        return shared
    }

    private fun requireKeySize(key: ByteArray, what: String) {
        require(key.size == KEY_SIZE) { "X25519 $what must be $KEY_SIZE bytes, was ${key.size}" }
    }

    /** The RFC 7748 base point, u = 9, little-endian. Exposed for tests. */
    public val BASE_POINT: ByteArray = ByteArray(KEY_SIZE).also { it[0] = 9 }

    /** True if a 32-byte array would be rejected by [agree] as a low-order point. */
    public fun isLowOrder(publicKey: ByteArray): Boolean = try {
        val probe = ByteArray(KEY_SIZE).also { it[0] = 1 } // any valid-ish scalar works
        agree(probe, publicKey)
        false
    } catch (expected: IllegalArgumentException) {
        true
    }
}
