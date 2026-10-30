package com.veyro.ui.format

import com.veyro.core.protocol.RelativePath

/**
 * The pure rules for receiving into a folder the user picked in Settings (a Storage Access
 * Framework tree, ADR-072). `AndroidMediaStorePublisher` does the ContentResolver calls; every
 * decision about names and paths is made here, so it is tested on the JVM.
 *
 * Nothing can leave the tree: the tree is walked by child name from its root, one validated
 * segment at a time (REQ-T11), and a segment can never hold a `/`.
 */
/** The folder picked in Settings: its tree URI (user data, never logged) and its name. */
public data class ChosenReceiveFolder(val uri: String, val name: String)

public object ReceiveTree {

    /** "Everything you receive is in ...", for Home's Received files row. */
    public fun receivedRowText(chosen: ChosenReceiveFolder?): String =
        "Everything you receive is in ${chosen?.name ?: ReceivedFolders.ROOT}, sorted into Photos, Videos, Music, Documents and more."

    /** A file being received is named `<name>.veyropart` until its hash matched. */
    public const val PART_SUFFIX: String = ".veyropart"

    /** What the Settings row shows when no folder is chosen. */
    public const val DEFAULT_LABEL: String = "${ReceivedFolders.ROOT} (default)"

    /** Shown once in Settings after a receive fell back to Download/Veyro. */
    public const val LOST_NOTICE: String =
        "The chosen folder is no longer available; using ${ReceivedFolders.ROOT}"

    /** Where one receive goes. */
    public enum class Target {
        /** No folder chosen: Download/Veyro through MediaStore. */
        DEFAULT,

        /** The chosen folder. */
        CHOSEN,

        /** A folder was chosen but its grant is gone or it was deleted: Download/Veyro, and say so. */
        FALLBACK,
    }

    public fun target(chosenUri: String?, granted: Boolean, reachable: Boolean): Target = when {
        chosenUri.isNullOrBlank() -> Target.DEFAULT
        granted && reachable -> Target.CHOSEN
        else -> Target.FALLBACK
    }

    /** "Download/Veyro (default)" or the chosen folder's name. */
    public fun label(chosenName: String?): String = chosenName?.takeIf { it.isNotBlank() } ?: DEFAULT_LABEL

    /** The validated segments of [relativePath], or null when it fails REQ-T11. */
    public fun segments(relativePath: String): List<String>? {
        val result = RelativePath.validate(relativePath) as? RelativePath.Result.Valid ?: return null
        val parts = result.segments
        if (parts.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." || '/' in it || '\\' in it }) return null
        return parts
    }

    /**
     * The folders inside the tree an item is saved in: its type folder for a loose file
     * (`["Photos"]`), the received folder's structure for an item of a folder
     * (`["Trip", "Day 1"]`, ADR-064). Null when the path is not valid.
     */
    public fun dirSegments(relativePath: String, mimeType: String?): List<String>? {
        val parts = segments(relativePath) ?: return null
        val dirs = parts.dropLast(1)
        return dirs.ifEmpty { listOf(ReceivedFolders.subfolder(parts.last(), mimeType)) }
    }

    public fun partName(fileName: String): String = fileName + PART_SUFFIX

    /**
     * [wanted] when no entry in [taken] has that name (compared ignoring case, as FAT and most
     * phone storage do), else "name (2).ext", "name (3).ext" and so on. Never overwrites.
     */
    public fun freeName(wanted: String, taken: Collection<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (wanted.lowercase() !in lower) return wanted
        val dot = wanted.lastIndexOf('.')
        val stem = if (dot > 0) wanted.substring(0, dot) else wanted
        val ext = if (dot > 0) wanted.substring(dot) else ""
        for (n in 2..9_999) {
            val candidate = "$stem ($n)$ext"
            if (candidate.lowercase() !in lower) return candidate
        }
        return "$stem (${System.currentTimeMillis()})$ext"
    }

    /** False only when the free space is known and too small; unknown never blocks a receive. */
    public fun hasRoom(freeBytes: Long?, neededBytes: Long): Boolean =
        freeBytes == null || freeBytes < 0 || neededBytes <= 0 || freeBytes >= neededBytes
}
