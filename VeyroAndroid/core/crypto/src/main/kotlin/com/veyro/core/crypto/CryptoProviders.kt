package com.veyro.core.crypto

/**
 * Which primitive comes from where, and why.
 *
 * This is written down because it is the single biggest influence on both the correctness and the
 * size of the crypto layer, and because the obvious choice (register `BouncyCastleProvider` and
 * use JCA names for everything) is the wrong one here. See ADR-016.
 *
 * ## Platform where possible
 *
 * | Primitive            | Source                                  | Available from |
 * |----------------------|-----------------------------------------|----------------|
 * | SHA-256              | `MessageDigest` (platform)              | all            |
 * | HMAC-SHA256          | `Mac` (platform)                        | all            |
 * | HKDF-SHA256          | implemented on `Mac` - RFC 5869 is ~20 lines of extract/expand | all |
 * | ChaCha20-Poly1305    | `Cipher.getInstance("ChaCha20-Poly1305")` via Conscrypt | API 28 |
 * | X25519               | **BouncyCastle lightweight API**        | see below      |
 *
 * `minSdk` is 29, so only X25519 is a problem: the platform exposes it through
 * `KeyAgreement.getInstance("XDH")` only from API 33. API 29-32 therefore needs a library, and
 * writing our own X25519 is not on the table - constant-time field arithmetic is exactly where
 * hand-rolled implementations fail (AGENTS.md: never invent cryptography).
 *
 * ## Lightweight API, never the JCA provider
 *
 * `bcprov-jdk18on` is roughly 12 MB: the full JCA provider plus post-quantum, CMS, S/MIME and
 * OpenPGP. Using it as a JCA provider means algorithms are resolved reflectively by name, which
 * R8 cannot follow, so the whole 12 MB survives shrinking.
 *
 * Using the lightweight API instead - a direct reference to
 * `org.bouncycastle.crypto.agreement.X25519Agreement` and friends - gives R8 a concrete call
 * graph and lets it discard everything else.
 *
 * So the rules are:
 *
 *  - **Do** reference BouncyCastle lightweight classes directly.
 *  - **Do not** call `Security.addProvider(BouncyCastleProvider())`.
 *  - **Do not** add `-keep class org.bouncycastle.**` to `proguard-rules.pro`.
 *  - **Do** prefer a platform `MessageDigest` / `Mac` / `Cipher` whenever one exists.
 *
 * The measurement that justifies this is recorded in ProjectInfo/07-STATUS.md; verify the release
 * APK size before closing phase P1 (NFR-10 target: < 15 MB).
 *
 * ## Randomness
 *
 * `SecureRandom()` with no seeding, no `setSeed`, and no custom provider. On Android it is backed
 * by the kernel CSPRNG. Seeding it manually can only make it worse.
 */
internal object CryptoProviders {

    /** RFC 7748 X25519 key/scalar size. */
    const val X25519_KEY_SIZE = 32

    /** ChaCha20-Poly1305 IETF nonce size. */
    const val AEAD_NONCE_SIZE = 12

    /** Poly1305 tag size. */
    const val AEAD_TAG_SIZE = 16

    const val SHA256 = "SHA-256"
    const val HMAC_SHA256 = "HmacSHA256"
    const val CHACHA20_POLY1305 = "ChaCha20-Poly1305"
}
