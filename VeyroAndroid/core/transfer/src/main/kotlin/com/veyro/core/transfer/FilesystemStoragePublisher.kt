package com.veyro.core.transfer

import com.veyro.core.protocol.RelativePath
import java.io.File

/**
 * Pure JVM filesystem implementation of [TransferStoragePublisher].
 *
 * Saves verified files to [destinationDir] using collision resolution and atomic rename/copy.
 * A nested path keeps its folders (a received folder, ADR-064), after validation (REQ-T11) and a
 * check that the resolved target is still inside [destinationDir].
 * Useful for tests, desktop implementations, and fallback storage.
 */
public class FilesystemStoragePublisher(
    private val destinationDir: File,
) : TransferStoragePublisher {

    override suspend fun publish(
        sourceFile: File,
        relativePath: String,
        mimeType: String?,
        sizeBytes: Long,
    ): StoragePublishResult {
        destinationDir.mkdirs()
        val resolved = inside(relativePath) ?: return StoragePublishResult.Failed("PATH_REJECTED")
        resolved.parentFile?.mkdirs()
        val target = resolveCollision(resolved)
        return try {
            if (!sourceFile.renameTo(target)) {
                sourceFile.copyTo(target, overwrite = false)
                sourceFile.delete()
            }
            StoragePublishResult.Success(
                destinationUri = target.toURI().toString(),
                displayName = target.name,
                mimeType = mimeType ?: "application/octet-stream",
                bytesWritten = target.length(),
            )
        } catch (t: Throwable) {
            StoragePublishResult.Failed("Filesystem publication failed: ${t.message}", t)
        }
    }

    override suspend fun folderExists(name: String): Boolean = inside(name)?.exists() == true

    override suspend fun createDirectory(relativePath: String): Boolean {
        val dir = inside(relativePath) ?: return false
        return dir.mkdirs() || dir.isDirectory
    }

    /** The target for a validated [relativePath], or null when it fails or escapes the folder. */
    private fun inside(relativePath: String): File? {
        val valid = RelativePath.validate(relativePath) as? RelativePath.Result.Valid ?: return null
        val root = destinationDir.canonicalFile
        val target = File(root, valid.normalized).canonicalFile
        return target.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    private fun resolveCollision(target: File): File {
        if (!target.exists()) return target
        val name = target.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        for (counter in 1..9999) {
            val candidate = File(target.parentFile, "$stem ($counter)$extension")
            if (!candidate.exists()) return candidate
        }
        return File(target.parentFile, "$stem (${System.currentTimeMillis()})$extension")
    }
}
