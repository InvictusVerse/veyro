package com.veyro.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.veyro.core.common.hex
import com.veyro.core.common.VeyroLog
import com.veyro.core.transfer.ChunkMath
import com.veyro.core.transfer.StoragePublishResult
import com.veyro.core.transfer.TransferStoragePublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Android MediaStore publisher for verified incoming transfers (REQ-T12).
 *
 * Distinct from receiving and hashing: only after whole-file SHA-256 verification passes
 * are bytes copied into shared MediaStore collections.
 *
 * - Images are published to [MediaStore.Images] under `Pictures/Veyro/`.
 * - Videos are published to [MediaStore.Video] under `Movies/Veyro/`.
 * - Audio is published to [MediaStore.Audio] under `Music/Veyro/`.
 * - Other files (Documents, Archives, etc.) are published to [MediaStore.Downloads] under `Download/Veyro/`.
 *
 * Uses `IS_PENDING = 1` during writing and clears it upon completion.
 * Handles insert, stream-open, write, and close failures explicitly by deleting abandoned pending rows.
 * If a custom destination directory was configured by the user, files are moved there instead.
 */
public class AndroidMediaStorePublisher(
    private val context: Context,
    private val customDestinationDir: (suspend () -> File?)? = null,
) : TransferStoragePublisher {

    public companion object {
        private const val TAG = "MediaStorePublisher"
        private const val VEYRO_SUBDIR = "Veyro"
    }

    override suspend fun publish(
        sourceFile: File,
        relativePath: String,
        mimeType: String?,
        sizeBytes: Long,
    ): StoragePublishResult = withContext(Dispatchers.IO) {
        val fileName = relativePath.substringAfterLast('/')
        val resolvedMimeType = mimeType ?: detectMimeType(fileName)
        VeyroLog.i(TAG) { "Publishing $fileName ($sizeBytes bytes, mime=$resolvedMimeType) to destination" }

        // If user specified custom destination folder, write there instead of MediaStore
        val customDir = customDestinationDir?.invoke()
        if (customDir != null && customDir.exists()) {
            return@withContext publishToCustomDir(sourceFile, customDir, fileName, resolvedMimeType)
        }

        publishToMediaStore(sourceFile, fileName, resolvedMimeType, sizeBytes)
    }

    private fun detectMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/x-ms-bmp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            else -> {
                runCatching {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                }.getOrNull() ?: "application/octet-stream"
            }
        }
    }

    private fun publishToMediaStore(
        sourceFile: File,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
    ): StoragePublishResult {
        val contentResolver = context.contentResolver

        val (collectionUri, relativeDir) = when {
            mimeType.startsWith("image/") -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                uri to "${Environment.DIRECTORY_PICTURES}/$VEYRO_SUBDIR"
            }
            mimeType.startsWith("video/") -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
                uri to "${Environment.DIRECTORY_MOVIES}/$VEYRO_SUBDIR"
            }
            mimeType.startsWith("audio/") -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                uri to "${Environment.DIRECTORY_MUSIC}/$VEYRO_SUBDIR"
            }
            else -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Files.getContentUri("external")
                }
                uri to "${Environment.DIRECTORY_DOWNLOADS}/$VEYRO_SUBDIR"
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }

        val insertedUri: Uri
        try {
            insertedUri = contentResolver.insert(collectionUri, values)
                ?: return StoragePublishResult.Failed("ContentResolver failed to insert row for $fileName into $collectionUri")
        } catch (t: Throwable) {
            VeyroLog.e(TAG, t) { "ContentResolver.insert threw for $fileName" }
            return StoragePublishResult.Failed("Insert exception: ${t.message}", t)
        }

        var pendingUri: Uri? = insertedUri
        try {
            val outputStream = contentResolver.openOutputStream(insertedUri, "w")
                ?: throw IOException("ContentResolver failed to open output stream for $insertedUri")

            var totalCopied = 0L
            sourceFile.inputStream().use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalCopied += read
                    }
                    output.flush()
                }
            }

            if (sizeBytes > 0 && totalCopied != sizeBytes) {
                throw IOException("Size mismatch on commit: wrote $totalCopied bytes, expected $sizeBytes")
            }

            // Post-publication stream read-back verification: Guarantee byte-for-byte fidelity
            val reopenedDigest = MessageDigest.getInstance("SHA-256")
            val readBackBytes = contentResolver.openInputStream(insertedUri)?.use { readBack ->
                val checkBuf = ByteArray(256 * 1024)
                var readCount = 0L
                var r: Int
                while (readBack.read(checkBuf).also { r = it } != -1) {
                    reopenedDigest.update(checkBuf, 0, r)
                    readCount += r
                }
                readCount
            } ?: throw IOException("Could not reopen $insertedUri for post-publication verification")

            if (readBackBytes != totalCopied) {
                throw IOException("Post-publication read-back length mismatch: read $readBackBytes, expected $totalCopied")
            }

            val readBackHash = reopenedDigest.digest().hex()
            val expectedSourceHash = ChunkMath.hashFile(sourceFile)
            if (!readBackHash.equals(expectedSourceHash, ignoreCase = true)) {
                throw IOException("Post-publication hash verification failed: MediaStore hash $readBackHash != source hash $expectedSourceHash")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(insertedUri, updateValues, null, null)
            }

            pendingUri = null // Completed successfully and verified byte-perfect
            // Clean up staging file
            sourceFile.delete()

            VeyroLog.i(TAG) {
                "[diagnostics:mediastore] item $fileName committed to MediaStore: size=$totalCopied bytes, uri=$insertedUri, dir=$relativeDir, mime=$mimeType"
            }
            return StoragePublishResult.Success(
                destinationUri = insertedUri.toString(),
                displayName = fileName,
                mimeType = mimeType,
                bytesWritten = totalCopied,
            )
        } catch (t: Throwable) {
            VeyroLog.e(TAG, t) { "Publication write failed for $fileName: ${t.message}" }
            pendingUri?.let { uriToDelete ->
                runCatching { contentResolver.delete(uriToDelete, null, null) }
            }
            return StoragePublishResult.Failed("Failed writing to MediaStore: ${t.message}", t)
        }
    }

    private fun publishToCustomDir(
        sourceFile: File,
        targetDir: File,
        fileName: String,
        mimeType: String,
    ): StoragePublishResult {
        targetDir.mkdirs()
        val targetFile = resolveCollision(File(targetDir, fileName))
        return try {
            if (!sourceFile.renameTo(targetFile)) {
                sourceFile.copyTo(targetFile, overwrite = false)
                sourceFile.delete()
            }
            StoragePublishResult.Success(
                destinationUri = targetFile.toURI().toString(),
                displayName = targetFile.name,
                mimeType = mimeType,
                bytesWritten = targetFile.length(),
            )
        } catch (t: Throwable) {
            StoragePublishResult.Failed("Failed to publish to custom dir: ${t.message}", t)
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
