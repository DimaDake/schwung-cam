package io.github.dimadake.schwungcam.ai

import io.github.dimadake.schwungcam.stream.SCREEN_H
import io.github.dimadake.schwungcam.stream.SCREEN_W
import io.github.dimadake.schwungcam.stream.SseParser
import io.github.dimadake.schwungcam.stream.StreamEvents
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.io.DataInputStream
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Builds `surface_live_shm_t` bytes the way the shim lays them out. */
private class Shm {
    val b = ByteArray(SurfaceSnapshot.SIZE).also { "SURFLV1".toByteArray().copyInto(it) }
    private var count = 0L
    var frame = 1000L

    private fun u32(o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte() }

    fun event(status: Int, d1: Int, d2: Int, atFrame: Long = frame) {
        val o = 1976 + (count % 32).toInt() * 8
        u32(o, atFrame); b[o + 4] = status.toByte(); b[o + 5] = d1.toByte(); b[o + 6] = d2.toByte()
        count++; u32(20, count)
    }

    fun enc(i: Int, v: Int) { b[1944 + 2 * i] = v.toByte(); b[1945 + 2 * i] = (v shr 8).toByte() }
    fun ccLed(cc: Int, v: Int) { b[24 + 256 + cc] = v.toByte() }
    fun pressure(note: Int, v: Int) { b[24 + 13 * 128 + note] = v.toByte() }
    fun snap(ns: Long = 0) = SurfaceSnapshot.decode(frame, b.copyOf(), ns)!!
}

private fun screen(seed: Int, page: Int = 0): ByteArray {
    // a page (a block whose place depends on [page]) plus a 2-pixel "animation" that moves every frame
    val lum = ByteArray(SCREEN_W * SCREEN_H)
    for (y in 10 until 30) for (x in 0 until 40) lum[y * SCREEN_W + x + page * 60] = -1
    lum[60 * SCREEN_W + seed % SCREEN_W] = -1
    lum[61 * SCREEN_W + seed % SCREEN_W] = -1
    return lum
}

private const val MS = 1_000_000L

/** A PNG read back by the spec, independently of [Png1]: CRCs checked, IDAT inflated. */
private class Png(bytes: ByteArray) {
    var w = 0; var h = 0; var depth = 0; var colour = -1
    private var pixels = ByteArray(0)

    init {
        val d = DataInputStream(ByteArrayInputStream(bytes))
        val sig = ByteArray(8).also { d.readFully(it) }
        assertArrayEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10), sig)
        val idat = ByteArrayOutputStream()
        while (true) {
            val len = d.readInt()
            val type = ByteArray(4).also { d.readFully(it) }
            val data = ByteArray(len).also { d.readFully(it) }
            assertEquals(CRC32().apply { update(type); update(data) }.value.toInt(), d.readInt())
            when (String(type)) {
                "IHDR" -> DataInputStream(ByteArrayInputStream(data)).run { w = readInt(); h = readInt(); depth = read(); colour = read() }
                "IDAT" -> idat.write(data)
                "IEND" -> break
            }
        }
        val inf = Inflater().apply { setInput(idat.toByteArray()) }
        pixels = ByteArray(((w + 7) / 8 + 1) * h)
        assertEquals(pixels.size, inf.inflate(pixels))
    }

    fun white(x: Int, y: Int): Boolean {
        val stride = (w + 7) / 8 + 1
        assertEquals(0, pixels[y * stride].toInt())            // filter: none
        return (pixels[y * stride + 1 + x / 8].toInt() and (0x80 ushr (x % 8))) != 0
    }
}

class AiTest {
    @Test fun `snapshot decodes at the offsets the shim pins`() {
        val s = Shm()
        s.event(0x90, 70, 100); s.enc(8, -3); s.ccLed(85, 127); s.pressure(70, 55)
        val d = s.snap()
        assertEquals(1L, d.eventCount)
        assertEquals(70, d.ring[0].d1); assertEquals(100, d.ring[0].d2); assertEquals(0x90, d.ring[0].status)
        assertEquals(-3, d.encPos[8]); assertEquals(127, d.ccLed[85]); assertEquals(55, d.notePressure[70])
        assertNull(d.ccRgbLit[85])
        assertNull(SurfaceSnapshot.decode(0, ByteArray(SurfaceSnapshot.SIZE), 0))   // no magic
    }

    @Test fun `a snapshot captured from a Move (schwung 1-8-2) decodes through the stream path`() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("surface-schwung-1.8.2.sse")!!.readBytes()
        var got: Any? = null
        SseParser { got = StreamEvents.decode(it) }.feed(bytes, bytes.size, 7)
        val snap = got as SurfaceSnapshot
        assertEquals(59459L, snap.frame); assertEquals(232L, snap.eventCount); assertEquals(7L, snap.arrivalNs)
        assertEquals(124, snap.ccLed[85])                 // a stopped Play: dim, which reads as off
        val leds = SurfaceEvents().onSnapshot(snap, 0).filter { it.type == InputEvent.LED }
        assertEquals(false, leds.single { it.control == "Play" }.on)
    }

    @Test fun `ring events are named and dated from their own frame`() {
        val s = Shm(); val ev = SurfaceEvents()
        assertTrue(ev.onSnapshot(s.snap(), 0).none { it.isInput })   // baseline
        s.frame = 1100
        s.event(0x90, 68, 90, atFrame = 1090)     // bottom-left pad
        s.event(0x90, 99, 64, atFrame = 1095)     // top-right pad
        s.event(0x90, 2, 127); s.event(0xB0, 85, 127); s.event(0x90, 18, 100)
        val out = ev.onSnapshot(s.snap(), 10_000 * MS)
        assertEquals(listOf("r4c1", "r1c8", "knob3", "Play", "step3"), out.map { it.control })
        assertEquals(10_000 * MS - 10 * SurfaceSnapshot.FRAME_NS, out[0].ns)
        assertEquals(listOf(true, true, true, true, true), out.map { it.on })
    }

    @Test fun `a lapped ring reports the loss and keeps the newest 32`() {
        val s = Shm(); val ev = SurfaceEvents()
        ev.onSnapshot(s.snap(), 0)
        repeat(40) { s.event(0x90, 16, 100) }
        val out = ev.onSnapshot(s.snap(), 0)
        assertEquals(8, out.single { it.type == InputEvent.GAP }.value)
        assertEquals(32, out.count { it.type == InputEvent.STEP })
    }

    @Test fun `turns come from the counters, wrapping included, and a release carries the pressure peak`() {
        val s = Shm(); val ev = SurfaceEvents()
        s.enc(0, 32760); ev.onSnapshot(s.snap(), 0)
        s.enc(0, -32766)                                  // +10 across the int16 wrap
        assertEquals(10, ev.onSnapshot(s.snap(), 0).single { it.type == InputEvent.TURN }.value)
        s.event(0x90, 80, 100); ev.onSnapshot(s.snap(), 0)
        s.pressure(80, 70); ev.onSnapshot(s.snap(), 0)
        s.pressure(80, 30); ev.onSnapshot(s.snap(), 0)
        s.pressure(80, 0); s.event(0x80, 80, 0)
        assertEquals(70, ev.onSnapshot(s.snap(), 0).single { it.type == InputEvent.PAD }.value)
    }

    @Test fun `transport lights are reported once per change`() {
        val s = Shm(); val ev = SurfaceEvents()
        val first = ev.onSnapshot(s.snap(), 0).filter { it.type == InputEvent.LED }
        assertEquals(5, first.size)                      // the state the take opened in
        assertEquals(0, ev.onSnapshot(s.snap(), 0).count { it.type == InputEvent.LED })
        s.ccLed(85, 124)                                  // Move's stopped Play: dark grey, not off
        assertEquals(0, ev.onSnapshot(s.snap(), 0).count { it.type == InputEvent.LED })
        s.ccLed(85, 126)
        assertEquals(listOf("Play" to true), ev.onSnapshot(s.snap(), 0).filter { it.type == InputEvent.LED }.map { it.control to it.on })
    }

    /** 60 fps of an animated screen from [from] to [to] ms. */
    private fun ScreenPicker.frames(from: Int, to: Int, page: Int = 0) { for (t in from until to step 16) onScreen(screen(t / 16, page), t * MS) }

    private fun turn(p: ScreenPicker, control: String, from: Int, to: Int) {
        for (t in from until to step 30) { p.frames(t, t + 30); p.onInput(InputEvent(t * MS, InputEvent.TURN, control, value = 1)) }
    }

    @Test fun `a knob gesture over an animated screen keeps four screens, not a hundred`() {
        val p = ScreenPicker()
        p.frames(0, 1000)
        p.onInput(InputEvent(1000 * MS, InputEvent.TOUCH, "knob3", true))
        turn(p, "knob3", 1000, 2000)
        p.onInput(InputEvent(2000 * MS, InputEvent.TOUCH, "knob3", false))
        p.frames(2000, 4000)
        assertEquals(listOf("start", "before", "release", "after"), p.picked.map { it.why })
        assertTrue(p.picked[1].ns <= 980 * MS)            // before the touch, not on it
        assertTrue(p.picked[2].ns < 2000 * MS)            // the card is still up
    }

    @Test fun `an animation with no input saves nothing, a page change does`() {
        val p = ScreenPicker()
        p.frames(0, 10_000)
        assertEquals(listOf("start"), p.picked.map { it.why })
        p.frames(10_000, 13_000, page = 1)
        assertEquals(listOf("start", "change"), p.picked.map { it.why })
    }

    @Test fun `a held step with two knob turns keeps each turn's result`() {
        val p = ScreenPicker()
        p.frames(0, 1000)
        p.onInput(InputEvent(1000 * MS, InputEvent.STEP, "step3", true))
        turn(p, "knob1", 1100, 1500)
        p.frames(1500, 2000)
        turn(p, "knob2", 2000, 2400)
        p.frames(2400, 3000)
        p.onInput(InputEvent(3000 * MS, InputEvent.STEP, "step3", false))
        p.frames(3000, 4000)
        assertEquals(listOf("start", "before", "during", "during", "during", "release", "after"), p.picked.map { it.why })
    }

    @Test fun `on a still page the after frame comes from the clock, not from a later screen`() {
        val p = ScreenPicker()
        p.onScreen(screen(0, 0), 0)
        p.onInput(InputEvent(1000 * MS, InputEvent.TOUCH, "knob1", true))
        p.onScreen(screen(1, 1), 1050 * MS)               // the param card
        p.onInput(InputEvent(1500 * MS, InputEvent.TOUCH, "knob1", false))
        p.onScreen(screen(2, 0), 1520 * MS)               // card gone, value changed
        // display-server sends nothing more: the page is still; only surface snapshots tick
        for (t in 1600..3000 step 170) p.tick(t * MS)
        p.onScreen(screen(3, 1), 9000 * MS)               // much later, unrelated
        // "before" is the start frame itself, so it is kept once; the 9 s page is a change of its own
        assertEquals(listOf("start", "release", "after", "change"), p.picked.map { it.why })
        assertEquals(listOf(0L, 1050 * MS, 1520 * MS, 9000 * MS), p.picked.map { it.ns })
    }

    @Test fun `identical screens are kept once`() {
        val p = ScreenPicker()
        val still = screen(0)
        for (t in 0 until 1000 step 16) p.onScreen(still, t * MS)
        p.onInput(InputEvent(500 * MS, InputEvent.PAD, "r1c1", true, 100, 92))
        p.onInput(InputEvent(600 * MS, InputEvent.PAD, "r1c1", false, 0, 92))
        for (t in 1000 until 2000 step 16) p.onScreen(still, t * MS)
        p.finish()
        assertEquals(listOf("start"), p.picked.map { it.why })
    }

    @Test fun `one knob's turns merge until it rests`() {
        val ev = (0 until 10).map { InputEvent(it * 50 * MS, InputEvent.TURN, "knob1", value = 2) } +
            InputEvent(900 * MS, InputEvent.TURN, "knob1", value = -1) +
            InputEvent(100 * MS, InputEvent.PAD, "r1c1", true, 90, 92)
        val lines = AiExport.eventLines(ev) { it / 1e9 }.map { it.second }
        assertEquals(
            listOf(
                """{"t":0.000,"type":"turn","control":"knob1","delta":20,"dur":0.450}""",
                """{"t":0.100,"type":"pad","pad":"r1c1","note":92,"on":true,"vel":90}""",
                """{"t":0.900,"type":"turn","control":"knob1","delta":-1,"dur":0.000}""",
            ), lines,
        )
    }

    @Test fun `screens are 1-bit PNGs that decode to the same pixels`() {
        val bits = Mono.pack(screen(5))
        val img = Png(Png1.encode(Bitmap1.screen(bits, 4)))
        assertEquals(512, img.w); assertEquals(256, img.h); assertEquals(1, img.depth); assertEquals(0, img.colour)
        for (y in 0 until SCREEN_H) for (x in 0 until SCREEN_W) assertEquals("$x,$y", Mono.lit(bits, x, y), img.white(x * 4 + 3, y * 4 + 2))
        assertTrue(Png1.encode(Bitmap1.screen(bits, 4)).size < 3000)
    }

    @Test fun `the zip holds a readme, the timeline, every screen and its sheet`() {
        val picked = (0 until 20).map { PickedScreen(it * 100 * MS, "change", Mono.pack(screen(it, it % 2))) }
        val events = listOf(InputEvent(150 * MS, InputEvent.BUTTON, "Play", true))
        val out = ByteArrayOutputStream()
        AiExport.write(out, "Take", 5.0, AiTake(events, picked)) { it / 1e9 }
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        assertEquals(listOf("README.md", "timeline.jsonl") + (0 until 20).map { AiExport.screenFile(it) } + listOf("sheets/0000.png", "sheets/0001.png"), files.keys.toList())
        val lines = String(files["timeline.jsonl"]!!).trim().lines()
        assertEquals("""{"t":0.000,"type":"take","name":"Take","duration":5.000,"screens":20}""", lines[0])
        assertEquals("""{"t":0.150,"type":"button","button":"Play","on":true}""", lines[3])
        // screen 15 ends sheet 0, screen 16 opens sheet 1 (one line down for the button)
        assertTrue(lines[17], lines[17].contains("screens/000015.png") && lines[17].contains("sheets/0000.png"))
        assertTrue(lines[18], lines[18].contains("screens/000016.png") && lines[18].contains("sheets/0001.png"))
        val sheet = Png(files["sheets/0000.png"]!!)
        assertEquals(4 * (256 + 2) + 5 * 8, sheet.w)
        assertNotNull(files["sheets/0001.png"]?.let(::Png))
    }

    @Test fun `digits draw as their glyphs`() {
        val b = Bitmap1(6, 7)
        Font5x7.draw(b, "1", 0, 0, 1)
        val rows = (0 until 7).map { y -> (0 until 5).joinToString("") { x -> if (b[x, y]) "1" else "0" } }
        assertArrayEquals(arrayOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"), rows.toTypedArray())
    }
}
