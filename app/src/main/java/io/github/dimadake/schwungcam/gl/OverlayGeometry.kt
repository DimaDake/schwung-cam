package io.github.dimadake.schwungcam.gl

enum class KeyMode { NONE, BLACK_TRANSPARENT, WHITE_TRANSPARENT }

/**
 * Where the Move screen sits, in normalised frame coordinates (0..1, y down),
 * so the preview and the encoded frame are the same picture at any size.
 * [w] is a fraction of the frame width; the height follows the screen's 2:1.
 */
data class Overlay(
    val enabled: Boolean = false,
    val cx: Float = 0.82f,
    val cy: Float = 0.82f,
    val w: Float = 0.3f,
    val key: KeyMode = KeyMode.BLACK_TRANSPARENT,
    val opacity: Float = 1f,
) {
    fun h(aspect: Float) = w * aspect / 2f

    fun clamped(aspect: Float): Overlay {
        val ww = w.coerceIn(MIN_W, minOf(1f, 2f / aspect))
        val hh = ww * aspect / 2f
        return copy(
            w = ww,
            cx = cx.coerceIn(ww / 2, 1 - ww / 2),
            cy = cy.coerceIn(hh / 2, 1 - hh / 2),
            opacity = opacity.coerceIn(0f, 1f),
        )
    }

    fun dragged(dx: Float, dy: Float, aspect: Float) = copy(cx = cx + dx, cy = cy + dy).clamped(aspect)

    fun scaled(factor: Float, aspect: Float) = copy(w = w * factor).clamped(aspect)

    /** Quad corners in GL NDC: left, top, right, bottom (y up). */
    fun ndc(aspect: Float): FloatArray {
        val hh = h(aspect)
        return floatArrayOf((cx - w / 2) * 2 - 1, 1 - (cy - hh / 2) * 2, (cx + w / 2) * 2 - 1, 1 - (cy + hh / 2) * 2)
    }

    companion object { const val MIN_W = 0.05f }
}
