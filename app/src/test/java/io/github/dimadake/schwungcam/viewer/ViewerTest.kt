package io.github.dimadake.schwungcam.viewer

import io.github.dimadake.schwungcam.link.StemExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.sin

class TakeIndexTest {
    private val a = "SchwungCam_20261122_195709"
    private val b = "SchwungCam_20261121_101500"

    private fun row(name: String, path: String, size: Long = 10) = MediaRow("content://$path$name", name, path, size)

    @Test fun groupsEveryKindByName() {
        val takes = TakeIndex.group(listOf(
            row("$a.mp4", "Movies/SchwungCam/", 1000),
            row("${a}_raw.wav", "Music/SchwungCam/", 200),
            row("T10 Keys.wav", "Music/SchwungCam/$a/", 30),
            row("T2 Bass.wav", "Music/SchwungCam/$a/", 30),
            row("$a.ai.zip", "Documents/SchwungCam/", 5),
            row("${b}_raw.mp4", "Movies/SchwungCam/", 700),
            row("holiday.mp4", "Movies/SchwungCam/"),
            row("$a.mp4", "Movies/Other/"),
            row("x.wav", "Music/SchwungCam/notatake/"),
        ))
        assertEquals(listOf(a, b), takes.map { it.name })
        val t = takes[0]
        assertEquals(setOf(Kind.MASTERED, Kind.RAW_AUDIO, Kind.STEMS, Kind.AI), t.kinds)
        assertEquals(listOf("T2 Bass.wav", "T10 Keys.wav"), t.files[Kind.STEMS]!!.map { it.name })
        assertEquals(1265L, t.size)
        assertEquals("$a.mp4", t.video!!.name)
        // a rescued take: its raw video is the picture
        assertEquals("${b}_raw.mp4", takes[1].video!!.name)
    }

    @Test fun spareKeepsTheOnlyPicture() {
        val full = TakeIndex.group(listOf(row("$a.mp4", "Movies/SchwungCam"), row("${a}_raw.mp4", "Movies/SchwungCam"),
            row("${a}_raw.wav", "Music/SchwungCam"), row("s.wav", "Music/SchwungCam/$a"), row("$a.ai.zip", "Documents/SchwungCam")))[0]
        assertEquals(listOf("${a}_raw.mp4", "${a}_raw.wav", "s.wav"), TakeIndex.spare(full).map { it.name })
        val rescued = TakeIndex.group(listOf(row("${a}_raw.mp4", "Movies/SchwungCam"), row("${a}_raw.wav", "Music/SchwungCam")))[0]
        assertEquals(listOf("${a}_raw.wav"), TakeIndex.spare(rescued).map { it.name })
    }

    @Test fun withoutDropsFilesAndEmptyTakes() {
        val t = TakeIndex.group(listOf(row("$a.mp4", "Movies/SchwungCam"), row("s.wav", "Music/SchwungCam/$a")))[0]
        val left = t.without(setOf(t.files[Kind.STEMS]!![0].id))!!
        assertEquals(setOf(Kind.MASTERED), left.kinds)
        assertNull(t.without(t.all.map { it.id }.toSet()))
    }

    @Test fun days() {
        val takes = TakeIndex.group(listOf(row("$a.mp4", "Movies/SchwungCam"), row("$b.mp4", "Movies/SchwungCam"),
            row("SchwungCam_20250301_120000.mp4", "Movies/SchwungCam")))
        val d = TakeIndex.days(takes, LocalDate.of(2026, 11, 22))
        assertEquals(listOf("Today", "Yesterday", "1 Mar 2025"), d.map { it.label })
        assertEquals("Sun 15 Nov", TakeIndex.dayLabel(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 22)))
    }

    @Test fun formats() {
        assertEquals("2.92 GB", Fmt.size((2.92 * (1L shl 30)).toLong()))
        assertEquals("256 MB", Fmt.size(256L shl 20))
        assertEquals("24:12", Fmt.duration(1452_000))
        assertEquals("1:02:03", Fmt.duration(3723_000))
        assertEquals("09:12.4", Fmt.stamp(552.43))
    }
}

class GesturesTest {
    private val lines = """
        {"t":0.000,"type":"take","name":"SchwungCam_x","duration":60.000,"screens":3}
        {"t":0.000,"type":"screen","why":"start","file":"screens/000000.png","sheet":"sheets/0000.png"}
        {"t":1.000,"type":"pad","pad":"r4c1","note":68,"on":true,"vel":127}
        {"t":1.500,"type":"pad","pad":"r4c1","note":68,"on":false,"peak_pressure":40}
        {"t":2.000,"type":"screen","why":"during","file":"screens/000001.png","sheet":"sheets/0000.png"}
        {"t":2.000,"type":"turn","control":"knob3","delta":-12,"dur":0.840}
        {"t":3.000,"type":"step","step":7,"on":true}
        {"t":3.100,"type":"step","step":7,"on":false}
        {"t":4.000,"type":"led","led":"Play","on":true,"value":126}
        {"t":5.000,"type":"button","button":"Play","on":true}
        {"t":5.200,"type":"button","button":"Play","on":false}
        {"t":6.000,"type":"touch","control":"knob1","on":true}
        {"t":7.000,"type":"gap","lost":5}
        {"t":8.000,"type":"led","led":"Play","on":false,"value":124}
        {"t":9.000,"type":"pad","pad":"r1c8","note":99,"on":true,"vel":64}
        not json
    """.trimIndent()

    private val g = Gestures.parse(lines.lineSequence())

    @Test fun parsesEveryKind() {
        assertEquals(14, g.events.size)
        val pad = g.events.filterIsInstance<Gesture.Pad>().first()
        assertEquals(3, pad.row); assertEquals(0, pad.col); assertEquals(127, pad.vel)
        val turn = g.events.filterIsInstance<Gesture.Turn>().single()
        assertEquals(-12, turn.delta); assertEquals(0.84, turn.dur, 1e-9)
        assertEquals(listOf("Pad r4c1 · vel 127", "Knob 3 -12", "Step 7", "Play light on", "Play", "5 events lost", "Play light off", "Pad r1c8 · vel 64"),
            g.listed.map { it.text() })
    }

    @Test fun screenHoldsUntilTheNext() {
        assertEquals("screens/000000.png", g.screenAt(-1.0))
        assertEquals("screens/000000.png", g.screenAt(1.99))
        assertEquals("screens/000001.png", g.screenAt(2.0))
        assertEquals("screens/000001.png", g.screenAt(50.0))
        assertNull(Gestures(emptyList()).screenAt(1.0))
    }

    @Test fun padsLightWhileHeldThenFade() {
        val i = 3 * 8
        assertEquals(0f, g.pad(i, 0.9), 0f)
        assertEquals(1f, g.pad(i, 1.2), 1e-6f)
        assertTrue(g.pad(i, 1.6) in 0.01f..0.99f)
        assertEquals(0f, g.pad(i, 1.5 + Gestures.FADE + 0.01), 0f)
        // held at Stop stays lit; by velocity
        assertEquals(0.35f + 0.65f * 64 / 127f, g.pad(7, 9.0), 1e-6f)
    }

    @Test fun turnsLedsButtonsSteps() {
        assertEquals(0, g.turning("knob3", 1.9))
        assertEquals(-1, g.turning("knob3", 2.5))
        assertEquals(0, g.turning("knob3", 3.5))
        assertEquals(0, g.turning("knob1", 2.5))
        assertTrue(g.step(6, 3.1) > 0f)
        assertFalse(g.led("Play", 3.9)); assertTrue(g.led("Play", 4.0)); assertFalse(g.led("Play", 8.5))
        assertTrue(g.button("Play", 5.1) > 0f)
        assertTrue(g.touched("knob1", 30.0))
        assertEquals(-1, g.listedAt(0.5)); assertEquals(0, g.listedAt(1.0)); assertEquals(1, g.listedAt(2.9))
    }
}

class AudioTest {
    @Test fun snapshotIsWhatIsThereSoFar() {
        val b = Peaks.Builder(44100)
        repeat(44100 / 2) { b.add(0.25f) }
        assertEquals(25, b.snapshot().size)
        repeat(44100 / 2) { b.add(0.25f) }
        assertEquals(50, b.build().size)
    }

    @Test fun peaksOfASine() {
        val p = Peaks.Builder(44100).run {
            for (i in 0 until 44100) add((0.5 * sin(2 * PI * 100 * i / 44100)).toFloat())
            build()
        }
        assertEquals(50, p.size)
        for (k in 0 until p.size) { assertEquals(64.0, p.max[k].toDouble(), 1.0); assertEquals(-64.0, p.min[k].toDouble(), 1.0) }
        val bytes = ByteArrayOutputStream().also { p.write(it) }.toByteArray()
        val q = Peaks.read(ByteArrayInputStream(bytes))!!
        assertEquals(p.max.toList(), q.max.toList())
        assertNull(Peaks.read(ByteArrayInputStream(ByteArray(12))))
    }

    @Test fun readsTheStemsOwnHeader() {
        val h = StemExport.header24(48000, 10)
        val w = WavInfo.parse(h + ByteArray(30))!!
        assertEquals(48000, w.rate); assertEquals(24, w.bits); assertEquals(h.size.toLong(), w.dataOffset)
        // a 24-bit sample of −0.5 and of the largest
        val out = FloatArray(4)
        val bytes = byteArrayOf(0, 0, 0xC0.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        val n = if (w.channels == 1) w.toStereo(bytes, 6, out) else WavInfo(48000, 1, 24, false, 0, 6).toStereo(bytes, 6, out)
        assertEquals(2, n)
        assertEquals(-0.5f, out[0], 1e-6f); assertEquals(-0.5f, out[1], 1e-6f); assertEquals(1f, out[2], 1e-6f)
    }

    @Test fun reads16BitStereo() {
        val h = wav16Header(44100, 2, 8)
        val w = WavInfo.parse(h + ByteArray(8))!!
        assertEquals(2, w.channels); assertEquals(16, w.bits); assertEquals(2L, w.frames)
        val out = FloatArray(4)
        w.toStereo(byteArrayOf(0, 0x40, 0, 0xC0.toByte(), 0, 0, 0, 0), 8, out)
        assertEquals(0.5f, out[0], 1e-6f); assertEquals(-0.5f, out[1], 1e-6f)
        assertNull(WavInfo.parse("RIFF0000AVI xxxx".toByteArray()))
    }

    @Test fun soloAndMute() {
        assertEquals(listOf(1f, 1f, 0f), StemMix.gains(booleanArrayOf(false, false, false), booleanArrayOf(false, false, true)).toList())
        assertEquals(listOf(0f, 1f, 0f), StemMix.gains(booleanArrayOf(false, true, true), booleanArrayOf(false, false, true)).toList())
        assertEquals(0.5f, StemMix.soft(0.5f), 0f)
        assertTrue(StemMix.soft(1.2f) in 0.95f..1f && StemMix.soft(3f) <= 1f)
        assertEquals(-StemMix.soft(1.5f), StemMix.soft(-1.5f), 0f)
    }

    private fun wav16Header(rate: Int, ch: Int, dataBytes: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort()).putInt(rate).putInt(rate * ch * 2).putShort((ch * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        return b.array()
    }
}
