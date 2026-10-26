package io.github.dimadake.schwungcam.ai

import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What a take collected, in phone time. */
class AiTake(val events: List<InputEvent>, val screens: List<PickedScreen>)

/**
 * Writes `<take>.ai.zip`: README.md, timeline.jsonl, screens/, sheets/.
 * [toSec] maps phone time onto the video's own clock (seconds from its first
 * frame), so a caption tool can place a line without knowing how the take was synced.
 */
object AiExport {
    fun write(out: OutputStream, name: String, durationSec: Double, take: AiTake, toSec: (Long) -> Double) {
        val screens = take.screens
            .map { it to toSec(it.ns).coerceIn(0.0, durationSec) }
            .sortedBy { it.second }
        val lines = ArrayList<Pair<Double, String>>()
        screens.forEachIndexed { i, (s, t) ->
            lines += t to obj(t, "screen", "why" to s.why, "file" to screenFile(i), "sheet" to sheetFile(i / ContactSheet.PER_SHEET))
        }
        for ((t, line) in eventLines(take.events, toSec)) if (t in 0.0..durationSec + TAIL_S) lines += t to line
        lines.sortBy { it.first }

        ZipOutputStream(out).use { z ->
            fun put(path: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(path)); z.write(bytes); z.closeEntry() }
            put("README.md", README.toByteArray())
            val tl = StringBuilder()
            tl.append(obj(0.0, "take", "name" to name, "duration" to round(durationSec), "screens" to screens.size)).append('\n')
            for ((_, l) in lines) tl.append(l).append('\n')
            put("timeline.jsonl", tl.toString().toByteArray())
            screens.forEachIndexed { i, (s, _) -> put(screenFile(i), Png1.encode(Bitmap1.screen(s.bits, 4))) }
            screens.chunked(ContactSheet.PER_SHEET).forEachIndexed { k, chunk ->
                val tiles = chunk.mapIndexed { j, (s, t) -> label(k * ContactSheet.PER_SHEET + j, t) to s.bits }
                put(sheetFile(k), Png1.encode(ContactSheet.render(tiles)))
            }
        }
    }

    /** Events as timeline lines; a run of one knob's turns (no 300 ms pause) becomes one line. */
    fun eventLines(events: List<InputEvent>, toSec: (Long) -> Double): List<Pair<Double, String>> {
        val out = ArrayList<Pair<Double, String>>()
        val open = HashMap<String, Triple<Long, Long, Int>>()   // control → first ns, last ns, delta
        fun flush(c: String) {
            val (a, b, d) = open.remove(c) ?: return
            val t = toSec(a)
            out += t to obj(t, InputEvent.TURN, "control" to c, "delta" to d, "dur" to round((b - a) / 1e9))
        }
        for (e in events.sortedBy { it.ns }) {
            for (c in open.filterValues { e.ns - it.second >= TURN_SPLIT_NS }.keys) flush(c)
            if (e.type == InputEvent.TURN) {
                val o = open[e.control]
                open[e.control] = if (o == null) Triple(e.ns, e.ns, e.value) else Triple(o.first, e.ns, o.third + e.value)
                continue
            }
            // the lights at the first snapshot are the state the take opened in
            val t = if (e.type == InputEvent.LED) maxOf(0.0, toSec(e.ns)) else toSec(e.ns)
            out += t to when (e.type) {
                InputEvent.PAD -> if (e.on == true) obj(t, e.type, "pad" to e.control, "note" to e.note, "on" to true, "vel" to e.value)
                    else obj(t, e.type, "pad" to e.control, "note" to e.note, "on" to false, "peak_pressure" to e.value)
                InputEvent.STEP -> obj(t, e.type, "step" to e.control.removePrefix("step").toInt(), "on" to e.on)
                InputEvent.TOUCH -> obj(t, e.type, "control" to e.control, "on" to e.on)
                InputEvent.BUTTON -> obj(t, e.type, "button" to e.control, "on" to e.on)
                InputEvent.LED -> obj(t, e.type, "led" to e.control, "on" to e.on, "value" to e.value)
                InputEvent.GAP -> obj(t, e.type, "lost" to e.value)
                else -> obj(t, e.type, "note" to e.note, "on" to e.on, "vel" to e.value)
            }
        }
        for (c in open.keys.toList()) flush(c)
        return out.sortedBy { it.first }
    }

    fun screenFile(i: Int) = "screens/%06d.png".format(Locale.US, i)
    fun sheetFile(k: Int) = "sheets/%04d.png".format(Locale.US, k)
    private fun label(i: Int, t: Double) = "#%03d %.2fs".format(Locale.US, i, t)
    private fun round(v: Double) = Math.round(v * 1000) / 1000.0

    private fun obj(t: Double, type: String, vararg kv: Pair<String, Any?>): String {
        val sb = StringBuilder("{\"t\":").append(String.format(Locale.US, "%.3f", t)).append(",\"type\":\"").append(type).append('"')
        for ((k, v) in kv) {
            if (v == null) continue
            sb.append(",\"").append(k).append("\":")
            when (v) {
                is String -> sb.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                is Double -> sb.append(String.format(Locale.US, "%.3f", v))
                else -> sb.append(v)
            }
        }
        return sb.append('}').toString()
    }

    /** Merges a knob's turns into one gesture unless it rests this long. */
    const val TURN_SPLIT_NS = 300_000_000L
    /** Audio and events run a moment past the last video frame. */
    private const val TAIL_S = 0.5

    val README = """
        # Schwung Cam AI timeline

        What the hands did on an Ableton Move (running Schwung) during one video take,
        and what its 128×64 screen showed. Use it to caption the video with the same name.

        ## Files

        - `timeline.jsonl` — one JSON object per line, sorted by `t`: seconds from the
          video's first frame, on the video's own clock.
        - `screens/NNNNNN.png` — the Move screen, 4× (512×256), white on black as on the device.
        - `sheets/NNNN.png` — 16 screens per image in a 4×4 grid, each labelled
          `#<screen number> <t>s`. Skim these first; open a single screen only for detail.

        ## How screens were chosen

        Not every frame: the screen animates. A screen is kept around each GESTURE
        (from the first touch or press until nothing is held and nothing happened for
        0.4 s), with `why`:

        - `before` — just before the gesture: the state it changed
        - `during` — one control came to rest while another is still held
          (e.g. a knob turned while a step is held)
        - `release` — just before the last control was let go; a parameter card
          (name and value) is often only visible here
        - `after` — when the gesture ended
        - `change` — the screen changed a lot with no input (a page Move turned itself)
        - `start`, `end` — the first and last screen of the take

        Compare a `before` with its `after` to see what a gesture did.

        ## Events (`type`)

        - `take` — first line: `name`, `duration`, `screens`
        - `pad` — `pad` "r1c1".."r4c8" (r1 = top row, c1 = left), `note` 68-99, `on`,
          `vel` (press) or `peak_pressure` (release; 0 = no aftertouch)
        - `step` — the 16 step buttons below the pads, `step` 1-16, `on`
        - `touch` — a capacitive touch: `control` knob1-8, volume, jog; `on`
        - `turn` — one burst of turning: `control`, `delta` (detents, + = clockwise),
          `dur` seconds. A pause of 0.3 s starts a new burst.
        - `button` — `button` Play, Record, Sample, Loop, Mute, Shift, Menu, Back, Capture,
          Undo, Copy, Delete, Up, Down, Left, Right, JogClick, Track1-4 (or `ccN`); `on`
        - `led` — a transport light: `led` Play, Record, Sample, Loop, Mute; `on`, and
          `value`, Move's palette index (dark grey = off: Move dims, it does not switch off).
          The first one at t≈0 is the state the take started in (Play on = already playing).
        - `screen` — `why`, `file`, `sheet`
        - `gap` — `lost` events were not captured (input faster than the stream)

        ## The Move

        Four rows of eight pads, 16 step buttons beneath, eight touch-sensitive knobs
        above the screen, a volume knob and a clickable jog wheel. Track1-4 are the
        buttons beside the pads. Shift is a modifier: Shift held while pressing another
        control is one gesture with a different meaning.

        Only the Move's own controls are here: notes played back by its sequencer and
        external USB MIDI are not. The audio in the video is what the Move played.
    """.trimIndent() + "\n"
}
