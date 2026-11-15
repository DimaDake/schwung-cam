package io.github.dimadake.schwungcam.link

import io.github.dimadake.schwungcam.record.PcmSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StemsTest {
    /** Records frames as their left-channel values; 0 is silence. */
    private class MemSink : PcmSink {
        val frames = ArrayList<Int>()
        var closed = false
        override fun write(pcm: ShortArray, offFrames: Int, frames: Int) { for (i in offFrames until offFrames + frames) this.frames.add(pcm[2 * i].toInt()) }
        override fun silence(frames: Long) { repeat(frames.toInt()) { this.frames.add(0) } }
        override fun close() { closed = true }
    }

    // 1000 Hz: one frame is one ms, so RESYNC_MS (20) is 20 frames
    private val rate = 1000
    private val ms = 1_000_000L

    /** Block [count] of [n] frames whose samples are their frame number + 1, stamped [jitterMs] off its true time. */
    private fun block(count: Long, n: Int = 10, at: Long = count * n, jitterMs: Long = 0, t0: Long = 5000 * ms) =
        StemBlock(count, rate, t0 + at * ms + jitterMs * ms, ShortArray(n * 2) { (at + it / 2 + 1).toShort() }, n)

    private fun lane(): Pair<StemLane, MemSink> { val s = MemSink(); return StemLane { s } to s }

    @Test fun `buffers lay end to end and the anchor ignores one jittered stamp`() {
        val (l, s) = lane()
        // the FIRST stamp is the late one: the anchor must not be it
        for (c in 0L until 10) l.add(block(c, jitterMs = if (c == 0L) 9 else 0))
        l.finish()
        assertEquals((1..100).toList(), s.frames)
        assertEquals(5000 * ms, l.anchorNs())
        assertEquals(5100 * ms, l.coveredNs())
        assertEquals(0, l.resyncs)
    }

    @Test fun `out of order buffers are put back by count`() {
        val (l, s) = lane()
        for (c in listOf(0L, 2, 1, 3, 5, 4)) l.add(block(c))
        l.finish()
        assertEquals((1..60).toList(), s.frames)
        assertEquals(0L, l.lostFrames)
    }

    @Test fun `a lost buffer is silence its length, so later audio keeps its place`() {
        val (l, s) = lane()
        for (c in (0L until 20).filter { it != 4L }) l.add(block(c))
        l.finish()
        assertEquals((1..200).map { if (it in 41..50) 0 else it }, s.frames)
        assertEquals(10L, l.lostFrames)
    }

    @Test fun `a long hole re-anchors on the stamp`() {
        val (l, s) = lane()
        l.add(block(0)); l.add(block(1))
        // the sender stalled 500 ms and lost 50 buffers
        l.add(block(52)); l.add(block(53))
        l.finish()
        assertEquals(540, s.frames.size)
        assertEquals((1..20).toList() + List(500) { 0 } + (521..540).toList(), s.frames)
        assertEquals(1, l.resyncs)
    }

    @Test fun `a stamp running behind its place drops the overlap`() {
        val (l, s) = lane()
        l.add(block(0)); l.add(block(1)); l.add(block(2))
        // a buffer far ahead by count whose stamp is 25 frames behind its place
        l.add(StemBlock(100, rate, 5000 * ms + 5 * ms, ShortArray(60) { 7 }, 30))
        l.finish()
        // 30 placed, then the new block's first 25 frames already have audio
        assertEquals((1..30).toList() + List(5) { 7 }, s.frames)
    }

    @Test fun `other session and wrong rate buffers are ignored`() {
        val (l, s) = lane()
        l.add(block(0))
        l.add(StemBlock(1, rate, StemBlock.NO_STAMP, ShortArray(20), 10))
        l.add(StemBlock(1, 48000, 5010 * ms, ShortArray(20), 10))
        l.add(block(1))
        l.finish()
        assertEquals((1..20).toList(), s.frames)
    }

    @Test fun `an empty lane has no anchor`() {
        val (l, _) = lane()
        assertNull(l.finish()); assertNull(l.anchorNs()); assertNull(l.coveredNs())
    }

    @Test fun `the picker lists channels with numbers in order`() {
        val ch = listOf("Movy" to "Movy 11", "Live" to "10-Audio", "Movy" to "Movy 2", "Live" to "9-Audio",
            "Movy" to "Movy Master", "Live" to "Main", "Movy" to "Movy 1")
            .map { (p, n) -> LinkChannel(n, p, n) }.sortedWith(LinkChannel.order).map { it.name }
        assertEquals(listOf("9-Audio", "10-Audio", "Main", "Movy 1", "Movy 2", "Movy 11", "Movy Master"), ch)
    }

    // ---------------- export ----------------

    @Test fun `range puts the stem's first frame at its time in the video`() {
        assertEquals(-441L to 441000L, StemExport.range(0.01, 44100, 10.0))  // starts 10 ms in: lead silence
        assertEquals(882L to 441000L, StemExport.range(-0.02, 44100, 10.0))  // started before the video
    }

    @Test fun `file names are unique and safe`() {
        assertEquals(listOf("Movy 1", "Main (Live)", "Main (Move)", "a_b"),
            StemExport.fileNames(listOf("Movy" to "Movy 1", "Live" to "Main", "Move" to "Main", "x" to "a/b")))
        assertEquals(listOf("Main (P)", "Main (P) 2"), StemExport.fileNames(listOf("P" to "Main", "P" to "Main")))
    }

    private fun wav16(frames: List<Pair<Int, Int>>): File {
        val f = File.createTempFile("stem", ".wav")
        val b = ByteBuffer.allocate(44 + frames.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.position(44)
        for ((l, r) in frames) { b.putShort(l.toShort()); b.putShort(r.toShort()) }
        f.writeBytes(b.array())
        return f
    }

    private fun samples24(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 44
        while (i < bytes.size) {
            out.add((bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() shl 16))
            i += 3
        }
        return out
    }

    @Test fun `24-bit export pads the lead, trims the tail and keeps every sample`() {
        val src = wav16(listOf(1 to -1, 2 to -2, -32768 to 32767, 4 to -4))
        val out = ByteArrayOutputStream()
        StemExport.write24(out, src, 1000, skip = -2, len = 5)
        val b = out.toByteArray()
        assertArrayEquals(StemExport.header24(1000, 5), b.copyOf(44))
        assertEquals(44 + 5 * 6, b.size)
        val v = samples24(b).map { it / 256 }
        assertEquals(listOf(0, 0, 0, 0, 1, -1, 2, -2, -32768, 32767), v)
    }

    @Test fun `24-bit export past the end of the stem is silence`() {
        val src = wav16(listOf(5 to 6, 7 to 8))
        val out = ByteArrayOutputStream()
        StemExport.write24(out, src, 1000, skip = 1, len = 3)
        assertEquals(listOf(7, 8, 0, 0, 0, 0), samples24(out.toByteArray()).map { it / 256 })
    }

    @Test fun `24-bit export crosses its chunk size`() {
        val n = 10_000
        val src = wav16(List(n) { (it % 1000) to -(it % 1000) })
        val out = ByteArrayOutputStream()
        StemExport.write24(out, src, 1000, skip = -3, len = n.toLong())
        val v = samples24(out.toByteArray()).map { it / 256 }
        val want = List(3) { listOf(0, 0) }.flatten() + (0 until n - 3).flatMap { listOf(it % 1000, -(it % 1000)) }
        assertEquals(want, v)
    }

    // ---------------- recorder ----------------

    /** A Link session with one channel that hands out 10-frame blocks on demand. */
    private class FakePort(val ch: List<LinkChannel>) : LinkPort {
        var active = false
        var subs = emptyList<String>()
        val queued = HashMap<String, ArrayDeque<LongArray>>()
        override fun setActive(on: Boolean, nowMs: Long) { active = on }
        override fun poll(nowMs: Long) {}
        override fun peers() = 1
        override fun nowUs() = 0L
        override fun channels() = ch
        override fun subscribe(ids: List<String>) = ids.also { subs = it }
        override fun drain(id: String, meta: LongArray, pcm: ShortArray): Int {
            val q = queued[id] ?: return 0
            var n = 0
            while (q.isNotEmpty() && n < meta.size / 4) {
                val (count, start) = q.removeFirst().let { it[0] to it[1] }
                meta[4 * n] = count; meta[4 * n + 1] = 1000; meta[4 * n + 2] = start; meta[4 * n + 3] = 10
                for (i in 0 until 20) pcm[20 * n + i] = (count + 1).toShort()
                n++
            }
            return n
        }
        fun push(id: String, count: Long) = queued.getOrPut(id) { ArrayDeque() }.addLast(longArrayOf(count, count * 10_000))
        override fun close() {}
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 3000
        while (!cond()) { check(System.currentTimeMillis() < until) { "timed out: $what" }; Thread.sleep(5) }
    }

    @Test fun `the recorder keeps a pre-roll, records picks only and names the absent`() {
        val port = FakePort(listOf(LinkChannel("a", "Movy", "Movy 1"), LinkChannel("b", "Movy", "Movy 2")))
        val rec = StemRecorder(openPort = { port }, clockNs = { 0L })
        rec.picks = setOf(LinkChannel.keyOf("Movy", "Movy 1"), LinkChannel.keyOf("Live", "Main"))
        rec.start()
        waitFor("subscribed") { port.subs == listOf("a") }
        for (c in 0L until 5) port.push("a", c)
        waitFor("pre-roll drained") { port.queued["a"]!!.isEmpty() }
        Thread.sleep(50)
        val dir = createTempDir()
        rec.startTake(dir)
        for (c in 5L until 8) port.push("a", c)
        waitFor("take covered") { (rec.coveredNs() ?: 0) >= 80 * 1_000_000L }
        val t = rec.stopTake()!!
        rec.stop()
        assertTrue(!port.active)
        assertEquals(1, t.stems.size)
        val s = t.stems[0]
        assertEquals("Movy 1", s.name); assertEquals(80L, s.frames); assertEquals(0L, s.anchorNs)
        assertEquals(listOf("Main"), t.missing)
        // header + 80 stereo int16 frames, block 0's samples first
        assertEquals(44L + 80 * 4, s.file.length())
        assertEquals(1, ByteBuffer.wrap(s.file.readBytes(), 44, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
        dir.deleteRecursively()
    }
}
