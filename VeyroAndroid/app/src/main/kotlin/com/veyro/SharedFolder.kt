package com.veyro

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.veyro.core.link.DocumentEntry
import com.veyro.core.link.DocumentTree
import java.io.IOException
import java.io.InputStream

/**
 * A folder picked with `ACTION_OPEN_DOCUMENT_TREE`, read through `DocumentsContract` (ADR-064).
 * The tree grant is all it needs: no storage permission. One query per directory, with only the
 * columns the walk uses, so a folder of thousands of files lists in seconds. Names and paths are
 * user data and are never logged.
 */
internal class SharedFolder(private val context: Context, private val treeUri: Uri) : DocumentTree {

    val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /** The folder's display name; the last part of its document id when the provider has none. */
    fun rootName(): String {
        val named = runCatching {
            context.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId),
                arrayOf(Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return named?.takeIf { it.isNotBlank() }
            ?: rootId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
    }

    override fun children(documentId: String): List<DocumentEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = context.contentResolver.query(uri, COLUMNS, null, null, null)
            ?: throw IOException("The folder could not be listed")
        return cursor.use { c ->
            val entries = ArrayList<DocumentEntry>(c.count)
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val flags = if (c.isNull(5)) 0 else c.getInt(5)
                entries += DocumentEntry(
                    documentId = id,
                    name = c.getString(1).orEmpty(),
                    isDirectory = c.getString(2) == Document.MIME_TYPE_DIR,
                    sizeBytes = if (c.isNull(3)) null else c.getLong(3),
                    lastModifiedEpochMillis = if (c.isNull(4)) 0L else c.getLong(4),
                    isVirtual = flags and Document.FLAG_VIRTUAL_DOCUMENT != 0,
                )
            }
            entries
        }
    }

    override fun open(documentId: String): InputStream =
        context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId))
            ?: throw IOException("The file could not be opened")

    private companion object {
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
