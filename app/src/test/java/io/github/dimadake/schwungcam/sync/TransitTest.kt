package io.github.dimadake.schwungcam.sync

import io.github.dimadake.schwungcam.link.LinkChannel
import io.github.dimadake.schwungcam.link.StemRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class TransitTest {
    private val rate = 44100

    /** Music-like: decaying noise bursts on an uneven grid, so no lag but the true one fits. */
    private fun signal(frames: Int, seed: Long = 1): FloatArray {
        val r = Random(seed)
        val s = FloatArray(frames)
        var t = 0
        while (t < frames) {
            val amp = 0.2f + r.nextFloat() * 0.6f
            for (i in 0 until minOf(6000, frames - t)) s[t + i] += amp * (r.nextFloat() * 2 - 1) * Math.exp(-i / 1500.0).toFloat()
            t += 4000 + r.nextInt(9000)
        }
        return s
    }

    private fun slice(s: FloatArray, start: Long, n: Int) = FloatArray(n) { i -> (start + i).let { if (it in s.indices) s[it.toInt()] else 0f } }

    @Test fun `finds the Mirror delay to the sample`() {
        val src = signal(20 * rate)
        for (delayMs in listOf(40.0, 3.0, 180.0, -10.0)) {
            val d = (delayMs * rate / 1000).toInt()
            // mirror sample i is the sound the reference had at i - d
            val got = TransitMeter.measure({ s, n -> slice(src, s - d, n) }, { s, n -> slice(src, s, n) }, (15 * rate).toLong(), rate)!!
            assertEquals(delayMs / 1000, got, 1.0 / rate)
        }
    }

    @Test fun `survives mastering-like level and noise`() {
        val src = signal(20 * rate, 7)
        val noise = Random(3)
        val d = (41.5 * rate / 1000).toInt()
        val got = TransitMeter.measure({ s, n -> slice(src, s - d, n).also { a -> for (i in a.indices) a[i] = a[i] * 0.3f + (noise.nextFloat() - 0.5f) * 0.01f } },
            { s, n -> slice(src, s, n) }, (15 * rate).toLong(), rate)!!
        assertEquals(0.0415, got, 1.0 / rate)
    }

    @Test fun `unrelated audio or silence measures nothing`() {
        val a = signal(20 * rate, 1); val b = signal(20 * rate, 99)
        assertNull(TransitMeter.measure({ s, n -> slice(a, s, n) }, { s, n -> slice(b, s, n) }, (15 * rate).toLong(), rate))
        assertNull(TransitMeter.measure({ _, n -> FloatArray(n) }, { s, n -> slice(a, s, n) }, (15 * rate).toLong(), rate))
        assertNull(TransitMeter.measure({ s, n -> slice(a, s, n) }, { s, n -> slice(a, s, n) }, (2 * rate).toLong(), rate))
    }

    @Test fun `the reference is the Move's own mix, never Live's`() {
        fun ch(p: String, n: String) = LinkChannel(n, p, n)
        assertEquals("Movy Master", StemRecorder.reference(listOf(ch("Live", "Main"), ch("Move", "Main"), ch("Movy", "Movy Master")))?.name)
        assertEquals("Move3", StemRecorder.reference(listOf(ch("Live", "Main"), ch("Move3", "Main")))?.peer)
        assertNull(StemRecorder.reference(listOf(ch("Live", "Main"), ch("Move", "1-MIDI"))))
    }
}
