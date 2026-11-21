package io.github.dimadake.schwungcam.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 8× oversampled peak detector with a 32-tap-per-phase Kaiser interpolator.
 * A BS.1770-length (12-tap) filter rolls off below Nyquist and read full-band
 * noise peaks 3 dB low against a near-ideal interpolator. This one is flat to
 * about 20 kHz, which is why the mastering chain low-passes at 20 kHz first;
 * what remains (≈0.15 dB) is the limiter's margin.
 *
 * [process] returns the largest |value| among the input sample and the
 * interpolated points, which all lie within the last [DELAY] + 1 input
 * samples, or, when the recent samples cannot interpolate above [floor], a
 * bound that is below [floor].
 */
class TruePeak {
    // each sample is written twice, so the newest TAPS_PER_PHASE always sit
    // contiguous at [idx, idx + TAPS_PER_PHASE), oldest first
    private val hist = DoubleArray(2 * TAPS_PER_PHASE)
    private var idx = 0

    fun process(x: Double, floor: Double = 0.0): Double {
        hist[idx] = x
        hist[idx + TAPS_PER_PHASE] = x
        idx = if (idx == TAPS_PER_PHASE - 1) 0 else idx + 1
        var histMax = 0.0
        for (t in idx until idx + TAPS_PER_PHASE) { val a = abs(hist[t]); if (a > histMax) histMax = a }
        // no interpolated point exceeds histMax·Σ|c|: skip the filter when that is safe
        val bound = histMax * MAX_ABS_SUM
        if (bound < floor) return bound
        var peak = abs(x)
        // the filter is even-length, so no phase lands on an input sample:
        // all of them are interpolated points between x[n-16] and x[n-15]
        val h = hist; val base = idx
        for (p in 0 until PHASES) {
            val c = REV[p]
            var acc = 0.0
            for (t in 0 until TAPS_PER_PHASE) acc += c[t] * h[base + t]
            if (abs(acc) > peak) peak = abs(acc)
        }
        return peak
    }

    companion object {
        const val PHASES = 8
        const val TAPS_PER_PHASE = 32
        /** Input samples between a peak and the step it is reported at, at most. */
        const val DELAY = TAPS_PER_PHASE / 2 + 1

        /** Windowed-sinc lowpass at the original Nyquist, split into polyphase banks. */
        private val COEF: Array<DoubleArray> = run {
            val n = PHASES * TAPS_PER_PHASE
            val mid = (n - 1) / 2.0
            val h = DoubleArray(n) { i ->
                val t = (i - mid) / PHASES
                val sinc = if (abs(t) < 1e-12) 1.0 else sin(PI * t) / (PI * t)
                val u = 2.0 * i / (n - 1) - 1
                sinc * besselI0(KAISER_BETA * kotlin.math.sqrt(1 - u * u)) / besselI0(KAISER_BETA)
            }
            Array(PHASES) { p ->
                val bank = DoubleArray(TAPS_PER_PHASE) { t -> h[t * PHASES + p] }
                // each phase must pass DC at unity, or a constant would "peak" above itself
                val s = bank.sum(); for (t in bank.indices) bank[t] /= s
                bank
            }
        }

        /** [COEF] with each bank reversed, so the newest sample meets tap 0 at the end of a forward walk. */
        private val REV: Array<DoubleArray> = Array(PHASES) { p -> COEF[p].reversedArray() }

        /** [REV] flat, bank after bank: what [NativePeaks] is built from, so both filters are one. */
        internal val banks: DoubleArray get() = DoubleArray(PHASES * TAPS_PER_PHASE) { REV[it / TAPS_PER_PHASE][it % TAPS_PER_PHASE] }

        private const val KAISER_BETA = 8.0

        private fun besselI0(x: Double): Double {
            var sum = 1.0; var term = 1.0; var k = 1
            while (term > 1e-12 * sum) { term *= (x / (2 * k)) * (x / (2 * k)); sum += term; k++ }
            return sum
        }

        internal val MAX_ABS_SUM: Double = maxOf(1.0, COEF.maxOf { b -> b.sumOf { abs(it) } })
    }
}
