package io.github.dimadake.schwungcam.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A selectable lens: a camera id, plus a physical lens of it when it is a logical multi-camera. */
data class CameraChoice(val id: String, val physicalId: String?, val label: String, val facing: Int)

object CameraCatalog {
    fun list(cm: CameraManager): List<CameraChoice> {
        val out = ArrayList<CameraChoice>()
        val seen = HashSet<String>()
        fun add(id: String, phys: String?, ch: CameraCharacteristics) {
            val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_BACK
            val side = when (facing) { CameraMetadata.LENS_FACING_FRONT -> "Front"; CameraMetadata.LENS_FACING_EXTERNAL -> "External"; else -> "Back" }
            val mm = equivMm(ch)
            var label = if (mm != null) "$side ${mm}mm" else side
            if (!seen.add(label)) label = "$label (${phys ?: id})"
            out.add(CameraChoice(id, phys, label, facing))
        }
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            add(id, null, ch)
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) {
                for (p in ch.physicalCameraIds) {
                    val pc = runCatching { cm.getCameraCharacteristics(p) }.getOrNull() ?: continue
                    // a lens that is also exposed as its own id is already listed
                    if (p in cm.cameraIdList) continue
                    add(id, p, pc)
                }
            }
        }
        // back lenses first, widest first within a side
        return out.sortedWith(compareBy({ it.facing != CameraMetadata.LENS_FACING_BACK }, { it.label.filter(Char::isDigit).toIntOrNull() ?: 0 }))
    }

    /** 35 mm-equivalent focal length. */
    private fun equivMm(ch: CameraCharacteristics): Int? {
        val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val s = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diag = sqrt(s.width * s.width + s.height * s.height)
        if (diag <= 0f) return null
        return (f * 43.27f / diag).roundToInt()
    }

    /**
     * The SurfaceTexture size to ask for: the smallest 16:9 size covering the
     * output, else the largest size up to 4K closest to 16:9.
     */
    fun bufferSize(ch: CameraCharacteristics, outW: Int, outH: Int): Size {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java).toList()
        val target = outW.toFloat() / outH
        val sixteenNine = sizes.filter { abs(it.width.toFloat() / it.height - target) < 0.01f }
        sixteenNine.filter { it.width >= outW && it.height >= outH }.minByOrNull { it.width * it.height }?.let { return it }
        return sizes.filter { it.width <= 3840 }.sortedWith(
            compareBy<Size> { abs(it.width.toFloat() / it.height - target) }.thenByDescending { it.width * it.height }
        ).first()
    }
}
