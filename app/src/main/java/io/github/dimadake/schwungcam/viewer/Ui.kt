package io.github.dimadake.schwungcam.viewer

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.dimadake.schwungcam.R

/** The camera's dark palette, in Material 3 roles. */
object C {
    const val BG = Color.BLACK
    const val SURFACE = 0xFF1E1F20.toInt()
    const val SURFACE2 = 0xFF2B2C2F.toInt()
    const val CHIP = 0xFF2C2D30.toInt()
    const val TEXT = 0xFFE3E3E3.toInt()
    const val TEXT2 = 0xFFC4C7C5.toInt()
    const val MUTED = 0xFF9AA0A6.toInt()
    const val LINE = 0xFF2A2A2A.toInt()
    const val ACCENT = 0xFFA8C7FA.toInt()
    const val ON_ACCENT = 0xFF062E6F.toInt()
    const val ERROR = 0xFFF2B8B5.toInt()
    const val ON_ERROR = 0xFF601410.toInt()

    fun of(k: Kind) = when (k) {
        Kind.MASTERED -> TEXT
        Kind.RAW_VIDEO -> 0xFFE3C77A.toInt()
        Kind.RAW_AUDIO -> 0xFFB8E6C4.toInt()
        Kind.STEMS -> 0xFFFFB4A9.toInt()
        Kind.AI -> ACCENT
    }

    fun icon(k: Kind) = when (k) {
        Kind.MASTERED -> R.drawable.ic_movie
        Kind.RAW_VIDEO -> R.drawable.ic_videocam
        Kind.RAW_AUDIO -> R.drawable.ic_graphic_eq
        Kind.STEMS -> R.drawable.ic_instant_mix
        Kind.AI -> R.drawable.ic_auto_awesome
    }
}

fun Context.dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

fun Context.text(s: CharSequence = "", size: Float = 14f, color: Int = C.TEXT) = TextView(this).apply {
    text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
}

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.ripple(borderless: Boolean = false): Drawable? {
    val a = obtainStyledAttributes(intArrayOf(if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground))
    return a.getDrawable(0).also { a.recycle() }
}

fun Context.iconButton(res: Int, desc: String, tint: Int = C.TEXT2, onClick: (View) -> Unit) = ImageView(this).apply {
    setImageResource(res); contentDescription = desc
    imageTintList = ColorStateList.valueOf(tint)
    scaleType = ImageView.ScaleType.CENTER
    background = ripple(true)
    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    setOnClickListener(onClick)
}

fun Context.icon(res: Int, tint: Int, sizeDp: Int) = ImageView(this).apply {
    setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

/**
 * A Material 3 checkbox: an outlined box, filled with a check when ticked and
 * with a dash when partly ticked. [round] draws the photo-picker circle instead.
 */
class CheckMark(ctx: Context, private val round: Boolean = false) : View(ctx) {
    var state = NONE
        set(v) { field = v; invalidate() }
    var checked: Boolean
        get() = state == ON
        set(v) { state = if (v) ON else NONE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val check = ctx.getDrawable(R.drawable.ic_check)!!.mutate()
    private val dash = ctx.getDrawable(R.drawable.ic_remove)!!.mutate()
    private val box = RectF()

    init {
        val s = ctx.dp(if (round) 24 else 20)
        layoutParams = LinearLayout.LayoutParams(s, s)
        check.setTint(C.ON_ACCENT); dash.setTint(C.ON_ACCENT)
    }

    override fun onDraw(c: Canvas) {
        val sw = context.dp(2).toFloat()
        box.set(sw / 2, sw / 2, width - sw / 2, height - sw / 2)
        val r = if (round) width / 2f else context.dp(3).toFloat()
        if (state == NONE) {
            paint.style = Paint.Style.FILL; paint.color = if (round) 0x44000000 else 0
            c.drawRoundRect(box, r, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.color = if (round) Color.WHITE else C.MUTED
            c.drawRoundRect(box, r, r, paint)
        } else {
            paint.style = Paint.Style.FILL; paint.color = C.ACCENT
            c.drawRoundRect(box, r, r, paint)
            val d = if (state == ON) check else dash
            val inset = width / 8
            d.setBounds(inset, inset, width - inset, height - inset); d.draw(c)
        }
    }

    companion object { const val NONE = 0; const val ON = 1; const val SOME = 2 }
}

/** A bottom sheet: a Dialog pinned to the bottom with M3's rounded top. */
class Sheet(ctx: Context, title: String) : Dialog(ctx) {
    val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(8), 0, ctx.dp(12))
            background = GradientDrawable().apply {
                setColor(C.SURFACE); val r = ctx.dp(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            addView(View(ctx).apply { background = rounded(0xFF555555.toInt(), ctx.dp(2).toFloat()) },
                LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ctx.dp(14) })
            addView(ctx.text(title, 20f).apply { setPadding(ctx.dp(24), 0, ctx.dp(24), ctx.dp(8)) })
            addView(ScrollView(ctx).apply { addView(body) }, lp())
        }
        setContentView(col)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setGravity(Gravity.BOTTOM)
            setLayout(MATCH_PARENT, WRAP_CONTENT)
        }
    }

    /** A row with a checkbox; the row toggles it. */
    fun checkRow(title: String, sub: String?, iconRes: Int?, iconTint: Int, initial: Boolean, onChange: (Boolean) -> Unit): Pair<View, CheckMark> {
        val ctx = context
        val mark = CheckMark(ctx).apply { checked = initial }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(24), ctx.dp(10), ctx.dp(24), ctx.dp(10)); background = ctx.ripple()
            addView(mark)
            if (iconRes != null) addView(ctx.icon(iconRes, iconTint, 22).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = ctx.dp(16) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(ctx.text(title, 16f))
                if (sub != null) addView(ctx.text(sub, 13f, C.MUTED))
            }, lp(0, WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(16) })
            setOnClickListener { mark.checked = !mark.checked; onChange(mark.checked) }
        }
        body.addView(row)
        return row to mark
    }

    /** Cancel + a filled button; returns the filled one. */
    fun buttons(action: String, fill: Int = C.ACCENT, onFill: Int = C.ON_ACCENT, onAction: () -> Unit): TextView {
        val ctx = context
        fun btn(t: String) = ctx.text(t, 14f).apply {
            gravity = Gravity.CENTER; setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), ctx.dp(10))
        }
        val go = btn(action).apply {
            background = rounded(fill, ctx.dp(20).toFloat()); setTextColor(onFill); paint.isFakeBoldText = true
            setOnClickListener { dismiss(); onAction() }
        }
        body.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(ctx.dp(24), ctx.dp(12), ctx.dp(24), 0)
            addView(btn("Cancel").apply {
                background = rounded(0, ctx.dp(20).toFloat(), ctx.dp(1), 0xFF555555.toInt())
                setOnClickListener { dismiss() }
            }, lp(0, WRAP_CONTENT, 1f).apply { marginEnd = ctx.dp(8) })
            addView(go, lp(0, WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(8) })
        })
        return go
    }
}

/** M3 snackbar: one line and an action, over the bottom of [parent], gone after [ms]. */
class Snackbar(private val parent: FrameLayout) {
    private var view: View? = null
    private val hide = Runnable { dismiss() }

    fun show(msg: String, action: String? = null, ms: Long = 6000, onAction: (() -> Unit)? = null) {
        dismiss()
        val ctx = parent.context
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.TEXT, ctx.dp(4).toFloat()); elevation = ctx.dp(6).toFloat()
            setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(8), ctx.dp(6)); minimumHeight = ctx.dp(48)
            addView(ctx.text(msg, 14f, 0xFF1F1F1F.toInt()), lp(0, WRAP_CONTENT, 1f))
            if (action != null) addView(ctx.text(action, 14f, 0xFF0B57D0.toInt()).apply {
                paint.isFakeBoldText = true; setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10)); background = ctx.ripple()
                setOnClickListener { dismiss(); onAction?.invoke() }
            })
        }
        parent.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            val m = ctx.dp(12); setMargins(m, m, m, ctx.dp(20))
        })
        view = v
        parent.postDelayed(hide, ms)
    }

    fun dismiss() {
        parent.removeCallbacks(hide)
        view?.let { parent.removeView(it) }
        view = null
    }
}

fun ViewGroup.addAll(vararg v: View) = v.forEach { addView(it) }
