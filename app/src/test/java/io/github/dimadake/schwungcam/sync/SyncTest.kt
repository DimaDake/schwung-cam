package io.github.dimadake.schwungcam.sync

import io.github.dimadake.schwungcam.stream.PcmChunk
import io.github.dimadake.schwungcam.stream.ScreenFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class SyncTest {
    private val rate = 44100

    /**
     * A stream whose phone clock runs [ppm] fast against the device clock,
     * true offset [offsetS], min transit 3 ms, jitter up to 80 ms with one
     * prompt delivery in roughly every 10.
     */
    private fun feed(env: ClockEnvelope, seconds: Int, offsetS: Double, ppm: Double, seed: Int = 1) {
        val rnd = Random(seed)
        var pos = 1_000_000L
        val n = 1470 // 33 ms ticks
        repeat(seconds * 30) {
            pos += n
            val devSec = pos.toDouble() / rate
            val phone = offsetS + devSec * (1 + ppm * 1e-6)
            val jitter = if (rnd.nextInt(10) == 0) 0.0 else rnd.nextDouble(0.0, 0.08)
            env.add(((phone + 0.003 + jitter) * 1e9).toLong(), pos)
        }
    }

    @Test fun `live offset rejects jitter`() {
        val env = ClockEnvelope(rate)
        assertNull(env.liveOffset())
        feed(env, 20, offsetS = 500.0, ppm = 0.0)
        assertEquals(500.003, env.liveOffset()!!, 0.0005)
    }

    @Test fun `final fit follows 100 ppm drift`() {
        val env = ClockEnvelope(rate)
        feed(env, 600, offsetS = 1234.5, ppm = 100.0)
        val start = 1_000_000.0 / rate
        val fit = env.fit(start, start + 600)!!
        assertEquals(1 + 100e-6, fit.b, 2e-6)
        for (t in listOf(start + 10, start + 300, start + 590)) {
            val truth = 1234.5 + t * (1 + 100e-6) + 0.003
            assertEquals("t=$t", truth, fit.phoneSec(t), 0.001)
        }
    }

    @Test fun `a congested stretch does not bend the fit`() {
        val env = ClockEnvelope(rate)
        feed(env, 120, offsetS = 10.0, ppm = 0.0)
        // ten seconds where everything arrived 200 ms late
        var pos = 1_000_000L + 121 * 30 * 1470L
        repeat(300) { pos += 1470; env.add(((10.0 + pos.toDouble() / rate + 0.2) * 1e9).toLong(), pos) }
        val start = 1_000_000.0 / rate
        val fit = env.fit(start, start + 135)!!
        assertEquals(10.003 + start + 60, fit.phoneSec(start + 60), 0.001)
    }

    @Test fun `too few points falls back to slope one`() {
        val fit = ClockEnvelope.fitPoints(listOf(1.0 to 0.5, 2.0 to 0.4))!!
        assertEquals(1.0, fit.b, 0.0); assertEquals(0.4, fit.a, 1e-12)
        assertNull(ClockEnvelope.fitPoints(emptyList()))
    }

    @Test fun `timeline maps video onto the audio clock`() {
        // phone = 100 + 1.0001·pos: the phone clock runs fast, so phone seconds are shorter in device time
        val fit = ClockFit(100.0, 1.0001)
        val firstNs = ((100 + 1.0001 * 50.0) * 1e9).toLong() // first frame at device second 50
        val tl = Timeline(fit, firstNs, rate)
        assertEquals(50.0 * rate, tl.p0, 1.0)
        assertEquals(0L, tl.videoPtsUs(0))
        // 10.001 phone seconds later is exactly 10 device seconds later
        assertEquals(10_000_000.0, tl.videoPtsUs(10_001_000).toDouble(), 2.0)
        val (skip, len) = tl.audioRange(wavStartPos = 50L * rate - 1000, endRawUs = 10_001_000)
        assertEquals(1000L, skip)
        assertEquals(10L * rate, len.toDouble().toLong(), )
    }

    private fun chunk(pos: Long, arrival: Long) = PcmChunk(pos, 1470, rate, ShortArray(2940), arrival)

    @Test fun `screen frame is dated by the pcm chunk of its tick`() {
        val env = ClockEnvelope(rate)
        val got = ArrayList<StampedScreen>()
        val st = ScreenStamper(env) { got.add(it) }
        // settle the envelope: offset 7 s, no transit
        for (i in 1..30) { val p = i * 1470L; val c = chunk(p - 1470, ((7.0 + p.toDouble() / rate) * 1e9).toLong()); env.add(c.arrivalNs, c.endPos); st.onPcm(c) }
        val p = 31 * 1470L
        val arrival = ((7.0 + p.toDouble() / rate + 0.050) * 1e9).toLong() // this tick was 50 ms late
        st.onScreen(ScreenFrame(ByteArray(1), arrival))
        assertEquals(0, got.size)
        val c = chunk(p - 1470, arrival + 1_000_000)
        env.add(c.arrivalNs, c.endPos); st.onPcm(c)
        assertEquals(1, got.size)
        assertEquals(((7.0 + p.toDouble() / rate) * 1e9), got[0].stampNs.toDouble(), 1e6)
    }

    @Test fun `without audio the arrival time is kept`() {
        val got = ArrayList<StampedScreen>()
        val st = ScreenStamper(ClockEnvelope(rate)) { got.add(it) }
        st.onScreen(ScreenFrame(ByteArray(1), 123))
        assertEquals(123L, got.single().stampNs)
    }

    @Test fun `a late chunk does not date the frame`() {
        val env = ClockEnvelope(rate)
        val got = ArrayList<StampedScreen>()
        val st = ScreenStamper(env) { got.add(it) }
        val c0 = chunk(0, 1_000_000_000); env.add(c0.arrivalNs, c0.endPos); st.onPcm(c0)
        st.onScreen(ScreenFrame(ByteArray(1), 1_100_000_000))
        val c1 = chunk(1470, 1_200_000_000); env.add(c1.arrivalNs, c1.endPos); st.onPcm(c1)
        assertEquals(1_100_000_000L, got.single().stampNs)
    }
}
