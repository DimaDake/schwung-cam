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
 * One attempt at the master. Each limiter pass writes to a new one, since
 * only once the pass is over is it known to be on target: then it is kept,
 * else dropped and the pass run again louder.
 */
interface MasterSink : FrameSink {
    fun keep() {}
    fun drop() {}
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
    /** Of the bar, each loudness pass without the limiter: they cost a fraction of a limiter pass. */
    private const val MEASURE_SHARE = 0.1f

    fun run(
        fs: Int,
        s: MasterSettings,
        open: () -> FrameReader,
        sink: () -> MasterSink,
        /** Frames [open] yields, if known: progress then also moves within a pass, not only between them. */
        frames: Long = 0,
        progress: (Float) -> Unit = {},
    ): MasterReport {
        if (!s.enabled) {
            write(fs, open, null, frames, progress, sink)
            return MasterReport(Double.NaN, Double.NaN, 0.0, 1)
        }
        // how much of the bar is done, and the span the next pass may fill
        var base = 0f
        fun span(w: Float): (Float) -> Unit { val b = base; base += w; return { f -> progress(b + f * w) } }
        fun measure(chain: Chain, w: Float) = measure(fs, open, chain, frames, span(w))

        val pre = measure(Chain(fs, null, 1.0, null), MEASURE_SHARE)
        if (pre == Double.NEGATIVE_INFINITY) {
            write(fs, open, Chain(fs, null, 1.0, null), frames, span(1f - base), sink)
            progress(1f)
            return MasterReport(pre, pre, 0.0, 2)
        }
        val thr = pre + GLUE_ABOVE_LUFS
        fun glue() = if (s.glue) Compressor(fs, thr) else null
        val glued = if (s.glue) measure(Chain(fs, glue(), 1.0, null), MEASURE_SHARE) else pre

        var gainDb = (s.targetLufs - glued).coerceAtMost(MAX_GAIN_DB)
        var out = Double.NEGATIVE_INFINITY
        var passes = 0
        for (i in 0 until MAX_LIMIT_PASSES) {
            val m = LoudnessMeter(fs)
            val o = sink()
            // most of what is left, as if this pass were the last, which it nearly always is
            try {
                pass(fs, open, Chain(fs, glue(), db(gainDb), Limiter(fs, s.ceilingDbtp)), frames, span((1f - base) * 0.95f)) { l, r, n ->
                    m.add(l, r, n); o.write(l, r, n)
                }
            } catch (e: Throwable) {
                o.drop(); throw e
            }
            out = m.integrated()
            passes++
            // the limiter only ever takes loudness away, so only an undershoot is retried
            val short = s.targetLufs - out
            if (short <= TOLERANCE_LU || gainDb >= MAX_GAIN_DB) {
                o.keep()
                progress(1f)
                return MasterReport(pre, out, gainDb, passes)
            }
            o.drop()
            gainDb = (gainDb + short).coerceAtMost(MAX_GAIN_DB)
        }
        // still short after every measured pass: one more at the corrected gain, unmeasured
        write(fs, open, Chain(fs, glue(), db(gainDb), Limiter(fs, s.ceilingDbtp)), frames, span(1f - base), sink)
        progress(1f)
        return MasterReport(pre, out, gainDb, passes)
    }

    /** One pass into a new attempt that is kept. */
    private fun write(fs: Int, open: () -> FrameReader, chain: Chain?, frames: Long, within: (Float) -> Unit, sink: () -> MasterSink) {
        val o = sink()
        try {
            pass(fs, open, chain, frames, within) { l, r, n -> o.write(l, r, n) }
        } catch (e: Throwable) {
            o.drop(); throw e
        }
        o.keep()
    }

    private fun db(x: Double) = 10.0.pow(x / 20)

    private fun measure(fs: Int, open: () -> FrameReader, chain: Chain, frames: Long, within: (Float) -> Unit): Double {
        val m = LoudnessMeter(fs)
        pass(fs, open, chain, frames, within) { l, r, n -> m.add(l, r, n) }
        return m.integrated()
    }

    /** Streams [open] through [chain] (null = untouched), keeping length and alignment; closes [chain]. */
    private fun pass(fs: Int, open: () -> FrameReader, chain: Chain?, frames: Long, within: (Float) -> Unit, out: FrameSink) {
        try { stream(fs, open, chain, frames, within, out) } finally { chain?.close() }
    }

    private fun stream(fs: Int, open: () -> FrameReader, chain: Chain?, frames: Long, within: (Float) -> Unit, out: FrameSink) {
        val l = FloatArray(BLOCK); val r = FloatArray(BLOCK)
        val ol = FloatArray(BLOCK); val or = FloatArray(BLOCK)
        var toDrop = chain?.latency ?: 0
        var read = 0L; var told = 0L
        open().use { rd ->
            while (true) {
                val n = rd.read(l, r, BLOCK)
                if (n <= 0) break
                read += n
                // about every 10 s of audio: often enough to watch, rare enough to be free
                if (frames > 0 && read - told >= 10L * fs) { told = read; within(minOf(1f, read.toFloat() / frames)) }
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

    private class Chain(fs: Int, val comp: Compressor?, val gain: Double, val lim: Limiter?) : AutoCloseable {
        private val hpL = Biquad.butterHighpass(fs.toDouble(), 20.0)
        private val hpR = hpL.copy()
        // 4th-order Butterworth at 20 kHz: inaudible, AAC drops it anyway, and it
        // keeps the content inside the band the true-peak detector reads accurately
        private val lpL = arrayOf(Biquad.butterLowpass(fs.toDouble(), 20000.0, 0.5412), Biquad.butterLowpass(fs.toDouble(), 20000.0, 1.3066))
        private val lpR = arrayOf(lpL[0].copy(), lpL[1].copy())
        private val peaks = if (lim != null) StereoPeaks.create() else null
        private val a = DoubleArray(BLOCK); private val b = DoubleArray(BLOCK); private val pk = DoubleArray(BLOCK)
        private val o = DoubleArray(2)
        val latency get() = lim?.latency ?: 0

        /** Processes n ≤ [BLOCK] frames; the first [drop] outputs are latency and are skipped. Returns frames written. */
        fun process(l: FloatArray, r: FloatArray, n: Int, ol: FloatArray, or: FloatArray, drop: Int): Int {
            for (i in 0 until n) {
                var x = hpL.process(l[i].toDouble())
                var y = hpR.process(r[i].toDouble())
                if (comp != null) { val g = comp.gain(x, y); x *= g; y *= g }
                x *= gain; y *= gain
                if (lim != null) { x = lpL[1].process(lpL[0].process(x)); y = lpR[1].process(lpR[0].process(y)) }
                a[i] = x; b[i] = y
            }
            // the limiter's input does not hang on its output: its peaks are found for the whole block first
            if (lim != null) peaks!!.process(a, b, n, lim.ceil, pk)
            var w = 0
            for (i in 0 until n) {
                var x = a[i]; var y = b[i]
                if (lim != null) { lim.process(x, y, pk[i], o); x = o[0]; y = o[1] }
                if (i >= drop) { ol[w] = x.toFloat(); or[w] = y.toFloat(); w++ }
            }
            return w
        }

        override fun close() { peaks?.close() }
    }
}
