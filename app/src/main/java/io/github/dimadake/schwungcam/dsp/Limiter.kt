package io.github.dimadake.schwungcam.dsp

import kotlin.math.exp
import kotlin.math.pow

/**
 * Stereo-linked lookahead true-peak limiter.
 *
 * The audio is delayed by L. Each step's required gain r[n] = min(1, ceil/peak)
 * comes from [TruePeak], which reports peaks up to D samples late. The gain is
 * min over the last W = L+1 values of r, released, then averaged over A = L−D.
 * For the output sample p = n−L, every averaged min then covers every r[k] that
 * can describe p (k ∈ [p, p+D+1]), so the applied gain never exceeds what the
 * peak needs, and the average turns the jump into a ramp.
 */
class Limiter(fs: Int, ceilingDbtp: Double = -1.0, lookaheadMs: Double = 5.0, releaseMs: Double = 80.0) {
    // under the ceiling by the detector's worst measured under-read (0.15 dB) plus slack
    private val ceil = 10.0.pow((ceilingDbtp - 0.25) / 20)
    private val la = (fs * lookaheadMs / 1000).toInt()
    private val avgLen = la - TruePeak.DELAY
    private val winLen = la + 1
    private val rel = 1 - exp(-1.0 / (fs * releaseMs / 1000))

    private val tpL = TruePeak(); private val tpR = TruePeak()
    private val dl = DoubleArray(la); private val dr = DoubleArray(la)
    private var dIdx = 0

    // monotonic deque of (step, r) for the sliding minimum
    private val qStep = LongArray(winLen + 1); private val qVal = DoubleArray(winLen + 1)
    private var qHead = 0; private var qSize = 0
    private var step = 0L

    private var released = 1.0
    private val avgRing = DoubleArray(avgLen) { 1.0 }
    private var avgIdx = 0
    private var avgSum = avgLen.toDouble()

    init { require(avgLen > 0) { "lookahead too short" } }

    /** Latency in samples: output n is input n − [latency]. */
    val latency: Int get() = la

    fun process(inL: Double, inR: Double, out: DoubleArray) {
        val peak = maxOf(tpL.process(inL, ceil), tpR.process(inR, ceil))
        val r = if (peak > ceil) ceil / peak else 1.0

        while (qSize > 0 && qVal[(qHead + qSize - 1) % qVal.size] >= r) qSize--
        qStep[(qHead + qSize) % qStep.size] = step; qVal[(qHead + qSize) % qVal.size] = r; qSize++
        while (qStep[qHead] <= step - winLen) { qHead = (qHead + 1) % qStep.size; qSize-- }
        val m = qVal[qHead]
        step++

        released = minOf(m, released + (1 - released) * rel)
        avgSum += released - avgRing[avgIdx]
        avgRing[avgIdx] = released
        avgIdx = (avgIdx + 1) % avgLen
        val g = avgSum / avgLen

        out[0] = dl[dIdx] * g; out[1] = dr[dIdx] * g
        dl[dIdx] = inL; dr[dIdx] = inR
        dIdx = (dIdx + 1) % dl.size
    }
}
