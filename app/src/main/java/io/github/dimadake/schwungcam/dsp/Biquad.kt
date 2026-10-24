package io.github.dimadake.schwungcam.dsp

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/** Normalised biquad (a0 = 1), transposed direct form II, double state. */
class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun copy() = Biquad(b0, b1, b2, a1, a2)

    companion object {
        /**
         * ITU-R BS.1770 K-weighting, stage 1 (head shelf), derived for any
         * rate from the analog prototype the 48 kHz table was made from.
         */
        fun kShelf(fs: Double): Biquad {
            val g = 3.999843853973347; val f0 = 1681.974450955533; val q = 0.7071752369554196
            val k = tan(PI * f0 / fs)
            val vh = 10.0.pow(g / 20); val vb = vh.pow(0.4996667741545416)
            val a0 = 1 + k / q + k * k
            return Biquad(
                (vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
                2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0,
            )
        }

        /** K-weighting stage 2 (RLB high-pass). */
        fun kHighpass(fs: Double): Biquad {
            val f0 = 38.13547087602444; val q = 0.5003270373238773
            val k = tan(PI * f0 / fs)
            val a0 = 1 + k / q + k * k
            return Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }

        /** One section of a Butterworth low-pass; cascade with the order's section Qs. */
        fun butterLowpass(fs: Double, f0: Double, q: Double = 1 / sqrt(2.0)): Biquad {
            val k = tan(PI * f0 / fs)
            val a0 = 1 + k / q + k * k
            return Biquad(k * k / a0, 2 * k * k / a0, k * k / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }

        fun butterHighpass(fs: Double, f0: Double): Biquad {
            val q = 1 / sqrt(2.0)
            val k = tan(PI * f0 / fs)
            val a0 = 1 + k / q + k * k
            return Biquad(1 / a0, -2 / a0, 1 / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }
    }
}
