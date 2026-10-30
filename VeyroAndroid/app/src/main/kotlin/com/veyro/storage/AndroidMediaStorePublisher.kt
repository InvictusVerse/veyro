package com.veyro.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.veyro.ui.format.ReceiveTree
import com.veyro.ui.format.ReceivedFolders
import android.webkit.MimeTypeMap
import com.veyro.core.common.VeyroLog
import com.veyro.core.transfer.DirectReceiveTarget
import com.veyro.core.transfer.StoragePublishResult
import com.veyro.core.transfer.TransferStoragePublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android MediaStore publisher for verified incoming transfers (REQ-T12).
 *
 * Distinct from receiving and hashing: an item only becomes visible in shared storage after its
 * whole-file SHA-256 verification passes. On the LAN route it is received straight into a pending
 * entry ([openDirect], ADR-049); other routes stage a file and [publish] copies it in.
 *
 * - Every file goes to [MediaStore.Downloads] under one folder, sorted by type:
 *   `Download/Veyro/Photos`, `Videos`, `Music`, `Documents`, `Archives`, `Apps`, `Other`
 *   ([ReceivedFolders]). Media is still indexed by MIME type, so Gallery and music apps see it.
 *
 * Uses `IS_PENDING = 1` during writing and clears it upon completion.
 * Handles insert, stream-open, write, and close failures explicitly by deleting abandoned pending rows.
 *
 * ADR-072: when the user picked a folder in Settings, files go there instead, through the
 * persisted SAF tree grant ([ReceiveTreeAccess]): the same type subfolders and folder structure,
 * written as `<name>.veyropart` and renamed once verified. If the grant is gone or the folder was
 * deleted, that receive goes to Download/Veyro and [onReceiveFolderLost] is told.
 */
public class AndroidMediaStorePublisher(
    private val context: Context,
    /** The tree URI chosen in Settings; null for Download/Veyro. */
    private val receiveFolderUri: (suspend () -> String?)? = null,
    /** A receive fell back to Download/Veyro because the chosen folder is not usable. */
    private val onReceiveFolderLost: suspend () -> Unit = {},
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

        // ADR-072: the folder chosen in Settings, when it is still usable.
        chosenTree()?.let { tree ->
            return@withContext publishToTree(tree, sourceFile, relativePath, resolvedMimeType, sizeBytes)
        }

        publishToMediaStore(sourceFile, fileName, resolvedMimeType, sizeBytes, ReceivedFolders.dirFor(relativePath, resolvedMimeType))
    }

    /**
     * ADR-064: a received folder's top name is taken when one of the type folders has it, when
     * this app already saved something under it, or (Android 11+, where shared storage can be
     * looked at by path) when a folder of that name is already there.
     */
    override suspend fun folderExists(name: String): Boolean = withContext(Dispatchers.IO) {
        if (ReceivedFolders.isTypeFolder(name)) return@withContext true
        chosenTree()?.let { tree -> return@withContext tree.rootHas(name) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && sharedVeyroDir()?.let { File(it, name).exists() } == true) {
            return@withContext true
        }
        // Rows this app owns under the name, finished or still pending. LIKE wildcards in the
        // name are escaped; a backslash cannot occur (REQ-T11).
        val escaped = name.replace("%", "\\%").replace("_", "\\_")
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'",
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(context.packageName, "${ReceivedFolders.ROOT}/$escaped/%"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        runCatching {
            context.contentResolver.query(downloads(), arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { it.count > 0 } == true
        }.onFailure { VeyroLog.w(TAG) { "Folder check failed (${it.javaClass.simpleName})" } }.getOrDefault(false)
    }

    /**
     * An empty directory of a received folder (ADR-064). MediaStore has no rows for directories,
     * so it is made by path: in the chosen folder, or on Android 11+ in Download/Veyro, where an
     * app may create directories without a permission. Android 10 cannot; it is counted instead.
     */
    override suspend fun createDirectory(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        chosenTree()?.let { tree ->
            val segments = ReceiveTree.segments(relativePath) ?: return@withContext false
            return@withContext tree.dir(segments, create = true) != null
        }
        val root = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> sharedVeyroDir()
            else -> null
        } ?: return@withContext false
        val dir = insideOrNull(root, relativePath) ?: return@withContext false
        runCatching { dir.mkdirs() || dir.isDirectory }.getOrDefault(false)
    }

    /** Download/Veyro on the primary volume, by path. */
    @Suppress("DEPRECATION") // The path is only read and made, never written through File for a file.
    private fun sharedVeyroDir(): File? = runCatching {
        File(android.os.Environment.getExternalStorageDirectory(), ReceivedFolders.ROOT)
    }.getOrNull()

    /** [root]/[relativePath] when it stays inside [root] after canonicalisation (REQ-T11). */
    private fun insideOrNull(root: File, relativePath: String): File? {
        if (!com.veyro.core.protocol.RelativePath.isValid(relativePath)) return null
        return runCatching {
            val base = root.canonicalFile
            File(base, relativePath).canonicalFile.takeIf { it.path.startsWith(base.path + File.separator) }
        }.getOrNull()
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

    /**
     * ADR-049: a pending MediaStore entry in its final folder, written in place while the file
     * arrives. Pending means hidden from other apps and Gallery until [MediaStoreTarget.commit],
     * which only runs after the whole-file hash matched; so nothing is copied after the last byte.
     * In the folder chosen in Settings (ADR-072) it is a `<name>.veyropart` document written the
     * same way. Null (receive into a staging file instead) if MediaStore or the folder's provider
     * refuses, or the provider's file cannot be written at a position.
     */
    override suspend fun openDirect(relativePath: String, mimeType: String?, sizeBytes: Long): DirectReceiveTarget? =
        withContext(Dispatchers.IO) {
            val fileName = relativePath.substringAfterLast('/')
            val resolvedMimeType = mimeType ?: detectMimeType(fileName)
            chosenTree()?.let { tree ->
                return@withContext openTreeDirect(tree, relativePath, fileName, resolvedMimeType, sizeBytes)
            }
            // A received folder keeps its structure (ADR-064); a loose file goes by type.
            val relativeDir = ReceivedFolders.dirFor(relativePath, resolvedMimeType)
            val resolver = context.contentResolver
            val uri = runCatching { resolver.insert(downloads(), pendingValues(fileName, resolvedMimeType, relativeDir)) }
                .onFailure { VeyroLog.w(TAG) { "Direct insert failed (${it.javaClass.simpleName})" } }
                .getOrNull() ?: return@withContext null
            val pfd = runCatching { resolver.openFileDescriptor(uri, "rw") }
                .onFailure { VeyroLog.w(TAG) { "Direct open failed (${it.javaClass.simpleName})" } }
                .getOrNull()
            if (pfd == null) {
                runCatching { resolver.delete(uri, null, null) }
                return@withContext null
            }
            MediaStoreTarget(uri, pfd, fileName, resolvedMimeType, relativeDir, sizeBytes)
        }

    /**
     * Removes pending entries Veyro left in its folders when a receive was killed mid-way
     * (ADR-049). Only entries older than [startedAtSeconds], so a receive that began after this
     * process started is never touched. MediaStore would remove them after 7 days anyway, but a
     * half-received 4 GB video should not hold space that long. Android 11+; returns how many.
     */
    public suspend fun deleteAbandonedPending(startedAtSeconds: Long): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext 0
        val resolver = context.contentResolver
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.MediaColumns.DATE_ADDED} < ?",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(context.packageName, "${ReceivedFolders.ROOT}/%", startedAtSeconds.toString()),
            )
        }
        var removed = 0
        runCatching {
            resolver.query(downloads(), arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(downloads(), c.getLong(0))
                    if (runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)) removed++
                }
            }
        }.onFailure { VeyroLog.w(TAG) { "Pending sweep failed: ${it.javaClass.simpleName}" } }
        if (removed > 0) VeyroLog.i(TAG) { "Removed $removed unfinished receive(s)" }
        removed
    }

    private fun downloads(): Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun pendingValues(fileName: String, mimeType: String, relativeDir: String): ContentValues =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }

    /** One pending entry written in place by positional reads and writes on its descriptor. */
    private inner class MediaStoreTarget(
        private val uri: Uri,
        private val pfd: ParcelFileDescriptor,
        private val fileName: String,
        private val mimeType: String,
        private val relativeDir: String,
        private val sizeBytes: Long,
    ) : DirectReceiveTarget {
        // Positional I/O (pwrite/pread) on one descriptor; neither stream owns it, pfd does.
        private val writer: FileChannel = FileOutputStream(pfd.fileDescriptor).channel
        private val reader: FileChannel = FileInputStream(pfd.fileDescriptor).channel
        private val closed = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)

        override fun write(position: Long, data: ByteArray) {
            val buffer = ByteBuffer.wrap(data)
            var at = position
            while (buffer.hasRemaining()) at += writer.write(buffer, at)
        }

        override fun read(position: Long, buffer: ByteArray, length: Int): Int =
            reader.read(ByteBuffer.wrap(buffer, 0, length), position)

        override suspend fun commit(): StoragePublishResult = withContext(Dispatchers.IO) {
            val size = runCatching { pfd.statSize }.getOrDefault(-1L)
            close()
            if (size != sizeBytes) {
                abort()
                return@withContext StoragePublishResult.Failed("Stored $size bytes, expected $sizeBytes")
            }
            try {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (context.contentResolver.update(uri, values, null, null) <= 0) throw IOException("update matched no row")
                finished.set(true)
                VeyroLog.i(TAG) {
                    "[diagnostics:mediastore] item committed in place: size=$sizeBytes bytes, uri=$uri, dir=$relativeDir, mime=$mimeType"
                }
                StoragePublishResult.Success(
                    destinationUri = uri.toString(),
                    displayName = fileName,
                    mimeType = mimeType,
                    bytesWritten = sizeBytes,
                )
            } catch (t: Throwable) {
                VeyroLog.e(TAG) { "Finishing the entry failed (${t.javaClass.simpleName})" }
                abort()
                StoragePublishResult.Failed("Could not finish the MediaStore entry: ${t.javaClass.simpleName}", t)
            }
        }

        override fun abort() {
            close()
            if (finished.compareAndSet(false, true)) runCatching { context.contentResolver.delete(uri, null, null) }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) runCatching { pfd.close() }
        }
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
        relativeDir: String,
    ): StoragePublishResult {
        val contentResolver = context.contentResolver

        // One Veyro folder for everything (Download/Veyro/Photos, /Videos, ...). The Downloads
        // collection is the only one that accepts every type; MediaStore still indexes images,
        // videos and audio there by MIME type, so they show in Gallery and music apps as before.
        val collectionUri = downloads()
        val values = pendingValues(fileName, mimeType, relativeDir)

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

            // One pass (ADR-049). The source was hash-verified moments ago; a failed write
            // throws, and the byte count is checked below. The read-back and second source hash
            // this used to do tripled the time (53 s for a 4 GB video) and caught nothing more.
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

            contentResolver.update(insertedUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)

            pendingUri = null // Completed
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

    /**
     * The folder chosen in Settings when Veyro still holds its grant and it still exists; null for
     * Download/Veyro (nothing chosen, or the folder is gone, which [onReceiveFolderLost] hears).
     */
    private suspend fun chosenTree(): ReceiveTreeAccess? {
        val chosen = runCatching { receiveFolderUri?.invoke() }.getOrNull() ?: return null
        val access = runCatching { ReceiveTreeAccess(context, Uri.parse(chosen)) }.getOrNull()
        val granted = access?.granted() == true
        val target = ReceiveTree.target(chosen, granted, granted && access?.reachable() == true)
        return when (target) {
            ReceiveTree.Target.CHOSEN -> access
            ReceiveTree.Target.DEFAULT -> null
            ReceiveTree.Target.FALLBACK -> {
                VeyroLog.w(TAG) { "Chosen folder not available; this receive goes to ${ReceivedFolders.ROOT}" }
                runCatching { onReceiveFolderLost() }
                null
            }
        }
    }

    /** ADR-072: a `<name>.veyropart` document written at positions, like [MediaStoreTarget]. */
    private fun openTreeDirect(
        tree: ReceiveTreeAccess,
        relativePath: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
    ): DirectReceiveTarget? {
        val dirs = ReceiveTree.dirSegments(relativePath, mimeType) ?: return null
        val dirId = tree.dir(dirs, create = true) ?: return null
        val part = tree.createPart(dirId, fileName, mimeType) ?: return null
        val pfd = tree.open(part.uri, "rw")
        if (pfd == null || !ReceiveTreeAccess.isRegularFile(pfd)) {
            // A cloud provider's pipe: stage it, then stream it in once verified (publish).
            runCatching { pfd?.close() }
            tree.delete(part.uri)
            return null
        }
        if (!ReceiveTree.hasRoom(ReceiveTreeAccess.freeBytes(pfd), sizeBytes)) {
            VeyroLog.w(TAG) { "Not enough space in the chosen folder for $sizeBytes bytes" }
            runCatching { pfd.close() }
            tree.delete(part.uri)
            return null
        }
        return TreeTarget(tree, part, pfd, mimeType, sizeBytes)
    }

    /** ADR-072: a staged, verified file streamed into the chosen folder, then renamed into place. */
    private fun publishToTree(
        tree: ReceiveTreeAccess,
        sourceFile: File,
        relativePath: String,
        mimeType: String,
        sizeBytes: Long,
    ): StoragePublishResult {
        val dirs = ReceiveTree.dirSegments(relativePath, mimeType) ?: return StoragePublishResult.Failed("PATH_REJECTED")
        val fileName = relativePath.substringAfterLast('/')
        val dirId = tree.dir(dirs, create = true) ?: return StoragePublishResult.Failed("Could not make the folder in the chosen folder")
        val part = tree.createPart(dirId, fileName, mimeType) ?: return StoragePublishResult.Failed("Could not create the file in the chosen folder")
        return try {
            val pfd = tree.open(part.uri, "w") ?: throw IOException("could not open the new file")
            var total = 0L
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                if (!ReceiveTree.hasRoom(ReceiveTreeAccess.freeBytes(pfd), sizeBytes)) throw IOException("not enough space")
                sourceFile.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        total += read
                    }
                    output.flush()
                }
            }
            if (sizeBytes > 0 && total != sizeBytes) throw IOException("wrote $total bytes, expected $sizeBytes")
            val (uri, name) = tree.finish(part) ?: throw IOException("could not rename into place")
            sourceFile.delete()
            VeyroLog.i(TAG) { "[diagnostics:tree] item saved in the chosen folder: size=$total bytes, mime=$mimeType" }
            StoragePublishResult.Success(destinationUri = uri.toString(), displayName = name, mimeType = mimeType, bytesWritten = total)
        } catch (t: Throwable) {
            VeyroLog.e(TAG) { "Saving into the chosen folder failed (${t.javaClass.simpleName})" }
            tree.delete(part.uri)
            StoragePublishResult.Failed("Failed writing to the chosen folder: ${t.javaClass.simpleName}", t)
        }
    }

    /** One `.veyropart` document in the chosen folder, written in place; renamed on [commit]. */
    private inner class TreeTarget(
        private val tree: ReceiveTreeAccess,
        private val part: ReceiveTreeAccess.Part,
        private val pfd: ParcelFileDescriptor,
        private val mimeType: String,
        private val sizeBytes: Long,
    ) : DirectReceiveTarget {
        private val writer: FileChannel = FileOutputStream(pfd.fileDescriptor).channel
        private val reader: FileChannel = FileInputStream(pfd.fileDescriptor).channel
        private val closed = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)

        override fun write(position: Long, data: ByteArray) {
            val buffer = ByteBuffer.wrap(data)
            var at = position
            while (buffer.hasRemaining()) at += writer.write(buffer, at)
        }

        override fun read(position: Long, buffer: ByteArray, length: Int): Int =
            reader.read(ByteBuffer.wrap(buffer, 0, length), position)

        override suspend fun commit(): StoragePublishResult = withContext(Dispatchers.IO) {
            val size = runCatching { pfd.statSize }.getOrDefault(-1L)
            close()
            if (size != sizeBytes) {
                abort()
                return@withContext StoragePublishResult.Failed("Stored $size bytes, expected $sizeBytes")
            }
            val done = tree.finish(part)
            if (done == null) {
                abort()
                return@withContext StoragePublishResult.Failed("Could not rename into place in the chosen folder")
            }
            finished.set(true)
            VeyroLog.i(TAG) { "[diagnostics:tree] item committed in place in the chosen folder: size=$sizeBytes bytes, mime=$mimeType" }
            StoragePublishResult.Success(
                destinationUri = done.first.toString(),
                displayName = done.second,
                mimeType = mimeType,
                bytesWritten = sizeBytes,
            )
        }

        override fun abort() {
            close()
            if (finished.compareAndSet(false, true)) tree.delete(part.uri)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) runCatching { pfd.close() }
        }
    }
}