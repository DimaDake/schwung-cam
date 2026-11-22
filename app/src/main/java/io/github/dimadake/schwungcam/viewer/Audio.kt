package io.github.dimadake.schwungcam.viewer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/** A waveform: [min]/[max] per bucket of the mono sum, −127…127, [perSec] buckets a second. */
class Peaks(val perSec: Int, val min: ByteArray, val max: ByteArray) {
    val size get() = min.size

    fun write(out: OutputStream) = DataOutputStream(out).run {
        writeInt(MAGIC); writeInt(perSec); writeInt(size); write(min); write(max); flush()
    }

    /** Adds samples one at a time; mono, −1…1. */
    class Builder(rate: Int, val perSec: Int = PER_SEC) {
        private val frames = maxOf(1, rate / perSec)
        private var n = 0
        private var lo = 0f
        private var hi = 0f
        private val min = java.io.ByteArrayOutputStream()
        private val max = java.io.ByteArrayOutputStream()

        fun add(v: Float) {
            if (n == 0) { lo = v; hi = v } else { if (v < lo) lo = v; if (v > hi) hi = v }
            if (++n == frames) flush()
        }

        private fun flush() {
            min.write(q(lo)); max.write(q(hi)); n = 0
        }

        fun build(): Peaks {
            if (n > 0) flush()
            return Peaks(perSec, min.toByteArray(), max.toByteArray())
        }

        private fun q(v: Float) = (v.coerceIn(-1f, 1f) * 127).roundToInt()
    }

    companion object {
        const val PER_SEC = 50
        private const val MAGIC = 0x5045414B // PEAK

        fun read(input: InputStream): Peaks? = runCatching {
            DataInputStream(input).run {
                if (readInt() != MAGIC) return null
                val per = readInt(); val n = readInt()
                val a = ByteArray(n); val b = ByteArray(n)
                readFully(a); readFully(b)
                Peaks(per, a, b)
            }
        }.getOrNull()
    }
}

/** A PCM WAV's layout: where the samples start and how they are stored. */
class WavInfo(val rate: Int, val channels: Int, val bits: Int, val float: Boolean, val dataOffset: Long, val dataBytes: Long) {
    val frameBytes get() = channels * bits / 8
    val frames get() = dataBytes / frameBytes

    /**
     * [bytes] (whole frames) → stereo float into [out] at [at]: mono is doubled,
     * channels past two are dropped. Returns the frames converted.
     */
    fun toStereo(bytes: ByteArray, len: Int, out: FloatArray, at: Int = 0): Int {
        val nf = len / frameBytes
        val bps = bits / 8
        for (f in 0 until nf) {
            val base = f * frameBytes
            val l = sample(bytes, base, bps)
            val r = if (channels > 1) sample(bytes, base + bps, bps) else l
            out[at + 2 * f] = l; out[at + 2 * f + 1] = r
        }
        return nf
    }

    private fun sample(b: ByteArray, o: Int, bps: Int): Float = when {
        float && bps == 4 -> java.lang.Float.intBitsToFloat((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or (b[o + 3].toInt() shl 24))
        bps == 2 -> ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)) / 32768f
        bps == 3 -> ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() shl 16)) / 8388608f
        bps == 4 -> ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
            (b[o + 3].toInt() shl 24)) / 2147483648f
        else -> 0f
    }

    companion object {
        /** From the first bytes of the file (a few KB is plenty); null if it is not a PCM WAV. */
        fun parse(h: ByteArray, fileSize: Long = Long.MAX_VALUE): WavInfo? {
            fun s(o: Int) = String(h, o, 4, Charsets.US_ASCII)
            fun u16(o: Int) = (h[o].toInt() and 0xFF) or ((h[o + 1].toInt() and 0xFF) shl 8)
            fun u32(o: Int) = (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)
            if (h.size < 12 || s(0) != "RIFF" || s(8) != "WAVE") return null
            var o = 12
            var fmt: IntArray? = null
            while (o + 8 <= h.size) {
                val id = s(o); val len = u32(o + 4)
                if (id == "fmt ") {
                    if (o + 24 > h.size) return null
                    var tag = u16(o + 8)
                    if (tag == 0xFFFE && o + 34 <= h.size) tag = u16(o + 32) // extensible: the sub-format GUID's first two bytes
                    fmt = intArrayOf(tag, u16(o + 10), u32(o + 12).toInt(), u16(o + 22))
                } else if (id == "data") {
                    val f = fmt ?: return null
                    if (f[0] != 1 && f[0] != 3) return null
                    val start = (o + 8).toLong()
                    // a writer that never came back to fill the size leaves 0 or 0xFFFFFFFF
                    val bytes = if (len == 0L || len == 0xFFFFFFFFL) fileSize - start else minOf(len, fileSize - start)
                    return WavInfo(f[2], f[1], f[3], f[0] == 3, start, bytes)
                }
                o += 8 + len.toInt() + (len.toInt() and 1)
            }
            return null
        }
    }
}

/** Summing stems with solo and mute. */
object StemMix {
    /** Any solo: only the soloed play. Mute always wins. */
    fun gains(solo: BooleanArray, mute: BooleanArray): FloatArray {
        val any = solo.any { it }
        return FloatArray(solo.size) { if (mute[it] || (any && !solo[it])) 0f else 1f }
    }

    /** Adds [src] × [gain] into [acc] (both stereo interleaved, [n] values). */
    fun add(acc: FloatArray, src: FloatArray, gain: Float, n: Int) {
        if (gain == 0f) return
        for (i in 0 until n) acc[i] += src[i] * gain
    }

    /** Leaves −0.9…0.9 alone, bends the rest smoothly towards ±1 so a loud sum does not wrap. */
    fun soft(v: Float): Float {
        val a = abs(v)
        if (a <= KNEE) return v
        val over = (a - KNEE) / (1 - KNEE)
        val bent = KNEE + (1 - KNEE) * tanh(over.toDouble()).toFloat()
        return if (v < 0) -bent else bent
    }

    private const val KNEE = 0.9f
}
