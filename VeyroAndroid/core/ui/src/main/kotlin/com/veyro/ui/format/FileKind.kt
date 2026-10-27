package com.veyro.ui.format

import java.io.File

/** Coarse file type, for a row icon and the destination folder. Decided by extension only. */
public enum class FileKind { IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, OTHER }

private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "dng", "raw", "svg")
private val VIDEO = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts")
private val AUDIO = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "amr")
private val DOCUMENT = setOf(
    "pdf", "txt", "md", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "epub",
)
private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "apk")

public fun fileKindOf(name: String): FileKind {
    val ext = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    return when (ext) {
        in IMAGE -> FileKind.IMAGE
        in VIDEO -> FileKind.VIDEO
        in AUDIO -> FileKind.AUDIO
        in DOCUMENT -> FileKind.DOCUMENT
        in ARCHIVE -> FileKind.ARCHIVE
        else -> FileKind.OTHER
    }
}

/**
 * Where received files will appear, in the words the Files app uses: "Download/Veyro/Photos", or
 * "Download/Veyro (Photos and Videos)" for a mix. Mirrors [ReceivedFolders], unless the user chose
 * a folder in Settings.
 */
public fun destinationLabel(names: List<String>, customDestination: File?): String {
    if (customDestination != null) return customDestination.name.ifBlank { customDestination.path }
    val folders = names.map { ReceivedFolders.subfolder(it) }.distinct()
    return when (folders.size) {
        0 -> ReceivedFolders.ROOT
        1 -> "${ReceivedFolders.ROOT}/${folders[0]}"
        2 -> "${ReceivedFolders.ROOT} (${folders[0]} and ${folders[1]})"
        else -> "${ReceivedFolders.ROOT} (" + folders.dropLast(1).joinToString(", ") + " and " + folders.last() + ")"
    }
}

/** "0:42" for a countdown. */
public fun formatCountdown(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
