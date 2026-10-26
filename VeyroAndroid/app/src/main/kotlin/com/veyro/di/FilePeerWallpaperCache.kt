package com.veyro.di

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.companion.PeerWallpaperCache
import com.veyro.core.companion.WallpaperEncoding
import com.veyro.core.model.DeviceId
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The last wallpaper each PC sent (ADR-038), one `<deviceId>.jpg` per PC in the app's cache, so
 * Home can draw the PC while it is disconnected. Cache, not files: the OS may reclaim it and the
 * PC sends it again on the next connection.
 *
 * The file name comes from the network (the session's peer id), so it is accepted only if it
 * parses as a [DeviceId], which allows Crockford base32 characters only: no separators, no
 * traversal (04-PROTOCOL.md section 7). Content is checked by [WallpaperEncoding.acceptsIncoming]
 * before it gets here.
 */
internal class FilePeerWallpaperCache(
    private val directory: File,
    private val dispatchers: AppDispatchers,
) : PeerWallpaperCache {

    private companion object {
        const val TAG = "WallpaperCache"
        const val SUFFIX = ".jpg"
    }

    override suspend fun loadAll(): Map<String, ByteArray> = withContext(dispatchers.io) {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }.orEmpty()
        files.mapNotNull { file ->
            val id = file.name.removeSuffix(SUFFIX)
            if (DeviceId.parseOrNull(id) == null) return@mapNotNull null
            runCatching { id to file.readBytes() }.getOrNull()
        }.toMap()
    }

    override suspend fun save(deviceId: String, jpeg: ByteArray?): Unit = withContext(dispatchers.io) {
        val id = DeviceId.parseOrNull(deviceId)?.value ?: return@withContext
        val target = File(directory, id + SUFFIX)
        if (jpeg == null) {
            target.delete()
            return@withContext
        }
        directory.mkdirs()
        val tmp = File(directory, "$id.tmp")
        tmp.writeBytes(jpeg)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                VeyroLog.w(TAG) { "Could not store a PC wallpaper" }
            }
        }
    }
}
