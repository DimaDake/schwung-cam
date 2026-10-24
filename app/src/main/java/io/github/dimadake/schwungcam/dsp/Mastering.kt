package io.github.dimadake.schwungcam.dsp

import java.io.Closeable
import kotlin.math.pow

data class MasterSettings(
    val enabled: Boolean = true,
    val glue: Boolean = true,
    val targetLufs: Double = -14.0,
    val ceilingDbtp: Double = -1.0,
)

data class MasterReport(val inputLufs: Double, val outputLufs: Double, val gainDb: Double, val passes: Int)

/** Sequential stereo float frames; 0 at the end. */
interface FrameReader : Closeable {
    fun read(l: FloatArray, r: FloatArray, max: Int): Int
}

fun interface FrameSink {
    fun write(l: FloatArray, r: FloatArray, n: Int)
}

/**
 * The post-take mastering chain: DC/20 Hz high-pass → glue → gain → 20 kHz
 * low-pass → true-peak limiter, with the gain found by measuring. Every pass streams from [open],
 * so memory stays flat however long the take is.
 */
object Mastering {
    const val BLOCK = 4096
    /** A near-silent take would otherwise have its noise floor raised to −14 LUFS. */
    const val MAX_GAIN_DB = 24.0
    const val TOLERANCE_LU = 0.5
    const val MAX_LIMIT_PASSES = 3
    const val GLUE_ABOVE_LUFS = 4.0

    fun run(
        fs: Int,
        s: MasterSettings,
        open: () -> FrameReader,
        sink: FrameSink,
        progress: (Float) -> Unit = {},
    ): MasterReport {
        if (!s.enabled) {
            pass(fs, open, null) { l, r, n -> sink.write(l, r, n) }
            return MasterReport(Double.NaN, Double.NaN, 0.0, 1)
        }
        val totalPasses = 3 + MAX_LIMIT_PASSES
        var done = 0
        fun tick() { done++; progress(done.toFloat() / totalPasses) }

        val pre = measure(fs, open, Chain(fs, null, 1.0, null)); tick()
        if (pre == Double.NEGATIVE_INFINITY) {
            pass(fs, open, Chain(fs, null, 1.0, null)) { l, r, n -> sink.write(l, r, n) }
            progress(1f)
            return MasterReport(pre, pre, 0.0, 2)
        }
        val thr = pre + GLUE_ABOVE_LUFS
        fun glue() = if (s.glue) Compressor(fs, thr) else null
        val glued = if (s.glue) measure(fs, open, Chain(fs, glue(), 1.0, null)).also { tick() } else pre

        var gainDb = (s.targetLufs - glued).coerceAtMost(MAX_GAIN_DB)
        var out = Double.NEGATIVE_INFINITY
        var passes = 0
        for (i in 0 until MAX_LIMIT_PASSES) {
            out = measure(fs, open, Chain(fs, glue(), db(gainDb), Limiter(fs, s.ceilingDbtp))); tick()
            passes++
            // the limiter only ever takes loudness away, so only an undershoot is retried
            val short = s.targetLufs - out
            if (short <= TOLERANCE_LU || gainDb >= MAX_GAIN_DB) break
            gainDb = (gainDb + short).coerceAtMost(MAX_GAIN_DB)
        }
        val chain = Chain(fs, glue(), db(gainDb), Limiter(fs, s.ceilingDbtp))
        pass(fs, open, chain) { l, r, n -> sink.write(l, r, n) }
        progress(1f)
        return MasterReport(pre, out, gainDb, passes)
    }

    private fun db(x: Double) = 10.0.pow(x / 20)

    private fun measure(fs: Int, open: () -> FrameReader, chain: Chain): Double {
        val m = LoudnessMeter(fs)
        pass(fs, open, chain) { l, r, n -> m.add(l, r, n) }
        return m.integrated()
    }

    /** Streams [open] through [chain] (null = untouched), keeping length and alignment. */
    private fun pass(fs: Int, open: () -> FrameReader, chain: Chain?, out: FrameSink) {
        val l = FloatArray(BLOCK); val r = FloatArray(BLOCK)
        val ol = FloatArray(BLOCK); val or = FloatArray(BLOCK)
        var toDrop = chain?.latency ?: 0
        open().use { rd ->
            while (true) {
                val n = rd.read(l, r, BLOCK)
                if (n <= 0) break
                if (chain == null) { out.write(l, r, n); continue }
                val k = chain.process(l, r, n, ol, or, toDrop)
                toDrop = maxOf(0, toDrop - n)
                if (k > 0) out.write(ol, or, k)
            }
        }
        if (chain != null && chain.latency > 0) {
            // flush the limiter's lookahead with silence
            var left = chain.latency
            java.util.Arrays.fill(l, 0f); java.util.Arrays.fill(r, 0f)
            while (left > 0) {
                val n = minOf(left, BLOCK)
                val k = chain.process(l, r, n, ol, or, toDrop)
                toDrop = maxOf(0, toDrop - n)
                if (k > 0) out.write(ol, or, k)
                left -= n
            }
        }
    }

    private class Chain(fs: Int, val comp: Compressor?, val gain: Double, val lim: Limiter?) {
        private val hpL = Biquad.butterHighpass(fs.toDouble(), 20.0)
        private val hpR = hpL.copy()
        // 4th-order Butterworth at 20 kHz: inaudible, AAC drops it anyway, and it
        // keeps the content inside the band the true-peak detector reads accurately
        private val lpL = arrayOf(Biquad.butterLowpass(fs.toDouble(), 20000.0, 0.5412), Biquad.butterLowpass(fs.toDouble(), 20000.0, 1.3066))
        private val lpR = arrayOf(lpL[0].copy(), lpL[1].copy())
        private val o = DoubleArray(2)
        val latency get() = lim?.latency ?: 0

        /** Processes n frames; the first [drop] outputs are latency and are skipped. Returns frames written. */
        fun process(l: FloatArray, r: FloatArray, n: Int, ol: FloatArray, or: FloatArray, drop: Int): Int {
            var w = 0
            for (i in 0 until n) {
                var a = hpL.process(l[i].toDouble())
                var b = hpR.process(r[i].toDouble())
                if (comp != null) { val g = comp.gain(a, b); a *= g; b *= g }
                a *= gain; b *= gain
                if (lim != null) {
                    a = lpL[1].process(lpL[0].process(a)); b = lpR[1].process(lpR[0].process(b))
                    lim.process(a, b, o); a = o[0]; b = o[1]
                }
                if (i >= drop) { ol[w] = a.toFloat(); or[w] = b.toFloat(); w++ }
            }
            return w
        }
    }
}
