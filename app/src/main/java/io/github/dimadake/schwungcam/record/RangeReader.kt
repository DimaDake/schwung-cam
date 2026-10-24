package io.github.dimadake.schwungcam.record

import io.github.dimadake.schwungcam.dsp.FrameReader
import java.io.File

/**
 * Exactly [length] frames of [file] starting at frame [skip]. A negative skip
 * is lead-in silence (the WAV began after the first video frame), and the end
 * is padded, so the audio always spans the video.
 */
class RangeReader(file: File, private val skip: Long, private val length: Long) : FrameReader {
    private val wav = WavReader(file, maxOf(0L, skip))
    private var lead = maxOf(0L, -skip)
    private var left = length

    override fun read(l: FloatArray, r: FloatArray, max: Int): Int {
        if (left <= 0) return 0
        val want = minOf(max.toLong(), left).toInt()
        var got = 0
        if (lead > 0) {
            val z = minOf(lead, want.toLong()).toInt()
            java.util.Arrays.fill(l, 0, z, 0f); java.util.Arrays.fill(r, 0, z, 0f)
            lead -= z; got = z
        }
        if (got < want) {
            val tl = FloatArray(want - got); val tr = FloatArray(want - got)
            val k = wav.read(tl, tr, want - got)
            System.arraycopy(tl, 0, l, got, k); System.arraycopy(tr, 0, r, got, k)
            got += k
            if (got < want) { java.util.Arrays.fill(l, got, want, 0f); java.util.Arrays.fill(r, got, want, 0f); got = want }
        }
        left -= got
        return got
    }

    override fun close() = wav.close()
}
