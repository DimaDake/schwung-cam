package io.github.dimadake.schwungcam.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * The Audio tab's waveforms: an overview of the whole take on top (tap to
 * seek), then a lane per source showing [WINDOW] seconds around the playhead,
 * which stays in the middle; drag them to scrub. Stem lanes carry S and M.
 */
class LanesView(ctx: Context) : View(ctx) {
    class Lane(val name: String, val color: Int, val stem: Int = -1) {
        var peaks: Peaks? = null
    }

    var lanes: List<Lane> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var durationSec = 1.0
    var t = 0.0
        set(v) { field = v; invalidate() }
    var solo = BooleanArray(0)
    var mute = BooleanArray(0)
    /** Solo/mute only mean something while the stems are what plays. */
    var stemsActive = false
        set(v) { field = v; invalidate() }
    var onSeek: (Double) -> Unit = {}
    var onSoloMute: () -> Unit = {}

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val laneH = ctx.dp(46).toFloat()
    private val overH = ctx.dp(36).toFloat()
    private val nameW = ctx.dp(78).toFloat()
    private val btn = ctx.dp(26).toFloat()
    private val side = ctx.dp(12).toFloat()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (overH + side + lanes.size * laneH + side).toInt())
    }

    private fun waveLeft() = side + nameW
    private fun waveRight() = width - side - if (lanes.any { it.stem >= 0 }) 2 * btn + side / 2 else 0f

    override fun onDraw(c: Canvas) {
        val left = waveLeft(); val right = waveRight()
        // overview: the first lane over the whole take
        val over = lanes.firstOrNull()?.peaks
        r.set(side, 0f, width - side, overH)
        p.style = Paint.Style.FILL; p.color = C.SURFACE; c.drawRoundRect(r, 8f, 8f, p)
        if (over != null) drawPeaks(c, over, 0.0, durationSec, side, width - side, 0f, overH, C.MUTED)
        val px = side + ((t / durationSec).coerceIn(0.0, 1.0) * (width - 2 * side)).toFloat()
        p.color = C.ACCENT; p.strokeWidth = 2f; c.drawLine(px, 0f, px, overH, p)

        val from = t - WINDOW / 2; val to = t + WINDOW / 2
        p.textSize = context.dp(12).toFloat(); p.textAlign = Paint.Align.LEFT
        lanes.forEachIndexed { i, lane ->
            val top = overH + side + i * laneH
            val audible = lane.stem < 0 || !stemsActive || StemMix.gains(solo, mute)[lane.stem] > 0f
            p.style = Paint.Style.FILL
            p.color = if (lane.stem < 0) C.TEXT else C.TEXT2
            val name = TextFit.ellipsize(lane.name, p, nameW - side / 2)
            c.drawText(name, side, top + laneH / 2 + p.textSize * 0.35f, p)
            val pk = lane.peaks
            if (pk == null) {
                p.color = C.MUTED; c.drawText("Reading…", left + side, top + laneH / 2 + p.textSize * 0.35f, p)
            } else {
                drawPeaks(c, pk, from, to, left, right, top + 4, top + laneH - 4, if (audible) lane.color else 0xFF4A4B4F.toInt())
            }
            if (lane.stem >= 0) {
                val bx = width - side - 2 * btn
                toggle(c, bx, top + (laneH - btn) / 2, "S", solo.getOrElse(lane.stem) { false }, 0xFFE3C77A.toInt())
                toggle(c, bx + btn + 4, top + (laneH - btn) / 2, "M", mute.getOrElse(lane.stem) { false }, C.ERROR)
            }
        }
        // the playhead
        if (lanes.isNotEmpty()) {
            val mid = (left + right) / 2
            p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = 2f
            c.drawLine(mid, overH + side, mid, overH + side + lanes.size * laneH, p)
        }
    }

    private fun toggle(c: Canvas, x: Float, y: Float, label: String, on: Boolean, color: Int) {
        val en = stemsActive
        p.style = Paint.Style.FILL; p.color = if (on && en) color else C.CHIP
        r.set(x, y, x + btn - 4, y + btn); c.drawRoundRect(r, 6f, 6f, p)
        p.color = if (on && en) 0xFF000000.toInt() else if (en) C.TEXT2 else 0xFF5F6368.toInt()
        p.textAlign = Paint.Align.CENTER
        c.drawText(label, x + (btn - 4) / 2, y + btn / 2 + p.textSize * 0.35f, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawPeaks(c: Canvas, pk: Peaks, from: Double, to: Double, x0: Float, x1: Float, y0: Float, y1: Float, color: Int) {
        p.color = color; p.strokeWidth = 1.5f
        val mid = (y0 + y1) / 2; val half = (y1 - y0) / 2
        val cols = (x1 - x0).toInt()
        if (cols <= 0) return
        val per = (to - from) / cols
        for (x in 0 until cols) {
            val a = ((from + x * per) * pk.perSec).toInt(); val b = maxOf(a + 1, ((from + (x + 1) * per) * pk.perSec).toInt())
            if (b <= 0 || a >= pk.size) continue
            var lo = 0; var hi = 0
            for (k in maxOf(0, a) until minOf(pk.size, b)) { if (pk.min[k] < lo) lo = pk.min[k].toInt(); if (pk.max[k] > hi) hi = pk.max[k].toInt() }
            val fx = x0 + x
            c.drawLine(fx, mid - hi / 127f * half, fx, mid - lo / 127f * half + 1, p)
        }
    }

    private var downX = 0f
    private var downT = 0.0
    private var dragging = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downT = t; dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.y > overH && (dragging || abs(e.x - downX) > context.dp(8))) {
                    dragging = true
                    val secPerPx = WINDOW / (waveRight() - waveLeft())
                    onSeek((downT - (e.x - downX) * secPerPx).coerceIn(0.0, durationSec))
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) tap(e.x, e.y)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        if (y <= overH) { onSeek(((x - side) / (width - 2 * side) * durationSec).coerceIn(0.0, durationSec)); return }
        val i = ((y - overH - side) / laneH).toInt()
        val lane = lanes.getOrNull(i) ?: return
        val bx = width - side - 2 * btn
        if (lane.stem >= 0 && stemsActive && x >= bx) {
            if (x < bx + btn) solo[lane.stem] = !solo[lane.stem] else mute[lane.stem] = !mute[lane.stem]
            invalidate(); onSoloMute()
        }
    }

    companion object { const val WINDOW = 20.0 }
}

object TextFit {
    fun ellipsize(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var n = s.length
        while (n > 1 && p.measureText(s, 0, n) + p.measureText("…") > w) n--
        return s.substring(0, n) + "…"
    }
}
