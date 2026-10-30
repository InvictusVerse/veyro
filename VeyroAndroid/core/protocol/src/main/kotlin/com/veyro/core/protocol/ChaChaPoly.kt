package com.veyro.core.protocol

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The one place a ChaCha20-Poly1305 (RFC 8439) cipher is created (ADR-058, 04-PROTOCOL.md 5.5).
 *
 * Platform only, fail closed: there is no BouncyCastle fallback. The JDK standard name is tried
 * first, then the name Android documents for API 28+. Both are the RFC 8439 AEAD; the RFC vector
 * test pins the bytes. Which name Conscrypt resolves on a device is unverified (no hardware).
 */
public object ChaChaPoly {

    /** Lookup order. */
    public val TRANSFORMATIONS: List<String> = listOf("ChaCha20-Poly1305", "ChaCha20/Poly1305/NoPadding")

    public const val KEY_SIZE: Int = 32
    public const val NONCE_SIZE: Int = 12
    public const val TAG_SIZE: Int = 16

    @Volatile
    private var resolved: String? = null

    /** A fresh cipher instance. Throws [IllegalStateException] when no provider offers the AEAD. */
    public fun newCipher(): Cipher {
        resolved?.let { name ->
            try {
                return Cipher.getInstance(name)
            } catch (_: GeneralSecurityException) {
                resolved = null
            }
        }
        for (name in TRANSFORMATIONS) {
            try {
                val cipher = Cipher.getInstance(name)
                resolved = name
                return cipher
            } catch (_: GeneralSecurityException) {
                // Try the next name.
            }
        }
        throw IllegalStateException("ChaCha20-Poly1305 unavailable")
    }

    /** Called before a link starts, so a missing provider fails at once and not on the first frame. */
    public fun requireAvailable() {
        newCipher()
    }

    /** The key spec every sealer and opener uses. */
    public fun keySpec(key: ByteArray): SecretKeySpec {
        require(key.size == KEY_SIZE) { "ChaCha20-Poly1305 key must be $KEY_SIZE bytes" }
        return SecretKeySpec(key, "ChaCha20")
    }
}
