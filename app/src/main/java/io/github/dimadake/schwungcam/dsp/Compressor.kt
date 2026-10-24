package io.github.dimadake.schwungcam.dsp

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/** Stereo-linked RMS glue compressor with a soft knee; no make-up gain. */
class Compressor(
    fs: Int,
    private val thresholdDb: Double,
    private val ratio: Double = 2.0,
    private val kneeDb: Double = 6.0,
    attackMs: Double = 30.0,
    releaseMs: Double = 250.0,
    rmsMs: Double = 10.0,
) {
    private val rmsA = exp(-1.0 / (fs * rmsMs / 1000))
    private val atk = exp(-1.0 / (fs * attackMs / 1000))
    private val relC = exp(-1.0 / (fs * releaseMs / 1000))
    private var ms = 0.0
    private var grDb = 0.0

    /** Gain (linear) for this frame. */
    fun gain(l: Double, r: Double): Double {
        ms = rmsA * ms + (1 - rmsA) * (l * l + r * r) / 2
        val level = if (ms <= 1e-20) -200.0 else 10 * log10(ms)
        val over = level - thresholdDb
        val slope = 1 / ratio - 1
        val target = when {
            over <= -kneeDb / 2 -> 0.0
            over < kneeDb / 2 -> slope * (over + kneeDb / 2).pow(2) / (2 * kneeDb)
            else -> slope * over
        }
        val c = if (target < grDb) atk else relC
        grDb = c * grDb + (1 - c) * target
        return 10.0.pow(grDb / 20)
    }
}
