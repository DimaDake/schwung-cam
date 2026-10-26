package io.github.dimadake.schwungcam.ai

/**
 * One thing the hands did, or one transport light changing. [control] names
 * the control the way README.md documents it ("r2c5", "step3", "knob3",
 * "Play"). [on] is a press (true) or release (false); null for a [TURN].
 */
class InputEvent(
    val ns: Long, val type: String, val control: String,
    val on: Boolean? = null, val value: Int = 0, val note: Int = -1,
) {
    /** Held while on: these keep a gesture open. */
    val holds get() = type == PAD || type == STEP || type == TOUCH || type == BUTTON || type == NOTE

    /** Something the hands did, as opposed to a light or a lost-events marker. */
    val isInput get() = type != LED && type != GAP

    companion object {
        const val PAD = "pad"; const val STEP = "step"; const val TOUCH = "touch"; const val TURN = "turn"
        const val BUTTON = "button"; const val NOTE = "note"; const val LED = "led"; const val GAP = "gap"
    }
}

/**
 * Turns successive [SurfaceSnapshot]s into [InputEvent]s: new ring slots,
 * encoder counter changes (a turn is not a ring event — it would flush every
 * press out of 32 slots in a second), pad pressure peaks and transport LEDs.
 */
class SurfaceEvents {
    private var lastCount = -1L
    private var lastEnc = IntArray(0)
    private val ledOn = HashMap<String, Boolean>()
    private val peak = IntArray(128)
    private val padDown = BooleanArray(128)

    /** [stampNs] is the phone time the snapshot was taken (its tick, through the clock envelope). */
    fun onSnapshot(s: SurfaceSnapshot, stampNs: Long): List<InputEvent> {
        val out = ArrayList<InputEvent>()
        if (lastCount < 0 || s.eventCount < lastCount) {
            // first snapshot, or the Move restarted: what came before is not this take's
            lastCount = s.eventCount; lastEnc = s.encPos.copyOf()
            leds(s, stampNs, out)
            return out
        }
        val n = s.eventCount - lastCount
        if (n > SurfaceSnapshot.RING) out += InputEvent(stampNs, InputEvent.GAP, "", value = (n - SurfaceSnapshot.RING).toInt())
        for (i in maxOf(lastCount, s.eventCount - SurfaceSnapshot.RING) until s.eventCount) {
            val e = s.ring[(i % SurfaceSnapshot.RING).toInt()]
            val age = (s.frame - e.frame) and 0xFFFFFFFFL
            decode(e, stampNs - age.coerceAtMost(MAX_AGE_FRAMES) * SurfaceSnapshot.FRAME_NS)?.let { out += it }
        }
        lastCount = s.eventCount
        for (i in 0 until SurfaceSnapshot.ENCODERS) {
            // int16 counters wrap; a wrap is still a small step
            val d = ((s.encPos[i] - lastEnc[i] + 32768) and 0xFFFF) - 32768
            if (d != 0) out += InputEvent(stampNs, InputEvent.TURN, ENCODER_NAMES[i], value = d)
        }
        lastEnc = s.encPos.copyOf()
        for (note in 68..99) if (padDown[note]) peak[note] = maxOf(peak[note], s.notePressure[note])
        leds(s, stampNs, out)
        return out
    }

    private fun decode(e: RawEvent, ns: Long): InputEvent? {
        val type = e.status and 0xF0
        val n = e.d1
        if (type == 0x90 || type == 0x80) {
            val on = type == 0x90 && e.d2 > 0
            return when (n) {
                in 68..99 -> {
                    val idx = n - 68
                    // the rack counts up from the bottom-left; r1 is the top row
                    val name = "r${4 - idx / 8}c${idx % 8 + 1}"
                    if (on) { padDown[n] = true; peak[n] = 0; InputEvent(ns, InputEvent.PAD, name, true, e.d2, n) }
                    else { padDown[n] = false; InputEvent(ns, InputEvent.PAD, name, false, peak[n], n) }
                }
                in 16..31 -> InputEvent(ns, InputEvent.STEP, "step${n - 15}", on, e.d2, n)
                // capacitive knob touch: on is 127, off anything below 64
                in 0..9 -> InputEvent(ns, InputEvent.TOUCH, TOUCH_NAMES[n], type == 0x90 && e.d2 >= 64)
                else -> InputEvent(ns, InputEvent.NOTE, "n$n", on, e.d2, n)
            }
        }
        if (type == 0xB0) {
            if (n == 114 || n == 115) return null      // jack detection, not a hand
            return InputEvent(ns, InputEvent.BUTTON, BUTTONS[n] ?: "cc$n", e.d2 >= 64)
        }
        return null
    }

    private fun leds(s: SurfaceSnapshot, ns: Long, out: MutableList<InputEvent>) {
        for ((cc, name) in LEDS) {
            val on = s.ccRgbLit[cc] ?: (s.ccLed[cc] !in UNLIT)
            if (ledOn[name] != on) { ledOn[name] = on; out += InputEvent(ns, InputEvent.LED, name, on, s.ccLed[cc]) }
        }
    }

    companion object {
        /** A slot older than this is a stale ring entry, not news; date it no earlier. */
        private const val MAX_AGE_FRAMES = 1000L
        val ENCODER_NAMES = Array(10) { if (it < 8) "knob${it + 1}" else if (it == 8) "volume" else "jog" }
        /** Touch notes 0-7 are knobs 1-8, 8 the volume knob, 9 the jog wheel: the same names as their turns. */
        val TOUCH_NAMES = ENCODER_NAMES
        val BUTTONS = mapOf(
            3 to "JogClick", 40 to "Track4", 41 to "Track3", 42 to "Track2", 43 to "Track1",
            49 to "Shift", 50 to "Menu", 51 to "Back", 52 to "Capture", 54 to "Down", 55 to "Up",
            56 to "Undo", 58 to "Loop", 60 to "Copy", 62 to "Left", 63 to "Right",
            85 to "Play", 86 to "Record", 88 to "Mute", 118 to "Sample", 119 to "Delete",
        )
        /**
         * Palette blacks and dark greys. Move does not switch a transport light
         * off: a stopped Play reads 124 (#141414) on the device.
         */
        val UNLIT = setOf(0, 117, 119, 123, 124)
        val LEDS = listOf(85 to "Play", 86 to "Record", 118 to "Sample", 58 to "Loop", 88 to "Mute")
    }
}
