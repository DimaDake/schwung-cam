package io.github.dimadake.schwungcam.record

import io.github.dimadake.schwungcam.stream.PcmChunk

/** Receives int16 stereo interleaved frames in device order. */
interface PcmSink {
    fun write(pcm: ShortArray, offFrames: Int, frames: Int)
    fun silence(frames: Long)
    fun close()
}

class TakeAudio(val startPos: Long, val frames: Long, val gapFrames: Long, val endedEarly: Boolean)

/**
 * Keeps a pre-roll of the stream and, while recording, lays the chunks onto
 * one gap-free timeline indexed by device `pos`.
 *
 * The pre-roll is why the first video frames have audio: pcm for time T
 * arrives a network delay after the camera exposed T.
 */
class PcmRecorder(private val preRollFrames: Int = 2 * 44100) {
    private val preRoll = ArrayDeque<PcmChunk>()
    private var preRollTotal = 0
    private var take: Take? = null

    @Synchronized
    fun onChunk(c: PcmChunk) {
        preRoll.addLast(c); preRollTotal += c.n
        while (preRoll.isNotEmpty() && preRollTotal - preRoll.first().n >= preRollFrames) preRollTotal -= preRoll.removeFirst().n
        take?.append(c)
    }

    @Synchronized
    fun start(sink: PcmSink) {
        val t = Take(sink)
        for (c in preRoll) t.append(c)
        take = t
    }

    /** The device position up to which the take has audio, or null. */
    @Synchronized
    fun coveredPos(): Long? = take?.next

    @Synchronized
    fun isEnded(): Boolean = take?.ended ?: false

    /** Null when no pcm ever arrived during the take. */
    @Synchronized
    fun stop(): TakeAudio? {
        val t = take ?: return null
        take = null
        t.sink.close()
        val s = t.start ?: return null
        return TakeAudio(s, t.next!! - s, t.gaps, t.ended)
    }

    private class Take(val sink: PcmSink) {
        var start: Long? = null
        var next: Long? = null
        var gaps = 0L
        var ended = false

        fun append(c: PcmChunk) {
            if (ended) return
            val nx = next
            if (nx == null) {
                start = c.pos; next = c.endPos
                sink.write(c.pcm, 0, c.n)
                return
            }
            val rate = c.rate.toLong()
            when {
                // a device restart resets pos; the clock fit cannot span it
                c.endPos <= nx - rate || c.pos - nx > MAX_GAP_S * rate -> ended = true
                c.pos >= nx -> {
                    val gap = c.pos - nx
                    if (gap > 0) { sink.silence(gap); gaps += gap }
                    sink.write(c.pcm, 0, c.n)
                    next = c.endPos
                }
                c.endPos > nx -> {
                    val skip = (nx - c.pos).toInt()
                    sink.write(c.pcm, skip, c.n - skip)
                    next = c.endPos
                }
                else -> Unit // already have it
            }
        }
    }

    companion object { const val MAX_GAP_S = 30 }
}
