package com.veyro.core.companion

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.link.LinkMessage
import com.veyro.core.link.OutgoingFile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [PhotoSource] backed by `MediaStore.Images` (ADR-034).
 *
 * Scope is what Phone Link shows: images whose `RELATIVE_PATH` starts with `DCIM/Camera/`,
 * `Pictures/Screenshots/` or `DCIM/Screenshots/`. Every query, including the per-id ones for
 * thumbnails and originals, carries that selection, so an id outside it reads as unknown. Every
 * read needs [permission] and answers empty without it.
 *
 * File names and images are user data: nothing here logs them.
 */
public class AndroidPhotoSource(
    context: Context,
    private val dispatchers: AppDispatchers,
) : PhotoSource {

    public companion object {
        private const val TAG = "PhotoSource"

        /** Rows scanned for one list; bounds the cost on a very large camera roll. */
        private const val MAX_SCAN_ROWS = 20_000
        private const val MAX_NEW_PER_PASS = 50

        private const val ID = MediaStore.Images.Media._ID
        private const val NAME = MediaStore.Images.Media.DISPLAY_NAME
        private const val TAKEN = MediaStore.Images.Media.DATE_TAKEN
        private const val ADDED = MediaStore.Images.Media.DATE_ADDED
        private const val MODIFIED = MediaStore.Images.Media.DATE_MODIFIED
        private const val WIDTH = MediaStore.Images.Media.WIDTH
        private const val HEIGHT = MediaStore.Images.Media.HEIGHT
        private const val SIZE = MediaStore.Images.Media.SIZE
        private const val MIME = MediaStore.Images.Media.MIME_TYPE
        private const val PATH = MediaStore.Images.Media.RELATIVE_PATH

        private const val CAMERA_PREFIX = "DCIM/Camera/"
        private val SCREENSHOT_PREFIXES = listOf("Pictures/Screenshots/", "DCIM/Screenshots/")

        /** `RELATIVE_PATH` starts with one of the served folders. */
        private val SCOPE = "(" + (listOf(CAMERA_PREFIX) + SCREENSHOT_PREFIXES)
            .joinToString(" OR ") { "$PATH LIKE '$it%'" } + ")"

        private val PROJECTION = arrayOf(ID, NAME, TAKEN, ADDED, WIDTH, HEIGHT, SIZE, MIME, PATH)

        /** The runtime permission photo reading needs on this release. */
        public fun permission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        public fun isGranted(context: Context): Boolean =
            context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED
    }

    private val appContext: Context = context.applicationContext
    private val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    override val changes: Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = try {
            appContext.contentResolver.registerContentObserver(collection, true, observer)
            true
        } catch (e: SecurityException) {
            VeyroLog.w(TAG) { "Photo observer refused: ${e.javaClass.simpleName}" }
            false
        }
        awaitClose { if (registered) appContext.contentResolver.unregisterContentObserver(observer) }
    }

    override fun canRead(): Boolean = isGranted(appContext)

    override suspend fun photos(limit: Int): List<LinkMessage.PhotoInfo> = read(emptyList()) {
        val out = ArrayList<LinkMessage.PhotoInfo>()
        // Sorted by date taken so the newest come first; rows without one (some screenshots) sort
        // last in SQL, so the final order is by the derived timestamp.
        query(PROJECTION, SCOPE, null, "$TAKEN DESC, $ID DESC")?.use { c ->
            var scanned = 0
            while (c.moveToNext() && scanned++ < MAX_SCAN_ROWS) out += info(c)
        }
        out.sortedWith(compareByDescending<LinkMessage.PhotoInfo> { it.ts }.thenByDescending { it.id }).take(limit)
    }

    override suspend fun photosAfter(id: Long): List<LinkMessage.PhotoInfo> = read(emptyList()) {
        val out = ArrayList<LinkMessage.PhotoInfo>()
        query(PROJECTION, "$SCOPE AND $ID > ?", arrayOf(id.toString()), "$ID ASC")?.use { c ->
            while (out.size < MAX_NEW_PER_PASS && c.moveToNext()) out += info(c)
        }
        out
    }

    override suspend fun ids(): Set<Long> = read(emptySet()) {
        val out = HashSet<Long>()
        query(arrayOf(ID), SCOPE, null, null)?.use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        out
    }

    override suspend fun thumbnail(id: Long, size: Int): PhotoThumbnail? = read(null) {
        if (!inScope(id)) return@read null
        val uri = ContentUris.withAppendedId(collection, id)
        val loaded = try {
            appContext.contentResolver.loadThumbnail(uri, Size(size, size), null)
        } catch (e: IOException) {
            return@read null
        }
        // loadThumbnail returns something close to the request, not at most it.
        val longest = max(loaded.width, loaded.height)
        val bitmap = if (longest > size) {
            val scale = size.toFloat() / longest
            Bitmap.createScaledBitmap(
                loaded,
                (loaded.width * scale).roundToInt().coerceAtLeast(1),
                (loaded.height * scale).roundToInt().coerceAtLeast(1),
                true,
            ).also { if (it !== loaded) loaded.recycle() }
        } else {
            loaded
        }
        BitmapThumbnail(bitmap)
    }

    override suspend fun original(id: Long): OutgoingFile? = read(null) {
        query(arrayOf(ID, NAME, SIZE, MODIFIED), "$SCOPE AND $ID = ?", arrayOf(id.toString()), null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: "photo-$id.jpg"
            val bytes = if (c.isNull(2)) -1L else c.getLong(2)
            val modified = if (c.isNull(3)) 0L else c.getLong(3) * 1000L
            val uri = ContentUris.withAppendedId(collection, id)
            OutgoingFile(
                effectiveName = name,
                sizeBytes = bytes,
                lastModifiedEpochMillis = modified,
                openStream = {
                    appContext.contentResolver.openInputStream(uri) ?: throw IOException("photo unavailable")
                },
            )
        }
    }

    private fun inScope(id: Long): Boolean =
        query(arrayOf(ID), "$SCOPE AND $ID = ?", arrayOf(id.toString()), null)?.use { it.moveToFirst() } == true

    private fun info(c: Cursor): LinkMessage.PhotoInfo {
        val taken = if (c.isNull(2)) 0L else c.getLong(2)
        val added = if (c.isNull(3)) 0L else c.getLong(3)
        val path = c.getString(8).orEmpty()
        return LinkMessage.PhotoInfo(
            id = c.getLong(0),
            name = c.getString(1).orEmpty(),
            ts = if (taken > 0L) taken else added * 1000L,
            width = c.intOrNull(4),
            height = c.intOrNull(5),
            size = if (c.isNull(6)) null else c.getLong(6),
            mime = c.getString(7),
            bucket = if (path.startsWith(CAMERA_PREFIX, ignoreCase = true)) "camera" else "screenshots",
        )
    }

    private fun Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index).takeIf { it > 0 }

    private fun query(
        projection: Array<String>,
        selection: String,
        args: Array<String>?,
        sort: String?,
    ): Cursor? = appContext.contentResolver.query(collection, projection, selection, args, sort)

    /** Runs [block] on IO with the read permission; [fallback] without it or when the provider refuses. */
    private suspend fun <T> read(fallback: T, block: () -> T): T = withContext(dispatchers.io) {
        if (!canRead()) return@withContext fallback
        try {
            block()
        } catch (e: SecurityException) {
            fallback
        } catch (e: IllegalArgumentException) {
            fallback
        } catch (e: IllegalStateException) {
            fallback
        } catch (e: android.database.SQLException) {
            VeyroLog.w(TAG) { "Photo query failed: ${e.javaClass.simpleName}" }
            fallback
        }
    }

    private class BitmapThumbnail(private val bitmap: Bitmap) : PhotoThumbnail {
        override fun encodeJpeg(quality: Int): ByteArray {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return out.toByteArray()
        }

        override fun close() {
            bitmap.recycle()
        }
    }
}
