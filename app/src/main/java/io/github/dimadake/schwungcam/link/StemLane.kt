package io.github.dimadake.schwungcam.link

import io.github.dimadake.schwungcam.record.PcmSink
import java.util.TreeMap
import kotlin.math.roundToLong

/** One Link Audio buffer, its stamp moved onto the camera clock. */
class StemBlock(val count: Long, val rate: Int, val stampNs: Long, val pcm: ShortArray, val frames: Int) {
    companion object {
        /** The buffer came from another Link session: it has no time on ours. */
        const val NO_STAMP = Long.MIN_VALUE
    }
}

/**
 * Lays one channel's buffers end to end in the SENDER's sample order: its
 * sample clock is the stem's timeline, never resampled. A stamp only decides
 * where the stem starts ([anchorNs], the median over its first seconds, so a
 * jittered stamp cannot move it) and catches a discontinuity: a stamp more
 * than [RESYNC_MS] from its place inserts silence or drops the overlap.
 *
 * Link Audio buffers can arrive out of order, so up to [HOLD] are held to be
 * put back by sequence number; a gap still open after that is a LOST buffer,
 * filled with silence its length so everything after keeps its place.
 */
class StemLane(private val sinkFor: (rate: Int) -> PcmSink) {
    private var sink: PcmSink? = null
    var rate = 0; private set
    private var expected = -1L
    private val pending = TreeMap<Long, StemBlock>()
    private var lastFrames = 0
    /** Frames written, silence included. */
    var frames = 0L; private set
    private var originNs = 0L
    private val offsets = ArrayList<Long>()
    var lostFrames = 0L; private set
    var resyncs = 0; private set

    fun add(b: StemBlock) {
        if (b.stampNs == StemBlock.NO_STAMP || b.frames <= 0) return
        if (rate == 0) rate = b.rate else if (b.rate != rate) return
        if (expected >= 0 && b.count < expected) {
            // a count far behind is the sender starting over, not a late buffer
            if (expected - b.count <= RESTART_BLOCKS) return
            release(true)
            expected = b.count
        }
        pending[b.count] = b
        release(false)
    }

    /** Places everything still held (the take is over). */
    fun finish(): PcmSink? { release(true); return sink }

    /** Stamp of the stem's first frame, or null before any audio. */
    fun anchorNs(): Long? {
        if (frames == 0L) return null
        val s = offsets.sorted()
        return originNs + s[s.size / 2]
    }

    /** Camera time up to which the stem has audio, or null. */
    fun coveredNs(): Long? = anchorNs()?.let { it + (frames * 1e9 / rate).roundToLong() }

    private fun release(all: Boolean) {
        while (pending.isNotEmpty()) {
            val first = pending.firstKey()
            if (expected >= 0 && first != expected && !all && pending.size <= HOLD) return
            val b = pending.remove(first)!!
            if (expected >= 0 && first > expected && lastFrames > 0) {
                val miss = (first - expected) * lastFrames
                // a long hole is left to the stamp, which knows its real length
                if (miss * 1000 < RESYNC_MS * rate) { silence(miss); lostFrames += miss }
            }
            place(b)
            expected = first + 1
        }
    }

    private fun placeNs() = originNs + (frames * 1e9 / rate).roundToLong()

    private fun place(b: StemBlock) {
        if (sink == null) { sink = sinkFor(rate); originNs = b.stampNs }
        val err = b.stampNs - placeNs()
        var off = 0
        if (err > RESYNC_MS * 1_000_000L) {
            silence((err * rate / 1e9).roundToLong()); resyncs++
        } else if (err < -RESYNC_MS * 1_000_000L) {
            resyncs++
            val drop = (-err * rate / 1e9).roundToLong()
            if (drop >= b.frames) return
            off = drop.toInt()
        }
        if (frames < ANCHOR_SEC * rate) offsets.add(b.stampNs + (off * 1e9 / rate).roundToLong() - placeNs())
        sink!!.write(b.pcm, off, b.frames - off)
        frames += b.frames - off
        lastFrames = b.frames
    }

    private fun silence(n: Long) {
        if (n <= 0) return
        sink!!.silence(n); frames += n
    }

    companion object {
        const val RESYNC_MS = 20L
        const val HOLD = 8
        const val RESTART_BLOCKS = 64
        const val ANCHOR_SEC = 2
    }
}
