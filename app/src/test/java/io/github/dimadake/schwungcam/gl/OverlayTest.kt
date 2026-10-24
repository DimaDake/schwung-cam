package io.github.dimadake.schwungcam.gl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayTest {
    private val a = 16f / 9f

    @Test fun `drag clamps inside the frame`() {
        val o = Overlay(cx = 0.5f, cy = 0.5f, w = 0.4f).dragged(2f, -2f, a)
        assertEquals(0.8f, o.cx, 1e-6f)
        assertEquals(o.h(a) / 2, o.cy, 1e-6f)
    }

    @Test fun `pinch is bounded`() {
        assertEquals(Overlay.MIN_W, Overlay(w = 0.1f).scaled(0.01f, a).w, 1e-6f)
        assertEquals(1f, Overlay(w = 0.5f).scaled(10f, a).w, 1e-6f)
    }

    @Test fun `height keeps the screen 2 to 1 in pixels`() {
        val o = Overlay(w = 0.5f)
        // 0.5 of 1920 = 960 px wide → 480 px high → 480/1080 of the frame
        assertEquals(480f / 1080f, o.h(a), 1e-5f)
    }

    @Test fun `ndc quad`() {
        val o = Overlay(cx = 0.5f, cy = 0.5f, w = 0.5f)
        val hh = o.h(a)
        assertArrayEquals(floatArrayOf(-0.5f, hh, 0.5f, -hh), o.ndc(a), 1e-6f)
    }
}
