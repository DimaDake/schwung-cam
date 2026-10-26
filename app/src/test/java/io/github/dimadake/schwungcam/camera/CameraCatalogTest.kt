package io.github.dimadake.schwungcam.camera

import android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
import android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraCatalogTest {
    // what the Pixel 9 listed: logical back 0 (main) with an ultrawide and a tele lens, two fronts
    private val pixel = listOf(
        CameraChoice("0", "3", "Back 12mm", LENS_FACING_BACK, 12),
        CameraChoice("0", null, "Back 24mm", LENS_FACING_BACK, 24),
        CameraChoice("0", "4", "Back 49mm", LENS_FACING_BACK, 49),
        CameraChoice("1", null, "Front 21mm", LENS_FACING_FRONT, 21),
        CameraChoice("5", null, "Front 23mm", LENS_FACING_FRONT, 23),
    )

    @Test fun `the 1x lens is the logical camera, not the widest`() {
        assertEquals("Back 24mm", CameraCatalog.main(pixel, LENS_FACING_BACK)?.label)
        assertEquals("Front 23mm", CameraCatalog.main(pixel, LENS_FACING_FRONT)?.label)
    }

    @Test fun `zoom labels read like Pixel Camera's`() {
        val main = CameraCatalog.main(pixel, LENS_FACING_BACK)
        assertEquals(listOf("0.5", "1", "2"), pixel.take(3).map { CameraCatalog.zoomLabel(it, main) })
    }

    @Test fun `a lens next to the main one is not a zoom stop`() {
        assertEquals(listOf("Front 23mm"), CameraCatalog.zoomStops(pixel, LENS_FACING_FRONT).map { it.label })
        assertEquals(listOf("Back 12mm", "Back 24mm", "Back 49mm"), CameraCatalog.zoomStops(pixel, LENS_FACING_BACK).map { it.label })
    }
}
