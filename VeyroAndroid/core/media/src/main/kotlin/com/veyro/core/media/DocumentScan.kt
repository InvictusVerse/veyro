package com.veyro.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Photos and document scans for the PC (ADR-073). Loads a camera JPEG upright (EXIF), flattens a
 * page from four corners with a perspective transform, and optionally turns it into crisp
 * black-and-white text. Images are user data: nothing here logs them.
 */
public object DocumentScan {

    public const val MAX_LONG_SIDE: Int = 4096

    /** The image in [file], turned upright by its EXIF orientation, its long side at most [maxLong]. */
    public fun loadUpright(file: File, maxLong: Int = MAX_LONG_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxLong) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return decoded
        val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
        if (turned !== decoded) decoded.recycle()
        return turned
    }

    /** Corners slightly inside the image, as a start for the handles: top-left, top-right, bottom-right, bottom-left. */
    public fun defaultCorners(width: Int, height: Int): List<PointF> {
        val dx = width * 0.08f
        val dy = height * 0.08f
        return listOf(PointF(dx, dy), PointF(width - dx, dy), PointF(width - dx, height - dy), PointF(dx, height - dy))
    }

    /** The page between [corners] (TL, TR, BR, BL in image pixels) as a flat rectangle. */
    public fun flatten(source: Bitmap, corners: List<PointF>): Bitmap {
        require(corners.size == 4) { "four corners" }
        val (tl, tr, br, bl) = corners
        val width = max(hypot(tr.x - tl.x, tr.y - tl.y), hypot(br.x - bl.x, br.y - bl.y)).roundToInt().coerceIn(16, MAX_LONG_SIDE)
        val height = max(hypot(bl.x - tl.x, bl.y - tl.y), hypot(br.x - tr.x, br.y - tr.y)).roundToInt().coerceIn(16, MAX_LONG_SIDE)
        val src = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)
        val dst = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return source.copy(Bitmap.Config.ARGB_8888, false)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    /**
     * Readable text on white: grey, then each pixel compared with the average brightness around
     * it (a local threshold, so shadows across the page do not turn black), softened at the edge.
     */
    public fun enhance(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val grey = IntArray(w * h) { i ->
            val c = pixels[i]
            (77 * Color.red(c) + 150 * Color.green(c) + 29 * Color.blue(c)) shr 8
        }
        // Summed-area table for the local mean.
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += grey[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val r = max(8, minOf(w, h) / 30)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = minOf(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = minOf(w - 1, x + r)
                val area = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
                val sum = integral[(y1 + 1) * (w + 1) + x1 + 1] - integral[y0 * (w + 1) + x1 + 1] -
                    integral[(y1 + 1) * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val mean = (sum / area).toInt()
                val v = grey[y * w + x]
                val diff = v - (mean - 12)
                val out = when {
                    diff >= 8 -> 255
                    diff <= -8 -> 0
                    else -> (diff + 8) * 255 / 16
                }
                pixels[y * w + x] = Color.rgb(out, out, out)
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Writes [bitmap] as JPEG to [file]. */
    public fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 90) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }
}
