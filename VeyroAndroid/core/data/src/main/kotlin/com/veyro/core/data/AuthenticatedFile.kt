package com.veyro.core.data

import com.veyro.core.common.Bytes
import java.io.File
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * An append-safe, authenticated, atomically-written file.
 *
 * Three guarantees, each for a concrete reason:
 *
 *  1. **Atomic.** Written to `name.tmp` then renamed. A crash or a kill mid-write cannot leave a
 *     half-written trust store behind for the next launch to parse - which would either crash or,
 *     worse, parse as a *shorter* list of trusted devices.
 *  2. **Authenticated.** An HMAC-SHA256 tag over the payload, keyed by a secret the app holds.
 *     This is what stops an attacker with filesystem access from silently *adding* a trusted peer
 *     (`05-SECURITY.md` section 4.4, adversary A4). Note what it does not do: it is not
 *     confidentiality, and it does not stop deletion. Deletion degrades to "you must re-pair",
 *     which is safe; silent insertion would not be.
 *  3. **Versioned.** A one-byte format version, so a future change fails loudly instead of being
 *     misparsed.
 *
 * ```
 * magic[4]="VYST" | version[1] | tagLen[1]=32 | hmac[32] | payload
 * ```
 *
 * The HMAC covers `version || payload`, so the version cannot be downgraded without detection.
 */
internal class AuthenticatedFile(
    private val file: File,
    private val macKey: ByteArray,
) {

    private companion object {
        val MAGIC = "VYST".toByteArray(Charsets.US_ASCII)
        const val VERSION: Byte = 1
        const val TAG_LEN = 32
        const val HEADER = 4 + 1 + 1 + TAG_LEN
        const val ALGORITHM = "HmacSHA256"
    }

    class CorruptException(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun exists(): Boolean = file.exists()

    /**
     * Reads and verifies the payload.
     *
     * @return the payload, or null if the file does not exist.
     * @throws CorruptException if the file is malformed or fails authentication. The caller must
     * decide the policy - for the trust store that means "treat as empty and tell the user", never
     * "silently continue with whatever parsed".
     */
    fun read(): ByteArray? {
        if (!file.exists()) return null

        val raw = try {
            file.readBytes()
        } catch (t: Throwable) {
            throw CorruptException("could not read ${file.name}", t)
        }

        if (raw.size < HEADER) {
            throw CorruptException("${file.name} is truncated (${raw.size} bytes)")
        }
        if (!raw.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw CorruptException("${file.name} has an unrecognised format")
        }
        val version = raw[4]
        if (version != VERSION) {
            throw CorruptException("${file.name} has unsupported version $version")
        }
        if (raw[5].toInt() != TAG_LEN) {
            throw CorruptException("${file.name} declares an unexpected tag length ${raw[5]}")
        }

        val tag = raw.copyOfRange(6, 6 + TAG_LEN)
        val payload = raw.copyOfRange(HEADER, raw.size)

        val expected = mac(version, payload)
        // Constant-time: a short-circuiting compare leaks how many leading tag bytes an attacker
        // guessed, which is enough to forge a tag one byte at a time.
        if (!Bytes.constantTimeEquals(tag, expected)) {
            throw CorruptException(
                "${file.name} failed authentication: it has been modified, or the key changed",
            )
        }
        return payload
    }

    fun write(payload: ByteArray) {
        val tag = mac(VERSION, payload)
        val out = ByteArray(HEADER + payload.size)
        MAGIC.copyInto(out, 0)
        out[4] = VERSION
        out[5] = TAG_LEN.toByte()
        tag.copyInto(out, 6)
        payload.copyInto(out, HEADER)

        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) {
            // Windows-style failure when the target exists; retry after removing it. The window
            // between delete and rename is the one place this is not atomic, which is why the
            // first attempt is the normal path.
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("could not persist ${file.name}")
            }
        }
    }

    fun delete() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    private fun mac(version: Byte, payload: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(macKey, ALGORITHM)) }
        mac.update(version)
        return mac.doFinal(payload)
    }
}
