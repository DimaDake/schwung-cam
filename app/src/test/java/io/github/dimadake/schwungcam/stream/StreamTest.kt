package io.github.dimadake.schwungcam.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class StreamTest {
    private fun parseAll(chunks: List<String>): List<SseEvent> {
        val out = ArrayList<SseEvent>()
        val p = SseParser { out.add(it) }
        chunks.forEachIndexed { i, c -> val b = c.toByteArray(); p.feed(b, b.size, i.toLong()) }
        return out
    }

    @Test fun `named and unnamed events, split across reads`() {
        val ev = parseAll(listOf("data: {\"a\":1}\n\nevent: pcm\nda", "ta: {\"pos\":5}\n", "\n: comment\nevent: hb\r\ndata: x\r\n\r\n"))
        assertEquals(3, ev.size)
        assertEquals("message", ev[0].name); assertEquals("{\"a\":1}", ev[0].data)
        assertEquals("pcm", ev[1].name); assertEquals("{\"pos\":5}", ev[1].data)
        assertEquals("arrival is when the blank line came", 2L, ev[1].arrivalNs)
        assertEquals("hb", ev[2].name)
    }

    @Test fun `multi-line data joins with newline`() {
        val ev = parseAll(listOf("data: a\ndata: b\n\n"))
        assertEquals("a\nb", ev.single().data)
    }

    @Test fun `event without data is not dispatched`() {
        assertEquals(0, parseAll(listOf("event: x\n\n")).size)
    }

    @Test fun `mono1 decode follows page-major bit order`() {
        val raw = ByteArray(1024)
        raw[0] = 0b0000_0101         // page 0, col 0: rows 0 and 2
        raw[1 * 128 + 7] = 0x80.toByte() // page 1, col 7: row 15
        val lum = ScreenDecoder.decode("mono1_packed", raw)!!
        assertEquals(-1, lum[0].toInt()); assertEquals(0, lum[128].toInt()); assertEquals(-1, lum[2 * 128].toInt())
        assertEquals(-1, lum[15 * 128 + 7].toInt())
        assertEquals(3, lum.count { it.toInt() != 0 })
    }

    @Test fun `gray4 decode, high nibble first`() {
        val raw = ByteArray(4096); raw[0] = 0xF1.toByte()
        val lum = ScreenDecoder.decode("gray4_packed", raw)!!
        assertEquals(255, lum[0].toInt() and 0xFF); assertEquals(17, lum[1].toInt() and 0xFF)
    }

    @Test fun `short payload is rejected`() {
        assertNull(ScreenDecoder.decode("mono1_packed", ByteArray(10)))
    }

    @Test fun `pcm event decodes little-endian interleaved int16`() {
        val raw = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F)
        val d = Base64.getEncoder().encodeToString(raw)
        val c = StreamEvents.decode(SseEvent("pcm", "{\"pos\":1000,\"n\":2,\"rate\":44100,\"d\":\"$d\"}", 9)) as PcmChunk
        assertEquals(1000L, c.pos); assertEquals(2, c.n); assertEquals(1002L, c.endPos); assertEquals(9L, c.arrivalNs)
        assertArrayEquals(shortArrayOf(1, -1, Short.MIN_VALUE, Short.MAX_VALUE), c.pcm)
    }

    @Test fun `screen event decodes`() {
        val d = Base64.getEncoder().encodeToString(ByteArray(1024) { 0xFF.toByte() })
        val f = StreamEvents.decode(SseEvent("message", "{\"format\":\"mono1_packed\",\"encoding\":\"base64\",\"width\":128,\"height\":64,\"source\":\"move 1-bit\",\"data\":\"$d\"}", 0))
        assertNotNull(f as ScreenFrame)
        assertEquals(8192, (f as ScreenFrame).lum.count { it.toInt() == -1 })
    }

    @Test fun `hb and surface are ignored`() {
        assertNull(StreamEvents.decode(SseEvent("hb", "{\"frame\":3}", 0)))
        assertNull(StreamEvents.decode(SseEvent("surface", "{\"v\":1}", 0)))
    }

    @Test fun `mdns query round-trips through a crafted answer`() {
        val q = Mdns.query("move.local")
        // answer: header(an=1) + question + answer with a compression pointer to the question name
        val ans = java.io.ByteArrayOutputStream()
        ans.write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 1, 0, 1, 0, 0, 0, 0))
        ans.write(q, 12, q.size - 12)
        ans.write(byteArrayOf(0xC0.toByte(), 12, 0, 1, 0x80.toByte(), 1, 0, 0, 0, 120, 0, 4, 192.toByte(), 168.toByte(), 1, 42))
        val pkt = ans.toByteArray()
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 42), Mdns.parseA(pkt, pkt.size, "move.local"))
        assertNull(Mdns.parseA(pkt, pkt.size, "other.local"))
        assertNull(Mdns.parseA(pkt, 20, "move.local"))
    }
}
