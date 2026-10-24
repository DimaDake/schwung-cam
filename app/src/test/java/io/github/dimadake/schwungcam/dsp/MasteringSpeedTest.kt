package io.github.dimadake.schwungcam.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Guards the "seconds per minute of audio" promise against a slow DSP change. */
class MasteringSpeedTest {
    @Test fun `a minute of audio masters in seconds`() {
        val fs = 44100; val n = 60 * fs
        val rnd = Random(3)
        val l = FloatArray(n) { i -> (0.3 * sin(2 * PI * 110 * i / fs) + (if (i % 22050 < 3000) rnd.nextDouble(-0.5, 0.5) else 0.0)).toFloat() }
        val r = l.copyOf()
        val t0 = System.nanoTime()
        val rep = Mastering.run(fs, MasterSettings(), {
            object : FrameReader {
                var at = 0
                override fun read(l2: FloatArray, r2: FloatArray, max: Int): Int {
                    val k = minOf(max, n - at); System.arraycopy(l, at, l2, 0, k); System.arraycopy(r, at, r2, 0, k); at += k; return k
                }
                override fun close() {}
            }
        }, { _, _, _ -> })
        val s = (System.nanoTime() - t0) / 1e9
        println("MASTERING 60 s of audio in %.2f s, %d limiter passes".format(s, rep.passes))
        assertTrue("took $s s", s < 15)
    }
}
