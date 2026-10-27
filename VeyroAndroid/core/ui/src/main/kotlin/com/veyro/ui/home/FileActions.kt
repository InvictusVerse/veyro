package com.veyro.ui.home

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.veyro.ui.format.ReceivedFolders
import java.io.File

/**
 * Intents for the Recent card's menu and the Received files row. Each returns false when no app
 * on the phone can handle it, so the caller can fall back. File names never reach the log.
 */
internal object FileActions {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun open(context: Context, uriString: String): Boolean = start(context) {
        val uri = Uri.parse(uriString)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
        Intent.createChooser(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Open with",
        )
    }

    fun share(context: Context, uriString: String): Boolean = start(context) {
        val uri = Uri.parse(uriString)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share",
        )
    }

    /**
     * Opens the folder the file was saved to in the Files app, falling back to the Downloads app.
     * MediaStore offers no "reveal this file" intent, so the folder is the closest equivalent.
     */
    fun showInFolder(context: Context, fileName: String, uriString: String?): Boolean {
        val folder = mediaStoreFolder(context, uriString) ?: folderFor(fileName, uriString)
        return openFolder(context, folder)
    }

    /** The Received files row: the folder received files land in by default. */
    fun openReceivedFolder(context: Context): Boolean = openFolder(context, ReceivedFolders.ROOT)

    private fun openFolder(context: Context, relativeFolder: String): Boolean {
        val documentUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$relativeFolder")
        if (start(context) { Intent(Intent.ACTION_VIEW).setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR) }) {
            return true
        }
        return start(context) { Intent(DownloadManager.ACTION_VIEW_DOWNLOADS) }
    }

    /** "Pictures/Veyro" for a MediaStore image; the parent of a `file://` path under shared storage. */
    internal fun folderFor(fileName: String, uriString: String?): String {
        val uri = uriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri?.scheme == "file") {
            val parent = uri.path?.let { File(it).parentFile?.absolutePath }
            val root = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()
            if (parent != null && root != null && parent.startsWith(root)) {
                return parent.removePrefix(root).trim('/').ifEmpty { "Download" }
            }
        }
        // A content:// row: ask MediaStore where it really is (older files are still in Pictures/Veyro etc.).
        return ReceivedFolders.pathFor(fileName)
    }

    /** The folder a MediaStore item lives in, from its RELATIVE_PATH; null if it cannot be read. */
    internal fun mediaStoreFolder(context: Context, uriString: String?): String? {
        val uri = uriString?.let { runCatching { Uri.parse(it) }.getOrNull() }?.takeIf { it.scheme == "content" } ?: return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.trim('/') else null
            }
        }.getOrNull()
    }

    private inline fun start(context: Context, build: () -> Intent): Boolean = try {
        context.startActivity(build().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
