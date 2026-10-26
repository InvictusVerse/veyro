package com.veyro.ui.format

/**
 * Size formatting for transfer UI.
 *
 * Binary units (KiB-sized 1024 steps) with decimal labels, which is what Android's own file manager
 * and every other file app on the device report. Using decimal 1000-steps would make Veyro disagree
 * with the Files app about the size of the same file, and the user would reasonably conclude that
 * something was wrong with the transfer.
 *
 * Lives in `:core:ui` rather than in a feature module because more than one feature shows sizes, and
 * a feature must never depend on another feature (ProjectInfo/03-ARCHITECTURE.md rule 2).
 */
public fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

/**
 * A rough time estimate.
 *
 * Returns null rather than "0 seconds" when the rate is unknown, because a confidently wrong ETA is
 * worse than no ETA - users plan around it.
 */
public fun formatEta(seconds: Long?): String? = when {
    seconds == null || seconds <= 0 -> null
    seconds < 60 -> "${seconds}s left"
    seconds < 3600 -> "${seconds / 60}m left"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m left"
}

public fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0 -> "0 B/s"
    bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
    bytesPerSecond < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024.0)
    bytesPerSecond < 1024L * 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024))
    else -> "%.2f GB/s".format(bytesPerSecond / (1024.0 * 1024 * 1024))
}
