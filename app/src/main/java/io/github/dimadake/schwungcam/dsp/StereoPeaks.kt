package io.github.dimadake.schwungcam.dsp

/**
 * [TruePeak] for both channels over a block: out[i] is the larger channel's
 * [TruePeak.process] of sample i. The limiter's input does not depend on its
 * output, so its peaks can be found a block ahead.
 */
interface StereoPeaks : AutoCloseable {
    fun process(l: DoubleArray, r: DoubleArray, n: Int, floor: Double, out: DoubleArray)
    override fun close() {}

    companion object {
        /** The NEON one when its library loads (the phone; a JVM test that was given it), else Kotlin. */
        fun create(): StereoPeaks = if (NativePeaks.available) NativePeaks() else KotlinPeaks()
    }
}

class KotlinPeaks : StereoPeaks {
    private val tpL = TruePeak(); private val tpR = TruePeak()

    override fun process(l: DoubleArray, r: DoubleArray, n: Int, floor: Double, out: DoubleArray) {
        for (i in 0 until n) out[i] = maxOf(tpL.process(l[i], floor), tpR.process(r[i], floor))
    }
}

/** app/src/main/cpp/truepeak_core.cpp: the same filter, eight phases at a time. */
class NativePeaks : StereoPeaks {
    private var h = nativeNew(TruePeak.banks, TruePeak.MAX_ABS_SUM)

    override fun process(l: DoubleArray, r: DoubleArray, n: Int, floor: Double, out: DoubleArray) {
        check(h != 0L)
        require(n <= l.size && n <= r.size && n <= out.size)
        nativeProcess(h, l, r, n, floor, out)
    }

    override fun close() {
        if (h != 0L) { nativeFree(h); h = 0L }
    }

    companion object {
        /** A JVM test points [LIB_PROPERTY] at the Mac build (tools/build-probe.sh). */
        const val LIB_PROPERTY = "schwungcam.dsplib"

        val available: Boolean = try {
            val p = System.getProperty(LIB_PROPERTY)
            if (p != null) System.load(p) else System.loadLibrary("schwungcam_dsp")
            true
        } catch (e: Throwable) {
            false
        }

        @JvmStatic private external fun nativeNew(coef: DoubleArray, maxAbsSum: Double): Long
        @JvmStatic private external fun nativeProcess(h: Long, l: DoubleArray, r: DoubleArray, n: Int, floor: Double, out: DoubleArray)
        @JvmStatic private external fun nativeFree(h: Long)
    }
}
