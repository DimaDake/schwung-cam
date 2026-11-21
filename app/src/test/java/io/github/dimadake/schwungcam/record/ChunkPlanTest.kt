package io.github.dimadake.schwungcam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlanTest {
    private val rate = 44100

    @Test fun `a short take is one chunk over everything`() {
        val p = ChunkPlan.plan(30L * rate, rate, 4)
        assertEquals(1, p.size)
        assertEquals(Chunk(0, 0, Long.MAX_VALUE, 0, 30L * rate), p[0])
    }

    @Test fun `a long take is cut on frames, every frame kept once, with pre and post roll`() {
        val total = 1453L * rate + 123  // the 24 min take, not a whole number of frames
        val p = ChunkPlan.plan(total, rate, 4)
        assertEquals(4, p.size)
        val frames = (total + ChunkPlan.FRAME - 1) / ChunkPlan.FRAME
        assertEquals(0L, p[0].keepFrom)
        for (i in 1 until p.size) {
            assertEquals("chunk $i starts where ${i - 1} stops keeping", p[i - 1].keepTo, p[i].keepFrom)
            assertEquals("pre-roll", (p[i].keepFrom - ChunkPlan.PRE) * ChunkPlan.FRAME, p[i].inFrom)
            assertEquals("post-roll", (p[i - 1].keepTo + ChunkPlan.POST) * ChunkPlan.FRAME, p[i - 1].inTo)
        }
        assertTrue(p.last().keepFrom < frames)
        assertEquals(Long.MAX_VALUE, p.last().keepTo)
        assertEquals(total, p.last().inTo)
        assertTrue("about even", p.dropLast(1).all { kotlin.math.abs((it.keepTo - it.keepFrom) - frames / 4) <= 1 })
    }

    @Test fun `chunks are no shorter than a minute`() {
        assertEquals(2, ChunkPlan.plan(150L * rate, rate, 4).size)
    }

    @Test fun `timestamps map to frames on the sample clock`() {
        for (f in listOf(0L, 1L, 17L, 62_609L)) assertEquals(f, ChunkPlan.frameAt(f * ChunkPlan.FRAME * 1_000_000 / rate, rate))
    }

    @Test fun `joins needs one unbroken run`() {
        assertTrue(ChunkPlan.joins(listOf(longArrayOf(0, 1, 2), longArrayOf(3, 4), longArrayOf(5))))
        assertFalse("gap", ChunkPlan.joins(listOf(longArrayOf(0, 1), longArrayOf(3))))
        assertFalse("repeat", ChunkPlan.joins(listOf(longArrayOf(0, 1, 2), longArrayOf(2, 3))))
        assertFalse("nothing", ChunkPlan.joins(listOf(longArrayOf(), longArrayOf())))
    }
}
