package io.github.dimadake.schwungcam.record

import io.github.dimadake.schwungcam.stream.PcmChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordTest {
    /** Records frames as their left-channel values. */
    private class MemSink : PcmSink {
        val frames = ArrayList<Short>()
        var closed = false
        override fun write(pcm: ShortArray, offFrames: Int, frames: Int) { for (i in offFrames until offFrames + frames) this.frames.add(pcm[2 * i]) }
        override fun silence(frames: Long) { repeat(frames.toInt()) { this.frames.add(0) } }
        override fun close() { closed = true }
    }

    /** A chunk whose samples are their own pos (mod 30000), +1 so 0 is distinguishable as silence. */
    private fun chunk(pos: Long, n: Int) = PcmChunk(pos, n, 100, ShortArray(n * 2) { ((pos + it / 2) % 30000 + 1).toShort() }, 0)

    @Test fun `pre-roll, gaps and overlaps land on one timeline`() {
        val rec = PcmRecorder(preRollFrames = 20)
        rec.onChunk(chunk(0, 10)); rec.onChunk(chunk(10, 10)); rec.onChunk(chunk(20, 10))
        val sink = MemSink()
        rec.start(sink)                       // pre-roll keeps the newest ≥ 20 frames: pos 10..30
        rec.onChunk(chunk(35, 5))             // gap of 5
        rec.onChunk(chunk(38, 6))             // overlaps 2
        rec.onChunk(chunk(30, 4))             // already have it
        val t = rec.stop()!!
        assertEquals(10L, t.startPos); assertEquals(34L, t.frames); assertEquals(5L, t.gapFrames); assertFalse(t.endedEarly)
        assertTrue(sink.closed)
        val expect = (10 until 44).map { if (it in 30 until 35) 0 else it + 1 }
        assertEquals(expect, sink.frames.map { it.toInt() })
    }

    @Test fun `pos going far back ends the take`() {
        val rec = PcmRecorder(preRollFrames = 0)
        val sink = MemSink()
        rec.start(sink)
        rec.onChunk(chunk(10_000, 100))
        rec.onChunk(chunk(5, 100))            // device restarted
        rec.onChunk(chunk(10_100, 100))
        val t = rec.stop()!!
        assertTrue(t.endedEarly); assertEquals(100L, t.frames)
    }

    @Test fun `no audio means no take`() {
        val rec = PcmRecorder()
        rec.start(MemSink())
        assertNull(rec.stop())
    }

    @Test fun `wav round trip and range padding`() {
        val f = File.createTempFile("take", ".wav"); f.deleteOnExit()
        val w = WavWriter(f)
        w.write(ShortArray(20) { (it * 1000).toShort() }, 0, 10)
        w.silence(5)
        w.close()
        assertEquals(44L + 15 * 4, f.length())
        val hdr = f.readBytes()
        assertEquals("RIFF", String(hdr, 0, 4)); assertEquals(60, java.nio.ByteBuffer.wrap(hdr, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)

        // skip −2 (lead silence), take 20 frames: 2 silent, 15 from file, 3 padding
        val r = RangeReader(f, -2, 20)
        val l = FloatArray(64); val rr = FloatArray(64)
        var total = 0
        val left = ArrayList<Float>()
        while (true) { val n = r.read(l, rr, 7); if (n == 0) break; total += n; for (i in 0 until n) left.add(l[i]) }
        r.close()
        assertEquals(20, total)
        assertEquals(0f, left[0]); assertEquals(0f, left[1])
        assertEquals(0f, left[2])                    // frame 0 of the file
        assertEquals(2000 / 32768f, left[3], 1e-6f)   // frame 1: left = sample index 2 → 2000
        assertEquals(0f, left[19])

        val r2 = RangeReader(f, 3, 2)
        assertEquals(2, r2.read(l, rr, 64)); assertEquals(6000 / 32768f, l[0], 1e-6f); r2.close()
    }
}
