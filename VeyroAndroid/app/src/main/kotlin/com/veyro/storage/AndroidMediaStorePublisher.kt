package com.veyro.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.veyro.ui.format.ReceivedFolders
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
 * - Every file goes to [MediaStore.Downloads] under one folder, sorted by type:
 *   `Download/Veyro/Photos`, `Videos`, `Music`, `Documents`, `Archives`, `Apps`, `Other`
 *   ([ReceivedFolders]). Media is still indexed by MIME type, so Gallery and music apps see it.
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
    }

    override suspend fun publish(
        sourceFile: File,
        relativePath: String,
        mimeType: String?,
        sizeBytes: Long,
    ): StoragePublishResult = withContext(Dispatchers.IO) {
        val fileName = relativePath.substringAfterLast('/')
        val resolvedMimeType = mimeType ?: detectMimeType(fileName)
        // File names are user data: never logged, here or in a failure reason.
        VeyroLog.i(TAG) { "Publishing an item ($sizeBytes bytes, mime=$resolvedMimeType) to destination" }

        // If user specified custom destination folder, write there instead of MediaStore
        val customDir = customDestinationDir?.invoke()
        if (customDir != null && customDir.exists()) {
            // The same subfolders inside the chosen folder, so it stays as tidy as the default.
            val sorted = File(customDir, ReceivedFolders.subfolder(fileName, resolvedMimeType))
            return@withContext publishToCustomDir(sourceFile, sorted, fileName, resolvedMimeType)
        }

        publishToMediaStore(sourceFile, fileName, resolvedMimeType, sizeBytes)
    }

    /**
     * One-time move of files Veyro saved before everything went into one folder: Pictures/Veyro,
     * Movies/Veyro, Music/Veyro and loose files in Download/Veyro go into the Download/Veyro
     * subfolders. Only rows this app owns can be moved, without any permission; anything else
     * (files from before a reinstall, files the user moved) stays where it is. Row ids do not
     * change, so history and Recent keep opening them. Returns how many moved.
     */
    public suspend fun moveOlderFiles(): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val old = listOf("Pictures/Veyro/", "Movies/Veyro/", "Music/Veyro/", "${ReceivedFolders.ROOT}/")
        var moved = 0
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        // Exactly the old folders, not their subfolders (Download/Veyro/Photos/ is already right).
        val selection = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} IN (${old.joinToString { "?" }})"
        runCatching {
            resolver.query(files, projection, selection, arrayOf(context.packageName) + old, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val target = ReceivedFolders.pathFor(name, c.getString(2)) + "/"
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target) }
                    val ok = runCatching {
                        resolver.update(android.content.ContentUris.withAppendedId(files, id), values, null, null) > 0
                    }.getOrDefault(false)
                    if (ok) moved++
                }
            }
        }.onFailure { VeyroLog.w(TAG) { "Moving older files failed: ${it.javaClass.simpleName}" } }
        VeyroLog.i(TAG) { "Moved $moved older received file(s) into ${ReceivedFolders.ROOT}" }
        moved
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

        // One Veyro folder for everything (Download/Veyro/Photos, /Videos, ...). The Downloads
        // collection is the only one that accepts every type; MediaStore still indexes images,
        // videos and audio there by MIME type, so they show in Gallery and music apps as before.
        val collectionUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativeDir = ReceivedFolders.pathFor(fileName, mimeType)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }

        val insertedUri: Uri
        try {
            insertedUri = contentResolver.insert(collectionUri, values)
                ?: return StoragePublishResult.Failed("ContentResolver failed to insert a row into $collectionUri")
        } catch (t: Throwable) {
            VeyroLog.e(TAG) { "ContentResolver.insert threw ${t.javaClass.simpleName}" }
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

            contentResolver.update(insertedUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)

            pendingUri = null // Completed successfully and verified byte-perfect
            // Clean up staging file
            sourceFile.delete()

            VeyroLog.i(TAG) {
                "[diagnostics:mediastore] item committed to MediaStore: size=$totalCopied bytes, uri=$insertedUri, dir=$relativeDir, mime=$mimeType"
            }
            return StoragePublishResult.Success(
                destinationUri = insertedUri.toString(),
                displayName = fileName,
                mimeType = mimeType,
                bytesWritten = totalCopied,
            )
        } catch (t: Throwable) {
            VeyroLog.e(TAG) { "Publication write failed (${t.javaClass.simpleName})" }
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
