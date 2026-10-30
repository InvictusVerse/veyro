package com.veyro.ui.format

/**
 * Where received files go: one Veyro folder in Download, sorted into subfolders by type.
 * `AndroidMediaStorePublisher`, the destination labels and the Received files row all read this,
 * so they cannot drift apart.
 */
public object ReceivedFolders {
    /** Relative to shared storage, as MediaStore's `RELATIVE_PATH` and the Files app write it. */
    public const val ROOT: String = "Download/Veyro"

    /** Every subfolder, for docs and the one-time move of older files. */
    public val ALL: List<String> = listOf("Photos", "Videos", "Music", "Documents", "Archives", "Apps", "Other")

    /** "Photos", "Videos", ... for a file. The MIME type wins when it names a media type. */
    public fun subfolder(fileName: String, mimeType: String? = null): String {
        val ext = fileName.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (ext == "apk" || mimeType == "application/vnd.android.package-archive") return "Apps"
        val kind = when {
            mimeType?.startsWith("image/") == true -> FileKind.IMAGE
            mimeType?.startsWith("video/") == true -> FileKind.VIDEO
            mimeType?.startsWith("audio/") == true -> FileKind.AUDIO
            else -> fileKindOf(fileName)
        }
        return when (kind) {
            FileKind.IMAGE -> "Photos"
            FileKind.VIDEO -> "Videos"
            FileKind.AUDIO -> "Music"
            FileKind.DOCUMENT -> "Documents"
            FileKind.ARCHIVE -> "Archives"
            FileKind.OTHER -> "Other"
        }
    }

    /** "Download/Veyro/Photos" for a file. */
    public fun pathFor(fileName: String, mimeType: String? = null): String = "$ROOT/${subfolder(fileName, mimeType)}"

    /**
     * The folder an item lands in. A loose file goes to its type folder; an item of a received
     * folder keeps the folder's structure ("Trip/day 1/a.jpg" -> "Download/Veyro/Trip/day 1",
     * ADR-064). [relativePath] is already validated (REQ-T11).
     */
    public fun dirFor(relativePath: String, mimeType: String? = null): String {
        val parent = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        return if (parent.isEmpty()) pathFor(relativePath, mimeType) else "$ROOT/$parent"
    }

    /** A received folder is never saved into a type folder: "Photos" arrives as "Photos (2)". */
    public fun isTypeFolder(name: String): Boolean = ALL.any { it.equals(name, ignoreCase = true) }
}
