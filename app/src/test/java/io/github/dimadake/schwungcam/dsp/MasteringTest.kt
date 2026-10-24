package io.github.dimadake.schwungcam.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class MasteringTest {
    private val fs = 44100

    private fun lufsOf(seconds: Double, gen: (Int) -> Pair<Double, Double>): Double {
        val m = LoudnessMeter(fs)
        val n = (seconds * fs).toInt()
        val l = FloatArray(n); val r = FloatArray(n)
        for (i in 0 until n) { val (a, b) = gen(i); l[i] = a.toFloat(); r[i] = b.toFloat() }
        m.add(l, r, n)
        return m.integrated()
    }

    private fun amp(dbfs: Double) = 10.0.pow(dbfs / 20)
    private fun sine(i: Int, f: Double, a: Double) = a * sin(2 * PI * f * i / fs)

    // EBU Tech 3341 case 1: stereo 1 kHz sine at −23 dBFS → −23 LUFS (±0.1)
    @Test fun `EBU 3341 case 1`() {
        val v = lufsOf(20.0) { val s = sine(it, 1000.0, amp(-23.0)); s to s }
        assertEquals(-23.0, v, 0.1)
    }

    // case 2: −33 dBFS → −33 LUFS
    @Test fun `EBU 3341 case 2`() {
        val v = lufsOf(20.0) { val s = sine(it, 1000.0, amp(-33.0)); s to s }
        assertEquals(-33.0, v, 0.1)
    }

    // case 3: 10 s −36, 60 s −23, 10 s −36 → −23 (relative gate drops the quiet parts)
    @Test fun `EBU 3341 case 3 gating`() {
        val v = lufsOf(80.0) { val t = it.toDouble() / fs; val a = if (t < 10 || t >= 70) amp(-36.0) else amp(-23.0); val s = sine(it, 1000.0, a); s to s }
        assertEquals(-23.0, v, 0.1)
    }

    // case 5: −26 / −20 / −26 dBFS for 20.1 / 20 / 20.1 s → −23 LUFS (relative gate keeps all)
    @Test fun `EBU 3341 case 5`() {
        val v = lufsOf(60.2) { val t = it.toDouble() / fs; val a = if (t < 20.1 || t >= 40.1) amp(-26.0) else amp(-20.0); val s = sine(it, 1000.0, a); s to s }
        assertEquals(-23.0, v, 0.1)
    }

    @Test fun `silence measures minus infinity`() {
        assertEquals(Double.NEGATIVE_INFINITY, lufsOf(2.0) { 0.0 to 0.0 }, 0.0)
    }

    private fun maxTruePeakDb(l: FloatArray, r: FloatArray, n: Int): Double {
        // a finer reference than the limiter's own detector: 16× windowed-sinc
        var peak = 0.0
        val taps = 32
        for (ch in listOf(l, r)) for (i in taps until n - taps) for (ph in 0 until 16) {
            val t = ph / 16.0
            var acc = 0.0
            for (k in -taps + 1..taps) {
                val x = k - t
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val w = 0.5 + 0.5 * kotlin.math.cos(PI * x / taps)
                acc += ch[i + k] * sinc * w
            }
            peak = maxOf(peak, kotlin.math.abs(acc))
        }
        return 20 * log10(peak)
    }

    @Test fun `limiter holds the ceiling on worst-case inter-sample peaks`() {
        val n = fs / 2
        val l = FloatArray(n); val r = FloatArray(n)
        val lim = Limiter(fs, -1.0)
        val o = DoubleArray(2)
        // fs/4 sine at 45° phase: samples at 0.707 but the waveform peaks at 1.0; driven +12 dB
        for (i in 0 until n + lim.latency) {
            val x = if (i < n) 4.0 * sin(PI / 2 * i + PI / 4) * (if (i % 4000 < 1500) 1.0 else 0.1) else 0.0
            lim.process(x, -x * 0.5, o)
            val j = i - lim.latency
            if (j >= 0) { l[j] = o[0].toFloat(); r[j] = o[1].toFloat() }
        }
        val tp = maxTruePeakDb(l, r, n)
        assertTrue("true peak $tp dBTP", tp <= -1.0)
        assertTrue("limiter must not squash everything: $tp", tp > -2.0)
    }

    private class ArrayReader(val l: FloatArray, val r: FloatArray) : FrameReader {
        var at = 0
        override fun read(l: FloatArray, r: FloatArray, max: Int): Int {
            val k = minOf(max, this.l.size - at)
            System.arraycopy(this.l, at, l, 0, k); System.arraycopy(this.r, at, r, 0, k); at += k
            return k
        }
        override fun close() {}
    }

    /** A drum-ish synthetic take: decaying noise hits plus a bass line and a 15 Hz rumble. */
    private fun take(seconds: Int, level: Double): Pair<FloatArray, FloatArray> {
        val n = seconds * fs
        val rnd = Random(7)
        val l = FloatArray(n); val r = FloatArray(n)
        for (i in 0 until n) {
            val beat = i % (fs / 2)
            val hit = if (beat < 4000) rnd.nextDouble(-1.0, 1.0) * (1 - beat / 4000.0) else 0.0
            val bass = 0.3 * sin(2 * PI * 55 * i / fs)
            val rumble = 0.2 * sin(2 * PI * 15 * i / fs)
            l[i] = (level * (hit + bass) + rumble * level).toFloat()
            r[i] = (level * (hit * 0.8 + bass) + rumble * level).toFloat()
        }
        return l to r
    }

    private fun master(l: FloatArray, r: FloatArray, s: MasterSettings): Triple<FloatArray, FloatArray, MasterReport> {
        val ol = FloatArray(l.size); val or = FloatArray(l.size)
        var w = 0
        val rep = Mastering.run(fs, s, { ArrayReader(l, r) }, { a, b, n -> System.arraycopy(a, 0, ol, w, n); System.arraycopy(b, 0, or, w, n); w += n })
        assertEquals("length preserved", l.size, w)
        return Triple(ol, or, rep)
    }

    @Test fun `quiet take is mastered to -14 LUFS under -1 dBTP`() {
        val (l, r) = take(12, 0.05)
        val (ol, or, rep) = master(l, r, MasterSettings())
        val m = LoudnessMeter(fs); m.add(ol, or, ol.size)
        assertEquals(-14.0, m.integrated(), 0.5)
        assertTrue("report ${rep.outputLufs}", kotlin.math.abs(rep.outputLufs - m.integrated()) < 0.01)
        val tp = maxTruePeakDb(ol, or, ol.size)
        assertTrue("true peak $tp", tp <= -1.0)
    }

    @Test fun `loud preset and no glue`() {
        val (l, r) = take(10, 0.2)
        val (ol, or, _) = master(l, r, MasterSettings(glue = false, targetLufs = -9.0))
        val m = LoudnessMeter(fs); m.add(ol, or, ol.size)
        assertEquals(-9.0, m.integrated(), 0.5)
        assertTrue(maxTruePeakDb(ol, or, ol.size) <= -1.0)
    }

    @Test fun `mastering off passes samples through untouched`() {
        val (l, r) = take(2, 0.1)
        val (ol, or, _) = master(l, r, MasterSettings(enabled = false))
        assertTrue(l.contentEquals(ol) && r.contentEquals(or))
    }

    @Test fun `silent take is not amplified`() {
        val l = FloatArray(fs * 2); val r = FloatArray(fs * 2)
        val (ol, _, rep) = master(l, r, MasterSettings())
        assertTrue(ol.all { it == 0f })
        assertEquals(0.0, rep.gainDb, 0.0)
    }

    @Test fun `alignment is preserved through the limiter latency`() {
        val n = fs
        val l = FloatArray(n); val r = FloatArray(n)
        for (i in 0 until n) { val s = sine(i, 1000.0, 0.05).toFloat(); l[i] = s; r[i] = s }
        l[n / 2] = 0.06f // mark
        val (ol, _, _) = master(l, r, MasterSettings(glue = false))
        // the output sine must be in phase with the input: correlation strongly positive at lag 0
        var c0 = 0.0; var c1 = 0.0
        for (i in 1000 until n - 1000) { c0 += l[i] * ol[i]; c1 += l[i] * ol[i + 11] }
        assertTrue("lag-0 correlation $c0 vs lag-11 $c1", c0 > 0 && c0 > c1)
    }
}
