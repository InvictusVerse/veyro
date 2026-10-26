package com.veyro.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.veyro.core.common.VeyroLog
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [SealingKeyProvider] backed by the Android Keystore (REQ-I03, `05-SECURITY.md` section 4.4).
 *
 * This is the only part of identity storage whose behaviour genuinely requires a device, and it is
 * deliberately kept to key lifecycle alone - no framing, no file I/O, no verification. All of that
 * is in [SealedFileSeedStore], which is testable on a plain JVM.
 *
 * ## Choices worth explaining
 *
 *  - **`setUserAuthenticationRequired(false)`.** Deliberate. A transfer must work with the screen
 *    locked; requiring per-use authentication would mean unlocking the phone every time the PC
 *    sends a file. `05-SECURITY.md` section 8 tracks "require unlock to send" as a planned opt-in
 *    (`G1`), not a default.
 *  - **StrongBox when available, TEE otherwise.** StrongBox is a separate tamper-resistant chip.
 *    Not every device has one, and absence surfaces as a thrown exception rather than a queryable
 *    flag, so it must be attempted and caught.
 *  - **`setRandomizedEncryptionRequired(true)`.** The Keystore generates the GCM IV. Reusing an IV
 *    with GCM is catastrophic, so the key is configured to refuse a caller-supplied one.
 *  - **`setUnlockedDeviceRequired` is NOT set.** It would break locked-screen transfers entirely.
 *
 * ## Testing honesty
 *
 * Robolectric does not provide an `AndroidKeyStore` provider, so this class cannot be unit-tested
 * on the JVM - an attempt returns `NoSuchProviderException`. Its behaviour is therefore verified by
 * instrumentation test on hardware, and that is the only way the StrongBox/TEE claim can actually
 * be checked. Tracked in `ProjectInfo/07-STATUS.md`.
 */
public class KeystoreSealingKeyProvider(
    private val alias: String = DEFAULT_ALIAS,
) : SealingKeyProvider {

    public companion object {
        public const val DEFAULT_ALIAS: String = "veyro.identity.v1"
        private const val TAG = "KeystoreKey"
        private const val KEYSTORE = "AndroidKeyStore"
    }

    override fun existing(): SecretKey? = try {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(alias, null) as? SecretKey
    } catch (t: Throwable) {
        VeyroLog.w(TAG, t) { "Keystore could not be opened" }
        null
    }

    override fun getOrCreate(): SecretKey {
        existing()?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(useStrongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                VeyroLog.i(TAG) { "No StrongBox on this device; using the TEE-backed Keystore" }
            } catch (t: Throwable) {
                VeyroLog.w(TAG, t) { "StrongBox key generation failed; falling back to the TEE" }
            }
        }
        return generate(useStrongBox = false)
    }

    override fun delete() {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    private fun generate(useStrongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }
}

/**
 * The production identity store: a sealed file in app-private storage, keyed by the Android
 * Keystore.
 *
 * A thin assembly of [SealedFileSeedStore] and [KeystoreSealingKeyProvider] so that `:app` has one
 * obvious thing to construct.
 */
public fun keystoreSeedStore(
    context: Context,
    fileName: String = "identity.bin",
): SeedStore = SealedFileSeedStore(
    file = File(context.filesDir, fileName),
    keyProvider = KeystoreSealingKeyProvider(),
)
