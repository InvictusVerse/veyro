package com.veyro.core.protocol.media

/**
 * The coded size of a mirrored screen (04-PROTOCOL.md 7.5.7). Integer arithmetic only, so the
 * phone (which encodes) and the PC (which plans the quality it asks for) get the same answer.
 * Vectors: `protocol/testvectors/media/stream-geometry.json`.
 *
 * The PC chooses the quality (`maxLong`, fps, bitrate, codec, hardware); this function only
 * turns its `maxLong` and the screen into pixels: the long side is the smallest of the screen,
 * `maxLong` and the encoder's limits, the short side keeps the screen's aspect, both are rounded
 * down to a multiple of 16 (what every H.264/H.265 encoder accepts).
 */
public object StreamGeometry {

    public enum class Fit {
        /** Android 10-12 stretch a mirrored display to the virtual display: all of the frame is picture. */
        STRETCH,

        /** Android 12L+ keep the aspect and centre it: the picture is the fitted rectangle. */
        FIT,
    }

    public data class Result(val width: Int, val height: Int, val visX: Int, val visY: Int, val visW: Int, val visH: Int)

    /**
     * [screenW] x [screenH] in the current rotation; [encMaxW] x [encMaxH] the encoder's largest
     * size, in either orientation. Returns null when nothing fits.
     */
    public fun compute(screenW: Int, screenH: Int, maxLong: Int, encMaxW: Int, encMaxH: Int, fit: Fit): Result? {
        if (screenW < 16 || screenH < 16 || maxLong < 16 || encMaxW < 16 || encMaxH < 16) return null
        val landscape = screenW >= screenH
        val srcLong = maxOf(screenW, screenH).toLong()
        val srcShort = minOf(screenW, screenH).toLong()
        val encLong = maxOf(encMaxW, encMaxH).toLong()
        val encShort = minOf(encMaxW, encMaxH).toLong()

        var long = minOf(srcLong, maxLong.toLong(), encLong)
        var short = srcShort * long / srcLong
        if (short > encShort) {
            short = encShort
            long = encShort * srcLong / srcShort
        }
        val codedLong = (long / 16 * 16).coerceAtLeast(16).toInt()
        val codedShort = (short / 16 * 16).coerceAtLeast(16).toInt()
        val w = if (landscape) codedLong else codedShort
        val h = if (landscape) codedShort else codedLong

        if (fit == Fit.STRETCH) return Result(w, h, 0, 0, w, h)
        // The screen's aspect fitted into w x h and centred.
        val sw = screenW.toLong()
        val sh = screenH.toLong()
        val visW: Int
        val visH: Int
        if (w * sh > h * sw) {
            visH = h
            visW = (h * sw / sh).toInt().coerceIn(1, w)
        } else {
            visW = w
            visH = (w * sh / sw).toInt().coerceIn(1, h)
        }
        return Result(w, h, (w - visW) / 2, (h - visH) / 2, visW, visH)
    }
}
