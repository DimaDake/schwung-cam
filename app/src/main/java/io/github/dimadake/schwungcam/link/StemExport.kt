package io.github.dimadake.schwungcam.link

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/** A raw stem as the 24-bit stereo WAV beside the video: frame 0 is the video's first frame. */
object StemExport {
    /**
     * Frames of the cache file to skip (negative = that much lead-in silence)
     * and the length, for a stem whose first frame plays [anchorSec] into a
     * video of [durationSec].
     */
    fun range(anchorSec: Double, rate: Int, durationSec: Double): Pair<Long, Long> =
        -(anchorSec * rate).roundToLong() to (durationSec * rate).roundToLong().coerceAtLeast(0)

    /** One file name per stem, unique within the take: the channel name, then its peer if two share it. */
    fun fileNames(stems: List<Pair<String, String>>): List<String> {
        val base = stems.map { (_, name) -> clean(name) }
        val named = stems.mapIndexed { i, (peer, _) -> if (base.count { it == base[i] } > 1) "${base[i]} (${clean(peer)})" else base[i] }
        val seen = HashMap<String, Int>()
        return named.map { n -> val k = seen.merge(n, 1, Int::plus)!!; if (k == 1) n else "$n $k" }
    }

    private fun clean(s: String) = s.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "Channel" }

    /** Writes [len] frames of the 16-bit cache WAV [src] from frame [skip] as 24-bit; outside it is silence. */
    fun write24(out: OutputStream, src: File, rate: Int, skip: Long, len: Long) {
        val o = BufferedOutputStream(out, 1 shl 16)
        o.write(header24(rate, len))
        val have = (src.length() - 44) / 4
        BufferedInputStream(FileInputStream(src), 1 shl 16).use { input ->
            var toSkip = 44 + maxOf(0L, skip) * 4
            while (toSkip > 0) { val s = input.skip(toSkip); if (s <= 0) break; toSkip -= s }
            val inBuf = ByteArray(CHUNK * 4)
            val outBuf = ByteArray(CHUNK * 6)
            var j = 0L
            while (j < len) {
                val n = minOf(CHUNK.toLong(), len - j).toInt()
                java.util.Arrays.fill(outBuf, 0, n * 6, 0)
                // the part of this chunk the cache file has: [a, b) in chunk frames
                val a = (-(j + skip)).coerceIn(0, n.toLong()).toInt()
                val b = (have - (j + skip)).coerceIn(a.toLong(), n.toLong()).toInt()
                val got = readFully(input, inBuf, (b - a) * 4) / 4
                for (k in 0 until got) {
                    val s = k * 4; val d = (a + k) * 6
                    // the 16-bit sample in the top two bytes, a zero low byte
                    outBuf[d + 1] = inBuf[s]; outBuf[d + 2] = inBuf[s + 1]
                    outBuf[d + 4] = inBuf[s + 2]; outBuf[d + 5] = inBuf[s + 3]
                }
                o.write(outBuf, 0, n * 6)
                j += n
            }
        }
        o.flush()
    }

    private fun readFully(input: BufferedInputStream, b: ByteArray, want: Int): Int {
        var n = 0
        while (n < want) { val k = input.read(b, n, want - n); if (k < 0) break; n += k }
        return n
    }

    private const val CHUNK = 4096

    fun header24(rate: Int, frames: Long): ByteArray {
        val data = minOf(frames * 6, 0xFFFFFFFFL - 36).toInt()
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate)
                .putInt(rate * 6).putShort(6).putShort(24)
            put("data".toByteArray()).putInt(data)
        }.array()
    }
}
