package com.veyro.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.veyro.core.common.VeyroLog

/**
 * The persisted Storage Access Framework grant behind "Save received files to" (ADR-072). The
 * folder is reached only through this grant, so Veyro needs no storage permission. URIs and folder
 * names are user data: never logged.
 */
internal object ReceiveFolderGrants {

    private const val TAG = "ReceiveFolder"
    private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Keeps read and write access to [tree] across restarts. False when the picker's grant cannot be kept. */
    fun take(context: Context, tree: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(tree, FLAGS)
        true
    }.onFailure { VeyroLog.w(TAG) { "Folder grant not kept (${it.javaClass.simpleName})" } }.getOrDefault(false)

    /** Gives the grant back, so the folder no longer shows under Veyro's access in system settings. */
    fun release(context: Context, uriString: String?) {
        val uri = uriString?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, FLAGS) }
            .onFailure { VeyroLog.w(TAG) { "Folder grant not released (${it.javaClass.simpleName})" } }
    }

    /** The picked folder's display name, or null when the provider does not say. */
    fun nameOf(context: Context, tree: Uri): String? = runCatching {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
