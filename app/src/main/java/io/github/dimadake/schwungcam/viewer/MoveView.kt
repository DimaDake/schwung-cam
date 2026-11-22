package io.github.dimadake.schwungcam.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * A small drawn Move at a moment of the take: its screen as saved, the knobs
 * (lit ring while turned, a wedge for the direction), 32 pads lit by velocity
 * and fading after release, 16 steps flashing on press, and the transport
 * buttons with their lights.
 */
class MoveView(ctx: Context) : View(ctx) {
    var gestures: Gestures? = null
    var screen: Bitmap? = null
    var t = 0.0
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pix = Paint().apply { isFilterBitmap = false }
    private val r = RectF()
    private val src = Rect()
    private val dst = RectF()
    private val pad = ctx.dp(10).toFloat()
    private val gap = ctx.dp(4).toFloat()

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, heightFor(width.toFloat()).toInt())
    }

    private fun cell(width: Float) = (width - 2 * pad - 7 * gap) / 8
    private fun screenH(width: Float) = (width - 2 * pad) * 0.42f / 2
    private fun heightFor(width: Float): Float {
        val c = cell(width)
        return pad + screenH(width) + 2 * gap + 4 * c + 3 * gap + 2 * gap + c * 0.32f + 2 * gap + c * 0.55f + pad
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val g = gestures
        p.style = Paint.Style.FILL; p.color = C.SURFACE
        r.set(0f, 0f, w, height.toFloat()); cv.drawRoundRect(r, context.dp(12).toFloat(), context.dp(12).toFloat(), p)
        val c = cell(w)

        // screen
        val sw = (w - 2 * pad) * 0.42f
        val sh = screenH(w)
        dst.set(pad, pad, pad + sw, pad + sh)
        p.color = Color.BLACK; cv.drawRect(dst, p)
        screen?.let { b -> src.set(0, 0, b.width, b.height); cv.drawBitmap(b, src, dst, pix) }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0xFF333333.toInt(); cv.drawRect(dst, p)

        // knobs: 2 rows of 4 beside the screen, volume and jog after them
        val kx0 = pad + sw + gap * 3
        val kArea = w - pad - kx0
        val kd = minOf(kArea / 5.2f, sh / 2.3f)
        val kStep = (kArea - kd) / 4
        for (i in 0 until 8) {
            val cx = kx0 + kd / 2 + (i % 4) * kStep
            val cy = pad + kd / 2 + (i / 4) * (sh - kd)
            knob(cv, cx, cy, kd / 2, "knob${i + 1}", g)
        }
        knob(cv, kx0 + kd / 2 + 4 * kStep, pad + kd / 2, kd / 2, "volume", g)
        knob(cv, kx0 + kd / 2 + 4 * kStep, pad + sh - kd / 2, kd / 2, "jog", g)

        // pads, row 0 on top
        val py = pad + sh + 2 * gap
        p.style = Paint.Style.FILL
        for (row in 0 until 4) for (col in 0 until 8) {
            val x = pad + col * (c + gap); val y = py + row * (c + gap)
            val lv = g?.pad(row * 8 + col, t) ?: 0f
            if (lv > 0f) {
                p.color = withAlpha(PAD, lv * 0.35f)
                r.set(x - gap * 0.8f, y - gap * 0.8f, x + c + gap * 0.8f, y + c + gap * 0.8f); cv.drawRoundRect(r, c * 0.2f, c * 0.2f, p)
            }
            p.color = mix(C.CHIP, PAD, lv)
            r.set(x, y, x + c, y + c); cv.drawRoundRect(r, c * 0.12f, c * 0.12f, p)
        }

        // steps
        val sy = py + 4 * c + 3 * gap + 2 * gap
        val stepW = (w - 2 * pad - 15 * gap / 2) / 16
        for (i in 0 until 16) {
            val x = pad + i * (stepW + gap / 2)
            p.color = mix(C.CHIP, STEP, g?.step(i, t) ?: 0f)
            r.set(x, sy, x + stepW, sy + c * 0.32f); cv.drawRoundRect(r, 3f, 3f, p)
        }

        // transport, lights from the take
        val by = sy + c * 0.32f + 2 * gap
        val bh = c * 0.55f
        val bw = (w - 2 * pad - 4 * gap) / 5
        p.textSize = bh * 0.46f; p.textAlign = Paint.Align.CENTER
        Gestures.LEDS.forEachIndexed { i, name ->
            val x = pad + i * (bw + gap)
            val pressed = g?.button(name, t) ?: 0f
            val lit = g?.led(name, t) == true
            p.style = Paint.Style.FILL; p.color = mix(C.CHIP, 0xFF4A4B4F.toInt(), pressed)
            r.set(x, by, x + bw, by + bh); cv.drawRoundRect(r, bh / 2, bh / 2, p)
            p.color = if (lit) ledColor(name) else 0xFF4A4B4F.toInt()
            cv.drawCircle(x + bh / 2, by + bh / 2, bh * 0.16f, p)
            p.color = if (lit || pressed > 0) C.TEXT else C.MUTED
            cv.drawText(name, x + bw / 2 + bh * 0.2f, by + bh / 2 + p.textSize * 0.36f, p)
        }
    }

    private fun knob(cv: Canvas, cx: Float, cy: Float, rad: Float, control: String, g: Gestures?) {
        val dir = g?.turning(control, t) ?: 0
        val touched = g?.touched(control, t) == true
        p.style = Paint.Style.FILL; p.color = if (touched) 0xFF3A3F4A.toInt() else C.CHIP
        cv.drawCircle(cx, cy, rad * 0.78f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = rad * 0.18f; p.strokeCap = Paint.Cap.ROUND
        r.set(cx - rad * 0.9f, cy - rad * 0.9f, cx + rad * 0.9f, cy + rad * 0.9f)
        p.color = if (dir != 0 || touched) C.ACCENT else 0xFF4A4B4F.toInt()
        cv.drawArc(r, 135f, 270f, false, p)
        if (dir != 0) {
            // which way: a bright wedge running from the top towards the turn
            p.color = Color.WHITE
            cv.drawArc(r, 270f, if (dir > 0) 70f else -70f, false, p)
        }
        p.strokeCap = Paint.Cap.BUTT
    }

    companion object {
        private val PAD = 0xFFFFB4A9.toInt()
        private val STEP = 0xFFE3C77A.toInt()

        fun ledColor(name: String) = when (name) {
            "Play" -> 0xFF81C995.toInt()
            "Record" -> 0xFFF28B82.toInt()
            else -> 0xFFE3C77A.toInt()
        }

        fun withAlpha(c: Int, a: Float) = (c and 0xFFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

        fun mix(a: Int, b: Int, k: Float): Int {
            val f = k.coerceIn(0f, 1f)
            fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - f) + ((b shr s) and 0xFF) * f).toInt() shl s
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }
    }
}

/** Just the Move screen, for the fullscreen overlay. */
class ScreenView(ctx: Context) : View(ctx) {
    var screen: Bitmap? = null
        set(v) { if (field !== v) { field = v; invalidate() } }
    private val pix = Paint().apply { isFilterBitmap = false }
    private val src = Rect()
    private val dst = Rect()

    override fun onDraw(cv: Canvas) {
        cv.drawColor(0xCC000000.toInt())
        val b = screen ?: return
        src.set(0, 0, b.width, b.height); dst.set(0, 0, width, height)
        cv.drawBitmap(b, src, dst, pix)
    }
}
