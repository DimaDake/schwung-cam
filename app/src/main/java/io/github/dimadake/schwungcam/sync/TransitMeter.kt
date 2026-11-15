package io.github.dimadake.schwungcam.sync

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The Mirror stream's own delay. [ClockEnvelope] traces clock offset PLUS the
 * minimum transit, so the take's sound sat that much late against the picture
 * (~40 ms on the owner's Wi-Fi, 2026-11-15). A Link Audio channel carrying the
 * same mix is stamped with when the sound left the Move, so where the Mirror
 * audio lines up with it IS the transit.
 *
 * Both signals are mono floats at the same rate, indexed so that a delay of 0
 * means Mirror sample i sits at reference sample i.
 */
object TransitMeter {
    const val MIN_S = -0.05
    const val MAX_S = 0.25
    const val WINDOW_S = 3.0
    const val MIN_CORR = 0.6
    const val AGREE_S = 0.002
    private const val DECIMATE = 22

    /**
     * Seconds the Mirror audio runs late, from up to [windows] windows spread
     * over [mirror]; null unless at least two windows correlate and agree.
     * [ref] covers the same span from [MAX_S] earlier to [-MIN_S] later.
     */
    fun measure(mirror: (start: Long, n: Int) -> FloatArray, ref: (start: Long, n: Int) -> FloatArray,
                frames: Long, rate: Int, windows: Int = 5): Double? {
        val win = (WINDOW_S * rate).toInt()
        if (frames < win + rate) return null
        val found = ArrayList<Double>()
        for (w in 0 until windows) {
            val start = ((frames - win) * (w + 0.5) / windows).toLong()
            lag(mirror(start, win), ref, start, rate)?.let { found.add(it) }
        }
        if (found.size < 2) return null
        found.sort()
        val med = found[found.size / 2]
        return if (found.count { abs(it - med) <= AGREE_S } >= 2) med else null
    }

    /** One window: coarse on a decimated envelope, then exact at full rate. */
    private fun lag(x: FloatArray, ref: (Long, Int) -> FloatArray, start: Long, rate: Int): Double? {
        val lo = (MIN_S * rate).roundToInt()
        val hi = (MAX_S * rate).roundToInt()
        // the reference sample that matches mirror sample start is start - lag
        val y = ref(start - hi, x.size + hi - lo)
        if (energy(x, 0, x.size) < 1e-6) return null
        val xd = envelope(x); val yd = envelope(y)
        var best = 0; var bestC = Double.NEGATIVE_INFINITY
        for (l in lo / DECIMATE..hi / DECIMATE) {
            val off = (hi / DECIMATE) - l
            val c = dot(xd, yd, off)
            if (c > bestC) { bestC = c; best = l * DECIMATE }
        }
        var fine = best; var fineC = Double.NEGATIVE_INFINITY
        for (l in maxOf(lo, best - 2 * DECIMATE)..minOf(hi, best + 2 * DECIMATE)) {
            val c = dot(x, y, hi - l)
            if (c > fineC) { fineC = c; fine = l }
        }
        val norm = fineC / sqrt(energy(x, 0, x.size) * energy(y, hi - fine, x.size) + 1e-12)
        return if (norm >= MIN_CORR) fine.toDouble() / rate else null
    }

    private fun envelope(s: FloatArray): FloatArray {
        val out = FloatArray(s.size / DECIMATE)
        for (i in out.indices) { var a = 0f; for (k in 0 until DECIMATE) a += abs(s[i * DECIMATE + k]); out[i] = a }
        // remove the mean, or the loudest overlap wins rather than the right one
        val m = out.average().toFloat()
        for (i in out.indices) out[i] -= m
        return out
    }

    private fun dot(x: FloatArray, y: FloatArray, off: Int): Double {
        var s = 0.0
        val n = minOf(x.size, y.size - off)
        for (i in 0 until n) s += x[i] * y[i + off]
        return s
    }

    private fun energy(x: FloatArray, off: Int, n: Int): Double {
        var s = 0.0
        for (i in off until minOf(x.size, off + n)) s += x[i] * x[i]
        return s
    }

    /** Mono frames [start, start+n) of a 16-bit stereo WAV with a 44-byte header; silence outside it. */
    fun readMono(file: File, start: Long, n: Int): FloatArray {
        val out = FloatArray(n)
        val have = (file.length() - 44) / 4
        val a = maxOf(0L, start); val b = minOf(have, start + n)
        if (b <= a) return out
        val bytes = ByteArray(((b - a) * 4).toInt())
        RandomAccessFile(file, "r").use { it.seek(44 + a * 4); it.readFully(bytes) }
        for (i in 0 until (b - a).toInt()) {
            val o = i * 4
            val l = ((bytes[o].toInt() and 0xFF) or (bytes[o + 1].toInt() shl 8)).toShort()
            val r = ((bytes[o + 2].toInt() and 0xFF) or (bytes[o + 3].toInt() shl 8)).toShort()
            out[(a - start).toInt() + i] = (l + r) / 65536f
        }
        return out
    }
}
