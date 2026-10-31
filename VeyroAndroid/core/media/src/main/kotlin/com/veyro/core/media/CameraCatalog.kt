package com.veyro.core.media

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodec
import com.veyro.core.common.VeyroLog

/** A camera size in the sensor's landscape orientation (w >= h) and the highest frame rate for it. */
public data class CameraSize(val w: Int, val h: Int, val fps: Int)

/** One phone camera (ADR-067). [facing]: `back`, `front` or `external`. */
public data class CameraInfo(
    val id: String,
    val facing: String,
    val sensorOrientation: Int,
    val sizes: List<CameraSize>,
    val flash: Boolean = false,
)

/**
 * The phone's cameras and the sizes a PC may ask for (ADR-067): sizes the camera can deliver to
 * an encoder (`getOutputSizes(MediaCodec)`) that at least one of this phone's encoders accepts,
 * at least 640x360, with the frame rate the camera reaches at that size (at most 60). Read once.
 */
public class CameraCatalog(private val context: Context, private val encoders: EncoderCatalog) {

    public companion object {
        private const val TAG = "CameraCatalog"
        private const val MAX_SIZES = 12
        private const val MAX_FPS = 60
    }

    public val all: List<CameraInfo> by lazy { load() }

    public fun find(id: String): CameraInfo? = all.firstOrNull { it.id == id }

    private fun load(): List<CameraInfo> {
        val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val found = mutableListOf<CameraInfo>()
        for (id in ids) {
            val info = runCatching { describe(manager, id) }.getOrNull() ?: continue
            if (info.sizes.isNotEmpty()) found += info
        }
        VeyroLog.i(TAG) { "Cameras: " + found.joinToString { "${it.facing} ${it.sizes.firstOrNull()?.let { s -> "${s.w}x${s.h}" }}" } }
        return found
    }

    private fun describe(manager: CameraManager, id: String): CameraInfo? {
        val c = manager.getCameraCharacteristics(id)
        val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in capabilities) return null
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "back"
            CameraMetadata.LENS_FACING_FRONT -> "front"
            else -> "external"
        }
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val aeMax = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.maxOfOrNull { it.upper } ?: 30
        val usable = encoders.all
        val sizes = (map.getOutputSizes(MediaCodec::class.java) ?: emptyArray())
            .map { size -> if (size.width >= size.height) size.width to size.height else size.height to size.width }
            .distinct()
            .filter { (w, h) -> w >= 640 && h >= 360 && w <= 8192 && h <= 8192 }
            .filter { (w, h) -> usable.any { e -> (e.maxW >= w && e.maxH >= h) || (e.maxW >= h && e.maxH >= w) } }
            .map { (w, h) ->
                val sensorSize = map.getOutputSizes(MediaCodec::class.java).firstOrNull { (it.width == w && it.height == h) || (it.width == h && it.height == w) }
                val minDuration = sensorSize?.let { runCatching { map.getOutputMinFrameDuration(MediaCodec::class.java, it) }.getOrDefault(0L) } ?: 0L
                val byDuration = if (minDuration > 0) (1_000_000_000L / minDuration).toInt() else 30
                CameraSize(w, h, minOf(byDuration, aeMax, MAX_FPS).coerceAtLeast(1))
            }
            .sortedByDescending { it.w.toLong() * it.h }
            .take(MAX_SIZES)
        val orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        return CameraInfo(id, facing, orientation, sizes, flash)
    }
}
