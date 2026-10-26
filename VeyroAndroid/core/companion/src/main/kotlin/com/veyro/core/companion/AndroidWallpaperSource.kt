package com.veyro.core.companion

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * [WallpaperSource] from `WallpaperManager` (ADR-038).
 *
 * Reading another app's wallpaper image is guarded: depending on the Android version it needs the
 * photo permission (`READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`) or more, and throws
 * `SecurityException` otherwise. That, a live wallpaper, or any other failure gives null, so the
 * PC is told "no wallpaper" and draws its own fallback. Changes come from
 * `ACTION_WALLPAPER_CHANGED` where the platform still delivers it, and from the wallpaper-colour
 * listener, which fires on every change on API 27+.
 */
public class AndroidWallpaperSource(
    context: Context,
    private val dispatchers: AppDispatchers,
) : WallpaperSource {

    private companion object {
        const val TAG = "WallpaperSource"
        /** Wallpapers are often 4K; anything bigger is refused rather than allocated. */
        const val MAX_SOURCE_PIXELS = 8192L * 8192L
    }

    private val appContext: Context = context.applicationContext

    override val changes: Flow<Unit> = callbackFlow {
        val manager = WallpaperManager.getInstance(appContext)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        @Suppress("DEPRECATION")
        val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        }
        val colors = WallpaperManager.OnColorsChangedListener { _, _ -> trySend(Unit) }
        runCatching { manager.addOnColorsChangedListener(colors, Handler(Looper.getMainLooper())) }
        awaitClose {
            runCatching { appContext.unregisterReceiver(receiver) }
            runCatching { manager.removeOnColorsChangedListener(colors) }
        }
    }

    override suspend fun currentJpeg(): ByteArray? = withContext(dispatchers.io) {
        val drawable: Drawable? = try {
            WallpaperManager.getInstance(appContext).drawable
        } catch (e: SecurityException) {
            VeyroLog.i(TAG) { "Wallpaper not readable without permission" }
            null
        } catch (e: RuntimeException) {
            VeyroLog.w(TAG) { "Wallpaper read failed: ${e.javaClass.simpleName}" }
            null
        }
        // Android 14+ refuses the image to ordinary apps (and 13 may return the stock wallpaper);
        // only "All files access" unlocks it. Without the image, send a picture made from the
        // wallpaper's colours, which needs no permission, so the PC still matches the phone.
        val source: Bitmap? = try {
            if (drawable != null) toBitmap(drawable) else colourWallpaper()
        } catch (e: OutOfMemoryError) {
            VeyroLog.w(TAG) { "Wallpaper too large to read" }
            null
        }
        if (source == null) return@withContext null

        try {
            WallpaperEncoding.fit { maxSide, quality -> encode(source, maxSide, quality) }
        } catch (e: RuntimeException) {
            VeyroLog.w(TAG) { "Wallpaper encode failed: ${e.javaClass.simpleName}" }
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun toBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: appContext.resources.displayMetrics.widthPixels
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: appContext.resources.displayMetrics.heightPixels
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_SOURCE_PIXELS) return null
        // Drawn straight at the wire size, so a vector or colour wallpaper never costs a full frame.
        val (tw, th) = WallpaperEncoding.scaledSize(w, h)
        val bitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, tw, th)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * A phone-shaped gradient from `WallpaperColors` (API 27+, no permission). Null if the
     * platform reports no colours.
     */
    private fun colourWallpaper(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
        val colors = runCatching {
            WallpaperManager.getInstance(appContext).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull() ?: return null
        val primary = colors.primaryColor.toArgb()
        val secondary = colors.secondaryColor?.toArgb() ?: primary
        val tertiary = colors.tertiaryColor?.toArgb() ?: secondary
        val metrics = appContext.resources.displayMetrics
        val (w, h) = WallpaperEncoding.scaledSize(
            metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1),
        )
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(primary, secondary, tertiary),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        Canvas(bitmap).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        VeyroLog.i(TAG) { "Sending a colour version of the wallpaper (image not readable)" }
        return bitmap
    }

    private fun encode(source: Bitmap, maxSide: Int, quality: Int): ByteArray? {
        if (source.width <= 0 || source.height <= 0) return null
        val (w, h) = WallpaperEncoding.scaledSize(source.width, source.height, maxSide)
        val scaled = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
        return try {
            ByteArrayOutputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                out.toByteArray()
            }
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}
