package io.github.dimadake.schwungcam.gl

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraUvTest {
    // the SurfaceTexture matrix a Pixel 9 back lens (sensor 90°) reported: (s, t) → (1−t, 1−s)
    private fun pixelMatrix(s: Float, t: Float) = floatArrayOf(1f - t, 1f - s)

    /** Corner i (BL, BR, TL, TR) in buffer pixels. */
    private fun bufferCorners(displayDeg: Int, bufW: Int = 1920, bufH: Int = 1080): List<Pair<Float, Float>> {
        val uv = CameraUv.corners(bufW, bufH, 90, displayDeg, false, 16f / 9f)
        return (0 until 4).map { i -> pixelMatrix(uv[2 * i], uv[2 * i + 1]).let { it[0] * bufW to it[1] * bufH } }
    }

    @Test fun `landscape takes the whole 16x9 buffer, not a rotated crop of it`() {
        for (d in listOf(90, 270)) {
            val c = bufferCorners(d)
            val w = c.maxOf { it.first } - c.minOf { it.first }
            val h = c.maxOf { it.second } - c.minOf { it.second }
            assertEquals("display $d width", 1920f, w, 0.5f)
            assertEquals("display $d height", 1080f, h, 0.5f)
        }
    }

    @Test fun `the two landscapes are a half turn apart`() {
        val a = bufferCorners(90); val b = bufferCorners(270)
        for (i in 0 until 4) {
            assertEquals(1920f - a[i].first, b[i].first, 0.5f)
            assertEquals(1080f - a[i].second, b[i].second, 0.5f)
        }
    }

    @Test fun `display 90 turns the natural image a quarter, top of the frame from the natural right edge`() {
        val uv = CameraUv.corners(1920, 1080, 90, 90, false, 16f / 9f)
        // TL and TR: the top edge of the output samples natural u = 1
        assertEquals(1f, uv[4], 1e-6f); assertEquals(1f, uv[6], 1e-6f)
    }

    @Test fun `natural orientation crops a portrait image to 16x9`() {
        val uv = CameraUv.corners(1920, 1080, 90, 0, false, 16f / 9f)
        // natural is 1080 wide × 1920 tall: full width, a 9/16 × 9/16 slice of the height
        assertEquals(1f, uv[2] - uv[0], 1e-6f)
        assertEquals((9f / 16f) * (9f / 16f), uv[5] - uv[1], 1e-5f)
    }

    @Test fun `unmirror flips the upright picture left to right in every rotation`() {
        for (d in listOf(0, 90, 180, 270)) {
            val a = CameraUv.corners(1920, 1080, 270, d, false, 16f / 9f)
            val b = CameraUv.corners(1920, 1080, 270, d, true, 16f / 9f)
            // BL↔BR, TL↔TR: the same picture, columns reversed, so up stays up
            for ((i, j) in listOf(0 to 1, 1 to 0, 2 to 3, 3 to 2)) {
                assertEquals("display $d corner $i u", a[2 * j], b[2 * i], 1e-6f)
                assertEquals("display $d corner $i v", a[2 * j + 1], b[2 * i + 1], 1e-6f)
            }
        }
    }
}
