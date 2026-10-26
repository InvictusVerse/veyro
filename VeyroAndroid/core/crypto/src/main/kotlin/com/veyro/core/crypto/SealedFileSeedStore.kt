package com.veyro.core.crypto

import com.veyro.core.common.VeyroLog
import com.veyro.core.model.DeviceId
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * [SeedStore] that keeps the identity in a single AEAD-sealed file.
 *
 * All of the logic lives here and none of it needs a device: the key comes from an injected
 * [SealingKeyProvider] (see that interface for why). On Android the provider is
 * [KeystoreSealingKeyProvider], so the seed is encrypted with a non-exportable hardware-backed
 * key and copying the file to another device yields an undecryptable blob - which is the property
 * we want, because an identity that can be copied is an identity that can be cloned.
 *
 * ## File format
 *
 * ```
 * magic[4] = "VYID" | version[1] = 1 | iv[12] | ciphertext+tag
 * ```
 *
 * The plaintext inside is `deviceId[26] | createdAtMillis[8 BE] | seed[32]`. The deviceId and
 * timestamp sit *inside* the AEAD, so they get the same integrity protection as the seed. They are
 * not redundant: see [IdentityRecord] for the two failure modes they make detectable.
 *
 * ## Why AAD
 *
 * The additional authenticated data binds the ciphertext to this specific purpose. Without it, a
 * blob sealed by some other component that happens to use the same key could be swapped in here.
 */
public class SealedFileSeedStore(
    private val file: File,
    private val keyProvider: SealingKeyProvider,
) : SeedStore {

    private companion object {
        const val TAG = "SeedStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val FORMAT_MAGIC = "VYID"
        const val FORMAT_VERSION = 1
        const val HEADER_BYTES = 4 + 1 + IV_BYTES

        val AAD: ByteArray = "veyro-identity-seed-v1".toByteArray(Charsets.US_ASCII)

        /** deviceId[26] + createdAt[8] + seed[32] */
        const val PAYLOAD_BYTES = DeviceId.LENGTH + 8 + 32
    }

    override fun read(): IdentityRecord? {
        if (!file.exists()) return null

        val raw = try {
            file.readBytes()
        } catch (t: Throwable) {
            throw IdentityUnavailableException("Identity file exists but could not be read", t)
        }

        // Validate the container before touching crypto, so a stray file gives a clear message
        // instead of an opaque cipher failure.
        if (raw.size < HEADER_BYTES) {
            throw IdentityUnavailableException(
                "Identity file is truncated (${raw.size} bytes, expected at least $HEADER_BYTES)",
            )
        }
        if (String(raw, 0, 4, Charsets.US_ASCII) != FORMAT_MAGIC) {
            throw IdentityUnavailableException("Identity file has an unrecognised format")
        }
        val version = raw[4].toInt()
        if (version != FORMAT_VERSION) {
            throw IdentityUnavailableException(
                "Identity file version $version is not supported by this build",
            )
        }

        val key = keyProvider.existing()
            ?: throw IdentityUnavailableException(
                "The key that seals this identity is gone. This happens when the device " +
                    "credential changes or app data is cleared; the identity must be recreated " +
                    "and every paired device re-paired.",
            )

        val iv = raw.copyOfRange(5, HEADER_BYTES)
        val ciphertext = raw.copyOfRange(HEADER_BYTES, raw.size)

        val plain = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                updateAAD(AAD)
                doFinal(ciphertext)
            }
        } catch (t: Throwable) {
            // A GCM tag failure lands here. It means the file was modified or the key was replaced.
            // Those are indistinguishable, and both mean the same thing: do not trust this.
            throw IdentityUnavailableException(
                "The stored identity failed authentication and cannot be trusted", t,
            )
        }

        return try {
            decodeRecord(plain)
        } finally {
            plain.fill(0)
        }
    }

    override fun write(record: IdentityRecord) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreate())
            updateAAD(AAD)
        }

        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected GCM IV length ${iv.size}" }

        val plain = encodeRecord(record)
        val ciphertext = try {
            cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }

        val out = ByteArray(HEADER_BYTES + ciphertext.size)
        FORMAT_MAGIC.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        out[4] = FORMAT_VERSION.toByte()
        iv.copyInto(out, 5)
        ciphertext.copyInto(out, HEADER_BYTES)

        writeAtomically(out)
    }

    override fun destroy() {
        // Delete the file first. If the key deletion then fails we are still safe (an orphaned key
        // with no ciphertext), whereas the reverse order could leave a readable seed behind after
        // the user explicitly asked for it to be destroyed.
        if (file.exists() && !file.delete()) {
            VeyroLog.w(TAG) { "Could not delete the identity file" }
        }
        try {
            keyProvider.delete()
        } catch (t: Throwable) {
            VeyroLog.w(TAG, t) { "Could not delete the sealing key" }
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────

    private fun writeAtomically(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        // Rename is atomic within a filesystem, so a crash mid-write cannot leave a half-written
        // identity in place for the reader to find.
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IdentityUnavailableException("Could not persist the identity file")
            }
        }
    }

    private fun encodeRecord(record: IdentityRecord): ByteArray {
        val id = record.deviceId.value.toByteArray(Charsets.US_ASCII)
        require(id.size == DeviceId.LENGTH) { "deviceId must be ${DeviceId.LENGTH} characters" }
        val out = ByteArray(PAYLOAD_BYTES)
        id.copyInto(out, 0)
        var at = id.size
        for (i in 7 downTo 0) {
            out[at++] = (record.createdAtEpochMillis ushr (i * 8)).toByte()
        }
        record.seed.copyInto(out, at)
        return out
    }

    private fun decodeRecord(plain: ByteArray): IdentityRecord {
        if (plain.size != PAYLOAD_BYTES) {
            throw IdentityUnavailableException(
                "Identity payload has the wrong size (${plain.size}, expected $PAYLOAD_BYTES)",
            )
        }
        val id = DeviceId.parseOrNull(String(plain, 0, DeviceId.LENGTH, Charsets.US_ASCII))
            ?: throw IdentityUnavailableException("Identity payload holds an invalid deviceId")

        var created = 0L
        for (i in 0 until 8) {
            created = (created shl 8) or (plain[DeviceId.LENGTH + i].toLong() and 0xFF)
        }
        return IdentityRecord(
            seed = plain.copyOfRange(DeviceId.LENGTH + 8, PAYLOAD_BYTES),
            deviceId = id,
            createdAtEpochMillis = created,
        )
    }
}
