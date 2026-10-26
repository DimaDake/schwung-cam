package io.github.dimadake.schwungcam.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A Pixel-style video shutter: a white ring around a red disc that morphs
 * into a stop square while recording. While a take is being mastered the ring
 * becomes a progress arc and the button is disabled.
 */
class ShutterButton @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val dp = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * dp; color = 0xFFFFFFFF.toInt() }
    private val track = Paint(ring).apply { color = 0x55FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEA4335.toInt() }
    private val box = RectF()
    private var morph = 0f
    private var anim: ValueAnimator? = null

    var recording = false
        set(v) {
            if (field == v) return
            field = v
            anim?.cancel()
            anim = ValueAnimator.ofFloat(morph, if (v) 1f else 0f).apply {
                duration = 220; interpolator = DecelerateInterpolator()
                addUpdateListener { morph = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    /** 0..1 while mastering; null otherwise. */
    var progress: Float? = null
        set(v) { field = v; isEnabled = v == null; invalidate() }

    init { isClickable = true; isFocusable = true }

    override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - ring.strokeWidth
        box.set(cx - r, cy - r, cx + r, cy + r)
        val p = progress
        if (p != null) {
            c.drawOval(box, track)
            c.drawArc(box, -90f, 360f * p.coerceIn(0f, 1f), false, ring)
        } else c.drawCircle(cx, cy, r, ring)

        fill.alpha = if (p != null) 90 else 255
        val press = if (isPressed) 0.9f else 1f
        val half = lerp(r * 0.80f, r * 0.42f, morph) * press
        val corner = lerp(half, half * 0.3f, morph)
        c.drawRoundRect(cx - half, cy - half, cx + half, cy + half, corner, corner, fill)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
