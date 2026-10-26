package io.github.dimadake.schwungcam.gl

/**
 * Texture coordinates of the output quad's corners (BL, BR, TL, TR), fed
 * through the SurfaceTexture matrix afterwards.
 *
 * Camera2 already gives the stream a buffer transform for the sensor
 * orientation (and a horizontal flip on front lenses), and the SurfaceTexture
 * matrix includes it, so after the matrix the image is upright in the device's
 * NATURAL orientation. What is left is the display rotation, plus a centre crop
 * to the output aspect. Measured on a Pixel 9 (sensor 90°): the matrix maps
 * (s, t) to (1−t, 1−s), a quarter turn with the GL flip.
 */
object CameraUv {
    /**
     * [bufW]×[bufH] is the camera buffer, [sensorDeg] the lens's
     * SENSOR_ORIENTATION, [displayDeg] the display rotation (Surface.ROTATION_*
     * in degrees), [unmirror] undoes a front lens's mirror.
     */
    fun corners(bufW: Int, bufH: Int, sensorDeg: Int, displayDeg: Int, unmirror: Boolean, outAspect: Float): FloatArray {
        val natural = if (norm(sensorDeg) % 180 == 0) bufW.toFloat() / bufH else bufH.toFloat() / bufW
        val upright = if (norm(displayDeg) % 180 == 0) natural else 1f / natural
        var sx = 1f; var sy = 1f
        if (upright > outAspect) sx = outAspect / upright else sy = upright / outAspect
        val out = FloatArray(8)
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        for (i in 0 until 4) {
            var u = 0.5f + (corners[2 * i] - 0.5f) * sx
            var v = 0.5f + (corners[2 * i + 1] - 0.5f) * sy
            // mirror the upright picture, not the natural one: a reflection reverses a rotation, so
            // flipping after the turn below lands a front lens upside down in landscape
            if (unmirror) u = 1f - u
            // window → natural: each quarter of display rotation turns the image one step
            repeat(norm(displayDeg) / 90) { val nu = v; val nv = 1f - u; u = nu; v = nv }
            out[2 * i] = u; out[2 * i + 1] = v
        }
        return out
    }

    private fun norm(d: Int) = ((d % 360) + 360) % 360
}
