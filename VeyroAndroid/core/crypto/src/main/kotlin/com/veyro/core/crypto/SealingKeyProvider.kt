package com.veyro.core.crypto

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Supplies the AES-256-GCM key that seals the identity seed at rest.
 *
 * This interface exists because of a concrete discovery: Robolectric does **not** provide a
 * working `AndroidKeyStore` JCA provider (`NoSuchProviderException: no such provider:
 * AndroidKeyStore`). Without this seam, every test of the storage format, the atomic write, and
 * the tamper-detection path would be an on-device instrumentation test - which means in practice
 * they would be written rarely and run rarely.
 *
 * With the seam, the split is honest:
 *
 *  - [SealedFileSeedStore] holds all the logic (container format, atomic rename, authentication
 *    failure handling) and is fully testable on a plain JVM.
 *  - [KeystoreSealingKeyProvider] holds ~30 lines of key creation and is the only part whose
 *    behaviour genuinely requires a device.
 *
 * That is also the right layering regardless of testing: "where does the key come from" is a
 * platform question, and "how is the payload framed and verified" is not.
 */
public interface SealingKeyProvider {

    /** Returns the existing key, or null if there is none. Must not create one. */
    public fun existing(): SecretKey?

    /** Returns the existing key, creating it if necessary. */
    public fun getOrCreate(): SecretKey

    /** Deletes the key, making any existing ciphertext permanently unreadable. */
    public fun delete()
}

/**
 * Test-only provider holding a plain in-memory AES-256 key.
 *
 * It lives in `main` so JVM tests of [SealedFileSeedStore] need no Robolectric and no emulator.
 * It provides **no** at-rest protection and must never be used in the app: the whole point of the
 * Keystore provider is that the key is not extractable, and this one is just a field.
 */
public class InMemorySealingKeyProvider : SealingKeyProvider {

    private var key: SecretKey? = null

    override fun existing(): SecretKey? = key

    override fun getOrCreate(): SecretKey = key ?: generate().also { key = it }

    override fun delete() {
        key = null
    }

    /** Replaces the key, simulating the Keystore key being invalidated and regenerated. */
    public fun rotate() {
        key = generate()
    }

    private fun generate(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
