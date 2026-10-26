package com.veyro.core.transfer

import java.io.File

/**
 * Pure JVM filesystem implementation of [TransferStoragePublisher].
 *
 * Saves verified files to [destinationDir] using collision resolution and atomic rename/copy.
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
        val fileName = relativePath.substringAfterLast('/')
        val target = resolveCollision(File(destinationDir, fileName))
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
