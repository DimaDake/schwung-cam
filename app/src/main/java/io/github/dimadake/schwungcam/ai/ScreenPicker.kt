package io.github.dimadake.schwungcam.ai

import io.github.dimadake.schwungcam.stream.SCREEN_H
import io.github.dimadake.schwungcam.stream.SCREEN_W

/** The Move screen as 1 bit per pixel, row-major, MSB first: 1024 bytes. */
object Mono {
    const val BYTES = SCREEN_W * SCREEN_H / 8

    fun pack(lum: ByteArray): ByteArray {
        val out = ByteArray(BYTES)
        for (i in 0 until SCREEN_W * SCREEN_H) if ((lum[i].toInt() and 0xFF) > 127) out[i shr 3] = (out[i shr 3].toInt() or (0x80 ushr (i and 7))).toByte()
        return out
    }

    fun lit(bits: ByteArray, x: Int, y: Int): Boolean {
        val i = y * SCREEN_W + x
        return (bits[i shr 3].toInt() and (0x80 ushr (i and 7))) != 0
    }

    /** Pixels that differ. */
    fun diff(a: ByteArray, b: ByteArray): Int {
        var n = 0
        for (i in 0 until BYTES) n += Integer.bitCount((a[i].toInt() xor b[i].toInt()) and 0xFF)
        return n
    }
}

class PickedScreen(val ns: Long, val why: String, val bits: ByteArray)

/**
 * Chooses which screens are worth an agent's attention. A Movy or Schwung
 * screen animates (knob arcs ease, LFO waves move, playheads run), so a rule
 * on screen changes would never settle, or settle constantly. Screens are
 * picked around INPUT instead: before a gesture, while one control rests
 * during a hold, at the release (while a param card is still up), and after.
 * Outside gestures, a big change (a page Move turned by itself) still counts.
 */
class ScreenPicker {
    private val recent = ArrayDeque<Pair<Long, ByteArray>>()
    val picked = ArrayList<PickedScreen>()
    private var lastSaved: ByteArray? = null
    private var now = Long.MIN_VALUE

    private var active = false
    private var lastInputNs = 0L
    private val held = HashSet<String>()
    private val controlLast = HashMap<String, Long>()
    private val rested = HashSet<String>()
    private var lastChangeCheck = Long.MIN_VALUE

    fun onScreen(lum: ByteArray, ns: Long) {
        val bits = Mono.pack(lum)
        recent.addLast(ns to bits)
        while (recent.size > RECENT) recent.removeFirst()
        if (picked.isEmpty()) save("start", ns to bits)
        advance(ns)
    }

    fun onInput(e: InputEvent) {
        if (!e.isInput) return
        if (!active) {
            active = true
            save("before", newestAtOrBefore(e.ns - BEFORE_MARGIN_NS))
        }
        lastInputNs = maxOf(lastInputNs, e.ns)
        controlLast[e.control] = e.ns
        rested.remove(e.control)
        if (e.holds && e.on == true) held += e.control
        if (e.holds && e.on == false && held.remove(e.control) && held.isEmpty()) save("release", newestBefore(e.ns))
        advance(e.ns)
    }

    fun finish() { recent.lastOrNull()?.let { save("end", it) } }

    /**
     * Moves the clock without a new screen. display-server sends a screen only
     * when it changes, so after a gesture on a still page nothing else would
     * say that 400 ms have passed. Called on every surface snapshot.
     */
    fun tick(t: Long) = advance(t)

    private fun advance(t: Long) {
        if (t > now) now = t
        if (active) {
            if (held.isNotEmpty()) {
                // one control done (a knob's turn, a step held still) while another is still down
                val due = controlLast.filter { (c, at) -> c !in rested && now - at >= REST_NS }
                if (due.isNotEmpty()) { rested += due.keys; save("during", newestAtOrBefore(due.values.max() + REST_NS)) }
            } else if (now - lastInputNs >= GESTURE_END_NS) {
                active = false
                controlLast.clear(); rested.clear()
                // by time, not "the newest": the clock may only have moved on long after
                save("after", newestAtOrBefore(lastInputNs + GESTURE_END_NS))
                lastChangeCheck = now
            }
        } else if (lastChangeCheck == Long.MIN_VALUE || now - lastChangeCheck >= CHANGE_EVERY_NS) {
            lastChangeCheck = now
            val cur = recent.lastOrNull() ?: return
            val last = lastSaved
            if (last != null && Mono.diff(last, cur.second) > CHANGE_PIXELS) save("change", cur)
        }
    }

    private fun newestAtOrBefore(t: Long) = recent.lastOrNull { it.first <= t } ?: recent.firstOrNull()
    private fun newestBefore(t: Long) = recent.lastOrNull { it.first < t } ?: recent.firstOrNull()

    private fun save(why: String, f: Pair<Long, ByteArray>?) {
        if (f == null) return
        val last = lastSaved
        if (last != null && last.contentEquals(f.second)) return
        lastSaved = f.second
        picked += PickedScreen(f.first, why, f.second)
    }

    companion object {
        /** About a second of screens at display-server's rate. */
        const val RECENT = 64
        /** A UI tick before the first input: the frame that shows what was about to change. */
        const val BEFORE_MARGIN_NS = 20_000_000L
        const val REST_NS = 300_000_000L
        const val GESTURE_END_NS = 400_000_000L
        const val CHANGE_EVERY_NS = 2_000_000_000L
        /** 3 % of 8192: a new page changes far more, a playhead or an LFO wave far less. */
        const val CHANGE_PIXELS = 246
    }
}
