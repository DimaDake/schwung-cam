package io.github.dimadake.schwungcam.dsp

import kotlin.math.log10

/**
 * ITU-R BS.1770-4 integrated loudness of a stereo stream. Mean squares are
 * kept per 100 ms step, so a ten-minute take costs 6000 doubles.
 */
class LoudnessMeter(private val fs: Int) {
    private val kl1 = Biquad.kShelf(fs.toDouble()); private val kl2 = Biquad.kHighpass(fs.toDouble())
    private val kr1 = kl1.copy(); private val kr2 = kl2.copy()
    private val step = fs / 10
    private var acc = 0.0
    private var count = 0
    private val steps = ArrayList<Double>()

    fun add(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val a = kl2.process(kl1.process(l[i].toDouble()))
            val b = kr2.process(kr1.process(r[i].toDouble()))
            acc += a * a + b * b
            if (++count == step) { steps.add(acc / step); acc = 0.0; count = 0 }
        }
    }

    /** LUFS, or [Double.NEGATIVE_INFINITY] for silence or under 400 ms. */
    fun integrated(): Double {
        if (steps.size < 4) return Double.NEGATIVE_INFINITY
        val blocks = DoubleArray(steps.size - 3) { j -> (steps[j] + steps[j + 1] + steps[j + 2] + steps[j + 3]) / 4 }
        val abs = blocks.filter { lufs(it) > -70.0 }
        if (abs.isEmpty()) return Double.NEGATIVE_INFINITY
        val rel = lufs(abs.average()) - 10.0
        val gated = abs.filter { lufs(it) > rel }
        return if (gated.isEmpty()) Double.NEGATIVE_INFINITY else lufs(gated.average())
    }

    private fun lufs(ms: Double) = if (ms <= 0) Double.NEGATIVE_INFINITY else -0.691 + 10 * log10(ms)
}
