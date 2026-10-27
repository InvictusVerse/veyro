package com.veyro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.veyro.core.common.VeyroLog
import com.veyro.core.link.OutgoingFile
import com.veyro.core.protocol.RelativePath
import java.io.IOException

/** Turns a picked or shared `content://` URI into something sendable. */
internal object SharedUris {
    private const val TAG = "SharedUris"

    /**
     * The display name and size come from the content provider, not from the URI path: a
     * `content://` URI frequently has no meaningful path at all. A provider that reports neither is
     * **skipped rather than guessed at**, because an item with an unknown size breaks the chunk plan
     * and would fail at the far end as a hash mismatch - which the user would read as a network
     * problem.
     *
     * The stream is opened lazily, each time, rather than being held open: a selection can wait a
     * while before the send starts, and an open descriptor that long is a leak and a liability if
     * the provider revokes access.
     */
    fun describe(context: Context, uri: Uri): OutgoingFile? {
        val resolver = context.contentResolver
        // Persist the read grant when the provider allows it, so the URI still resolves later.
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure {
                // Not fatal: most providers grant only a transient permission, valid while the
                // component that received it (here, the share service) is alive.
                VeyroLog.d(TAG) { "No persistable permission for a file: ${it.javaClass.simpleName}" }
            }

        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }.onFailure { VeyroLog.w(TAG, it) { "Could not query a file" } }

        val rawName = name ?: uri.lastPathSegment?.substringAfterLast('/')
        if (rawName.isNullOrBlank() || size < 0) {
            VeyroLog.w(TAG) { "Skipping a file with no reported name or size" }
            return null
        }

        // Sanitised here so what the user sees is what goes on the wire. A courtesy, not a security
        // control: the receiver validates regardless (REQ-T11).
        val safeName = RelativePath.sanitizeOutgoingSegment(rawName)
        return OutgoingFile(
            effectiveName = safeName,
            sizeBytes = size,
            lastModifiedEpochMillis = System.currentTimeMillis(),
            openStream = { resolver.openInputStream(uri) ?: throw IOException("could not open $safeName") },
        )
    }
}
