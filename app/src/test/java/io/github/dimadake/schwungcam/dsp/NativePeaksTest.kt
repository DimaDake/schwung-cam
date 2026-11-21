package io.github.dimadake.schwungcam.dsp

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** The NEON detector must find what the Kotlin one finds: run tools/build-probe.sh first, or this is skipped. */
class NativePeaksTest {
    @Test fun `native peaks match Kotlin peaks`() {
        assumeTrue("libschwungcam_dsp not built (tools/build-probe.sh)", NativePeaks.available)
        val fs = 44100; val n = 10 * fs
        val rnd = Random(7)
        // loud bursts (the filter runs), quiet stretches (it is skipped), and a full-scale square (inter-sample overs)
        val l = DoubleArray(n) { i -> when ((i / 11025) % 3) { 0 -> rnd.nextDouble(-1.0, 1.0); 1 -> 0.01 * sin(2 * PI * 440 * i / fs); else -> if ((i / 7) % 2 == 0) 0.99 else -0.99 } }
        val r = DoubleArray(n) { i -> 0.8 * l[(i + 13) % n] }
        val floor = 0.85
        val k = KotlinPeaks(); val nat = NativePeaks()
        val a = DoubleArray(Mastering.BLOCK); val b = DoubleArray(Mastering.BLOCK)
        val bl = DoubleArray(Mastering.BLOCK); val br = DoubleArray(Mastering.BLOCK)
        var at = 0; var worst = 0.0; var filtered = 0
        while (at < n) {
            val m = minOf(Mastering.BLOCK - (at % 3), n - at)  // odd block sizes too
            System.arraycopy(l, at, bl, 0, m); System.arraycopy(r, at, br, 0, m)
            k.process(bl, br, m, floor, a); nat.process(bl, br, m, floor, b)
            for (i in 0 until m) { worst = maxOf(worst, kotlin.math.abs(a[i] - b[i])); if (a[i] >= floor) filtered++ }
            at += m
        }
        nat.close()
        assertEquals("largest difference", 0.0, worst, 1e-12)
        assertTrue("the filter ran: $filtered", filtered > n / 10)
    }

    private fun assertTrue(msg: String, c: Boolean) = org.junit.Assert.assertTrue(msg, c)
}
