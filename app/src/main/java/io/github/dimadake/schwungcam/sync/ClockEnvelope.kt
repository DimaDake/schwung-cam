package io.github.dimadake.schwungcam.sync

import kotlin.math.abs
import kotlin.math.floor

/** phoneSec = a + b·posSec. */
data class ClockFit(val a: Double, val b: Double) {
    fun phoneSec(posSec: Double) = a + b * posSec
    fun posSec(phoneSec: Double) = (phoneSec - a) / b
}

/**
 * Maps the Move's sample clock onto the phone clock from pcm arrival times.
 *
 * `d = arrival − newestSampleTime` is clock offset plus transit. Transit jitter
 * only ever ADDS delay, so the minimum per 1 s bucket traces offset + minimum
 * transit; anything averaged would carry the jitter into the sync.
 */
class ClockEnvelope(val rate: Int = 44100) {
    private class Pt(val x: Double, var d: Double)

    private val buckets = java.util.TreeMap<Long, Pt>()

    @Synchronized
    fun add(arrivalNs: Long, endPos: Long) {
        val x = endPos.toDouble() / rate
        val d = arrivalNs / 1e9 - x
        val key = floor(x).toLong()
        val p = buckets[key]
        if (p == null) buckets[key] = Pt(x, d) else if (d < p.d) { p.d = d; }
        // a take is minutes long; an hour of buckets is still only 3600 entries
        while (buckets.size > 4 * 3600) buckets.pollFirstEntry()
    }

    @Synchronized
    fun clear() = buckets.clear()

    /** Offset over the newest 10 s of buckets, or null before any audio. */
    @Synchronized
    fun liveOffset(): Double? {
        if (buckets.isEmpty()) return null
        val last = buckets.lastKey()
        return buckets.tailMap(last - 9).values.minOf { it.d }
    }

    fun livePhoneNs(endPos: Long): Long? =
        liveOffset()?.let { ((endPos.toDouble() / rate + it) * 1e9).toLong() }

    /** Bucket minima within [fromSec, toSec] of device time, as (posSec, d). */
    @Synchronized
    fun points(fromSec: Double, toSec: Double): List<Pair<Double, Double>> =
        buckets.subMap(floor(fromSec).toLong(), true, floor(toSec).toLong(), true)
            .values.map { it.x to it.d }

    fun fit(fromSec: Double, toSec: Double): ClockFit? = fitPoints(points(fromSec - 5, toSec + 5))

    companion object {
        const val OUTLIER_S = 0.002
        const val MIN_FIT_POINTS = 5

        fun fitPoints(pts: List<Pair<Double, Double>>): ClockFit? {
            if (pts.isEmpty()) return null
            if (pts.size < MIN_FIT_POINTS) return ClockFit(pts.minOf { it.second }, 1.0)
            var use = pts
            var line = regress(use)
            // a congested bucket sits well above the envelope; refit without it
            for (iter in 0 until 3) {
                val (c0, c1) = line
                val kept = use.filter { (x, d) -> d - (c0 + c1 * x) <= OUTLIER_S }
                if (kept.size == use.size || kept.size < MIN_FIT_POINTS) break
                use = kept
                line = regress(use)
            }
            // d = c0 + c1·x  ⇒  phone = x + d = c0 + (1 + c1)·x
            return ClockFit(line.first, 1.0 + line.second)
        }

        private fun regress(pts: List<Pair<Double, Double>>): Pair<Double, Double> {
            val mx = pts.sumOf { it.first } / pts.size
            val my = pts.sumOf { it.second } / pts.size
            var sxx = 0.0; var sxy = 0.0
            for ((x, y) in pts) { sxx += (x - mx) * (x - mx); sxy += (x - mx) * (y - my) }
            val slope = if (abs(sxx) < 1e-12) 0.0 else sxy / sxx
            return (my - slope * mx) to slope
        }
    }
}
