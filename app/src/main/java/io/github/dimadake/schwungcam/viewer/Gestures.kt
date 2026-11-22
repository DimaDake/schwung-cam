package io.github.dimadake.schwungcam.viewer

import io.github.dimadake.schwungcam.stream.FlatJson
import java.util.Locale

/** One line of an `ai.zip`'s timeline.jsonl (format: docs/superpowers/specs/2026-10-26-ai-timeline-design.md). */
sealed class Gesture(val t: Double) {
    /** [row] 0 = top, [col] 0 = left. */
    class Pad(t: Double, val row: Int, val col: Int, val on: Boolean, val vel: Int) : Gesture(t)
    class Step(t: Double, val step: Int, val on: Boolean) : Gesture(t)
    class Touch(t: Double, val control: String, val on: Boolean) : Gesture(t)
    class Turn(t: Double, val control: String, val delta: Int, val dur: Double) : Gesture(t)
    class Button(t: Double, val name: String, val on: Boolean) : Gesture(t)
    class Led(t: Double, val name: String, val on: Boolean) : Gesture(t)
    class Screen(t: Double, val file: String) : Gesture(t)
    class Gap(t: Double, val lost: Int) : Gesture(t)

    /** A line for the event list; null for what it does not show. */
    fun text(): String? = when (this) {
        is Pad -> if (on) "Pad r${row + 1}c${col + 1} · vel $vel" else null
        is Step -> if (on) "Step ${step}" else null
        is Touch -> null
        is Turn -> "${knobLabel(control)} ${if (delta > 0) "+" else ""}$delta"
        is Button -> if (on) name else null
        is Led -> "$name light ${if (on) "on" else "off"}"
        is Screen -> null
        is Gap -> "$lost events lost"
    }

    companion object {
        fun knobLabel(c: String) = when {
            c.startsWith("knob") -> "Knob " + c.removePrefix("knob")
            else -> c.replaceFirstChar { it.titlecase(Locale.US) }
        }
    }
}

/** A take's gestures, and what the Move looked like at any moment of the video. */
class Gestures(events: List<Gesture>) {
    val events = events.sortedBy { it.t }
    /** What the event list shows, in time order. */
    val listed: List<Gesture> = this.events.filter { it.text() != null }
    private val listedT = DoubleArray(listed.size) { listed[it].t }

    private val screens = this.events.filterIsInstance<Gesture.Screen>()
    private val screenT = DoubleArray(screens.size) { screens[it].t }

    private class Span(val from: Double, val to: Double, val level: Float)
    private val pads = Array(32) { ArrayList<Span>() }
    private val steps = Array(16) { ArrayList<Span>() }
    private val knobs = HashMap<String, ArrayList<Span>>()       // turns: level = sign of delta
    private val touches = HashMap<String, ArrayList<Span>>()
    private val buttons = HashMap<String, ArrayList<Span>>()
    private val leds = HashMap<String, ArrayList<Pair<Double, Boolean>>>()

    init {
        val padOn = HashMap<Int, Gesture.Pad>()
        val touchOn = HashMap<String, Double>()
        val buttonOn = HashMap<String, Double>()
        val end = Double.POSITIVE_INFINITY
        for (e in this.events) when (e) {
            is Gesture.Pad -> {
                val i = e.row * 8 + e.col
                if (i !in 0 until 32) continue
                if (e.on) padOn[i] = e
                else padOn.remove(i)?.let { pads[i] += Span(it.t, e.t, it.vel / 127f) }
            }
            is Gesture.Step -> if (e.on && e.step in 1..16) steps[e.step - 1] += Span(e.t, e.t, 1f)
            is Gesture.Turn -> knobs.getOrPut(e.control) { ArrayList() } += Span(e.t, e.t + e.dur, if (e.delta < 0) -1f else 1f)
            is Gesture.Touch -> if (e.on) touchOn[e.control] = e.t
                else touchOn.remove(e.control)?.let { touches.getOrPut(e.control) { ArrayList() } += Span(it, e.t, 1f) }
            is Gesture.Button -> if (e.on) buttonOn[e.name] = e.t
                else buttonOn.remove(e.name)?.let { buttons.getOrPut(e.name) { ArrayList() } += Span(it, e.t, 1f) }
            is Gesture.Led -> leds.getOrPut(e.name) { ArrayList() } += e.t to e.on
            else -> {}
        }
        // held at Stop: lit to the end of the video
        for ((i, p) in padOn) pads[i] += Span(p.t, end, p.vel / 127f)
        for ((c, t) in touchOn) touches.getOrPut(c) { ArrayList() } += Span(t, end, 1f)
        for ((b, t) in buttonOn) buttons.getOrPut(b) { ArrayList() } += Span(t, end, 1f)
    }

    /** The zip entry of the screen showing at [t]: the newest saved at or before it, else the first one. */
    fun screenAt(t: Double): String? {
        if (screens.isEmpty()) return null
        val i = floorIndex(screenT, t)
        return screens[maxOf(i, 0)].file
    }

    /** Index in [listed] of the newest event at or before [t]; -1 before the first. */
    fun listedAt(t: Double): Int = floorIndex(listedT, t)

    /** Pad [i] (row × 8 + col) brightness 0…1: by velocity while held, fading out after release. */
    fun pad(i: Int, t: Double): Float = level(pads[i], t, FADE)

    fun step(i: Int, t: Double): Float = level(steps[i], t, FADE)

    /** −1 / +1 while a turn of [control] runs (its direction), 0 otherwise. */
    fun turning(control: String, t: Double): Int {
        val s = knobs[control] ?: return 0
        val i = floorFrom(s, t)
        if (i < 0) return 0
        val sp = s[i]
        return if (t <= sp.to + TURN_TAIL) sp.level.toInt() else 0
    }

    fun touched(control: String, t: Double): Boolean = level(touches[control].orEmpty(), t, 0.0) > 0f

    fun button(name: String, t: Double): Float = level(buttons[name].orEmpty(), t, FADE)

    /** The light's state at [t]; off before its first line. */
    fun led(name: String, t: Double): Boolean {
        val l = leds[name] ?: return false
        var lo = 0; var hi = l.size - 1; var ans = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (l[m].first <= t) { ans = m; lo = m + 1 } else hi = m - 1 }
        return ans >= 0 && l[ans].second
    }

    private fun level(spans: List<Span>, t: Double, fade: Double): Float {
        // spans of one control do not overlap, so the newest start at or before t decides
        val i = floorFrom(spans, t)
        if (i < 0) return 0f
        val s = spans[i]
        if (t <= s.to) return 0.35f + 0.65f * s.level
        if (fade <= 0) return 0f
        val k = 1 - (t - s.to) / fade
        return if (k <= 0) 0f else ((0.35f + 0.65f * s.level) * k).toFloat()
    }

    private fun floorFrom(spans: List<Span>, t: Double): Int {
        var lo = 0; var hi = spans.size - 1; var ans = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (spans[m].from <= t) { ans = m; lo = m + 1 } else hi = m - 1 }
        return ans
    }

    companion object {
        const val FADE = 0.3
        const val TURN_TAIL = 0.15
        val LEDS = listOf("Play", "Record", "Sample", "Loop", "Mute")

        fun floorIndex(a: DoubleArray, t: Double): Int {
            var lo = 0; var hi = a.size - 1; var ans = -1
            while (lo <= hi) { val m = (lo + hi) ushr 1; if (a[m] <= t) { ans = m; lo = m + 1 } else hi = m - 1 }
            return ans
        }

        private val PAD = Regex("r(\\d+)c(\\d+)")

        fun parse(lines: Sequence<String>): Gestures = Gestures(lines.mapNotNull(::line).toList())

        /** One timeline.jsonl line; null for the `take` header and anything unknown. */
        fun line(s: String): Gesture? {
            if (s.isBlank()) return null
            val m = FlatJson.parse(s)
            val t = m["t"]?.toDoubleOrNull() ?: return null
            val on = m["on"] == "true"
            return when (m["type"]) {
                "pad" -> PAD.matchEntire(m["pad"] ?: "")?.let {
                    Gesture.Pad(t, it.groupValues[1].toInt() - 1, it.groupValues[2].toInt() - 1, on, m["vel"]?.toIntOrNull() ?: 0)
                }
                "step" -> m["step"]?.toIntOrNull()?.let { Gesture.Step(t, it, on) }
                "touch" -> m["control"]?.let { Gesture.Touch(t, it, on) }
                "turn" -> m["control"]?.let { Gesture.Turn(t, it, m["delta"]?.toIntOrNull() ?: 0, m["dur"]?.toDoubleOrNull() ?: 0.0) }
                "button" -> m["button"]?.let { Gesture.Button(t, it, on) }
                "led" -> m["led"]?.let { Gesture.Led(t, it, on) }
                "screen" -> m["file"]?.let { Gesture.Screen(t, it) }
                "gap" -> Gesture.Gap(t, m["lost"]?.toIntOrNull() ?: 0)
                else -> null
            }
        }
    }
}
