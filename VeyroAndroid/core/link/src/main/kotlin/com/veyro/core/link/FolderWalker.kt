package com.veyro.core.link

import com.veyro.core.protocol.RelativePath
import java.io.IOException
import java.io.InputStream

/**
 * A folder the user picked, as the platform lists it. On Android this is a Storage Access
 * Framework tree read with `DocumentsContract` (ADR-064), so no storage permission is needed.
 */
public interface DocumentTree {
    /** The entries of directory [documentId]. Throws when it cannot be listed. */
    public fun children(documentId: String): List<DocumentEntry>

    /** Opens document [documentId] for reading. */
    public fun open(documentId: String): InputStream
}

/** One entry of a [DocumentTree] directory. The name is user data: shown, never logged. */
public data class DocumentEntry(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    /** Null when the provider does not say; such a file cannot be sent exactly. */
    val sizeBytes: Long?,
    val lastModifiedEpochMillis: Long,
    /** No byte stream behind it (a cloud document, say), so there is nothing to send. */
    val isVirtual: Boolean = false,
)

/** What [FolderWalker.walk] found. */
public data class FolderWalk(
    /** The top folder's name as sent, sanitised. */
    val name: String,
    /** Files first, then empty directories; every path starts with [name]. */
    val items: List<OutgoingFile>,
    val fileCount: Int,
    val emptyDirectoryCount: Int,
    val totalBytes: Long,
    /** Entries left out: unreadable, of unknown size, virtual, too deep or with a path too long. */
    val skipped: Int,
    /** More than [FolderWalker.MAX_ITEMS] entries; [items] is then empty and nothing is sent. */
    val tooLarge: Boolean,
)

/**
 * Turns a picked folder into the items of one transfer (ADR-064).
 *
 * - The folder's own name is the top segment; everything below keeps its relative path.
 * - Every segment goes through [RelativePath.sanitizeOutgoingSegment], and two names that end up
 *   equal ignoring case get " (2)" and so on, because Windows and Android shared storage would
 *   otherwise write both to one file.
 * - A path that would fail the receiver's rules (04-PROTOCOL.md section 8: 64 segments, 1024
 *   bytes) is left out and counted, as is anything unreadable, virtual or of unknown size.
 * - An empty directory becomes a directory item, so it exists on the other side too.
 * - A directory is visited once, so a provider that lists a folder inside itself cannot loop.
 */
public object FolderWalker {
    /** Files plus empty directories in one folder send; the offer stays well under 24 MB. */
    public const val MAX_ITEMS: Int = 10_000

    public fun walk(tree: DocumentTree, rootId: String, rootName: String): FolderWalk =
        Walk(tree).run(rootId, RelativePath.sanitizeOutgoingSegment(rootName))

    private class Walk(private val tree: DocumentTree) {
        private val files = ArrayList<OutgoingFile>()
        private val directories = ArrayList<OutgoingFile>()
        private val visited = HashSet<String>()
        private var skipped = 0
        private var tooLarge = false

        fun run(rootId: String, top: String): FolderWalk {
            visit(rootId, top)
            val items = if (tooLarge) emptyList() else files + directories
            return FolderWalk(
                name = top,
                items = items,
                fileCount = if (tooLarge) 0 else files.size,
                emptyDirectoryCount = if (tooLarge) 0 else directories.size,
                totalBytes = if (tooLarge) 0L else files.sumOf { it.sizeBytes },
                skipped = skipped,
                tooLarge = tooLarge,
            )
        }

        /** Walks [documentId] at [path]; true when it holds something that is sent or skipped. */
        private fun visit(documentId: String, path: String): Boolean {
            if (!visited.add(documentId)) return true
            val children = try {
                tree.children(documentId)
            } catch (e: Exception) {
                skipped++
                return true
            }
            val names = HashSet<String>()
            var holdsSomething = false
            for (entry in children.sortedBy { it.name.lowercase() }) {
                if (tooLarge) return true
                if (files.size + directories.size >= MAX_ITEMS) {
                    tooLarge = true
                    return true
                }
                holdsSomething = true
                val name = unique(RelativePath.sanitizeOutgoingSegment(entry.name), names)
                val child = "$path/$name"
                if (!RelativePath.isValid(child)) {
                    skipped++
                    continue
                }
                if (entry.isDirectory) {
                    if (!visit(entry.documentId, child)) directories += directory(name, child, entry)
                    continue
                }
                val size = entry.sizeBytes
                if (entry.isVirtual || size == null || size < 0) {
                    skipped++
                    continue
                }
                files += OutgoingFile(
                    effectiveName = name,
                    sizeBytes = size,
                    lastModifiedEpochMillis = entry.lastModifiedEpochMillis,
                    relativePath = child,
                    streamed = true,
                    openStream = { tree.open(entry.documentId) },
                )
            }
            return holdsSomething
        }

        private fun directory(name: String, path: String, entry: DocumentEntry) = OutgoingFile(
            effectiveName = name,
            sizeBytes = 0L,
            lastModifiedEpochMillis = entry.lastModifiedEpochMillis,
            relativePath = path,
            isDirectory = true,
            openStream = { throw IOException("A directory has no data") },
        )

        /** [name], or "name (2).ext" and so on when the folder already has it, ignoring case. */
        private fun unique(name: String, taken: MutableSet<String>): String {
            if (taken.add(name.lowercase())) return name
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val extension = if (dot > 0) name.substring(dot) else ""
            var n = 2
            while (true) {
                val candidate = "$stem ($n)$extension"
                if (taken.add(candidate.lowercase())) return candidate
                n++
            }
        }
    }
}

/** "Photos folder, 120 files": the banner, notification and prompt wording for a folder. */
public fun folderSummary(name: String, fileCount: Int): String =
    "$name folder, " + if (fileCount == 1) "1 file" else "$fileCount files"
