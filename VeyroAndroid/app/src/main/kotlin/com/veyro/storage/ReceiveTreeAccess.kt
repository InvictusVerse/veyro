package com.veyro.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os
import android.system.OsConstants
import com.veyro.ui.format.ReceiveTree

/**
 * The folder the user picked in Settings (ADR-072), reached only through DocumentsContract and
 * the persisted tree grant: no storage permission. Directories are found by child name from the
 * tree's root, one validated segment at a time, so nothing can be created outside the tree.
 * URIs and names are user data: this class never logs.
 */
internal class ReceiveTreeAccess(context: Context, private val treeUri: Uri) {

    private val resolver: ContentResolver = context.contentResolver

    /** Throws for a URI that is not a tree; the caller treats that as "folder gone". */
    val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /** A file being written: [uri] is `<name>.veyropart` when [renameOnFinish], else the final name. */
    class Part(val uri: Uri, val dirId: String, val name: String, val renameOnFinish: Boolean)

    private data class Child(val id: String, val name: String, val isDir: Boolean)

    /** Veyro still holds read and write access to the tree. */
    fun granted(): Boolean = runCatching {
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission && it.isWritePermission }
    }.getOrDefault(false)

    /** The tree's root still exists and is a directory. */
    fun reachable(): Boolean = runCatching {
        resolver.query(docUri(rootId), arrayOf(Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            c.moveToFirst() && c.getString(0) == Document.MIME_TYPE_DIR
        } == true
    }.getOrDefault(false)

    /** Whether the tree's root has an entry named [name] (any case). */
    fun rootHas(name: String): Boolean = children(rootId).any { it.name.equals(name, ignoreCase = true) }

    /**
     * The directory at [segments] below the root, made where missing when [create]. Null when it
     * is missing (and not [create]) or cannot be made. Segments are already validated (REQ-T11).
     */
    fun dir(segments: List<String>, create: Boolean): String? {
        var id = rootId
        for (segment in segments) {
            val kids = children(id)
            val found = kids.firstOrNull { it.isDir && it.name == segment }
                ?: kids.firstOrNull { it.isDir && it.name.equals(segment, ignoreCase = true) }
            id = when {
                found != null -> found.id
                !create -> return null
                else -> createDocument(id, Document.MIME_TYPE_DIR, segment)?.let(DocumentsContract::getDocumentId) ?: return null
            }
        }
        return id
    }

    /**
     * A new hidden-by-name `<name>.veyropart` in [dirId], renamed by [finish]. A provider that
     * cannot rename gets the final, unused name at once instead; [abort] still removes it.
     */
    fun createPart(dirId: String, name: String, mimeType: String): Part? {
        // octet-stream, so the provider keeps the .veyropart name instead of adding an extension.
        val part = createDocument(dirId, OCTET_STREAM, ReceiveTree.partName(name)) ?: return null
        if ((flagsOf(part) and Document.FLAG_SUPPORTS_RENAME) != 0) return Part(part, dirId, name, renameOnFinish = true)
        delete(part)
        val direct = createDocument(dirId, mimeType, ReceiveTree.freeName(name, childNames(dirId))) ?: return null
        return Part(direct, dirId, name, renameOnFinish = false)
    }

    /**
     * Gives a finished [part] its final name, " (2)" and so on when the name is taken: an existing
     * file is never overwritten. Returns the document and the name it ended with; null on failure.
     */
    fun finish(part: Part): Pair<Uri, String>? {
        if (!part.renameOnFinish) return part.uri to (displayName(part.uri) ?: part.name)
        val own = ReceiveTree.partName(part.name)
        val taken = childNames(part.dirId).filterNot { it == own }
        val wanted = ReceiveTree.freeName(part.name, taken)
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, part.uri, wanted) }
            .getOrElse { return null }
            ?: part.uri // null: the document kept its id
        val finalName = displayName(renamed) ?: return null
        if (finalName.endsWith(ReceiveTree.PART_SUFFIX)) return null
        return renamed to finalName
    }

    fun delete(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }

    fun open(uri: Uri, mode: String): ParcelFileDescriptor? = runCatching { resolver.openFileDescriptor(uri, mode) }.getOrNull()

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun createDocument(parentId: String, mimeType: String, name: String): Uri? =
        runCatching { DocumentsContract.createDocument(resolver, docUri(parentId), mimeType, name) }.getOrNull()

    private fun childNames(dirId: String): List<String> = children(dirId).map { it.name }

    private fun children(dirId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val out = ArrayList<Child>()
        runCatching {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    out += Child(id, name, c.getString(2) == Document.MIME_TYPE_DIR)
                }
            }
        }
        return out
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun flagsOf(uri: Uri): Int = runCatching {
        resolver.query(uri, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        } ?: 0
    }.getOrDefault(0)

    companion object {
        const val OCTET_STREAM = "application/octet-stream"

        /** Free bytes on the volume behind [pfd]; null when the provider's descriptor cannot say. */
        fun freeBytes(pfd: ParcelFileDescriptor): Long? = runCatching {
            val st = Os.fstatvfs(pfd.fileDescriptor)
            st.f_bavail * st.f_frsize
        }.getOrNull()

        /** A real file, so positional reads and writes work (a pipe from a cloud provider does not). */
        fun isRegularFile(pfd: ParcelFileDescriptor): Boolean = runCatching {
            OsConstants.S_ISREG(Os.fstat(pfd.fileDescriptor).st_mode)
        }.getOrDefault(false)
    }
}
