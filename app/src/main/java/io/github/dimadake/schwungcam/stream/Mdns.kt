package io.github.dimadake.schwungcam.stream

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * A one-shot mDNS A query for `*.local`, for phones whose resolver does not
 * do mDNS. The caller holds a Wi-Fi MulticastLock around [resolve].
 */
object Mdns {
    private const val QTYPE_A = 1

    fun query(name: String, id: Int = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        u16(id); u16(0); u16(1); u16(0); u16(0); u16(0)
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(); out.write(b.size); out.write(b)
        }
        out.write(0)
        u16(QTYPE_A)
        u16(0x8001) // QU bit: ask for a unicast reply
        return out.toByteArray()
    }

    /** The first A record for [name] in a response, or null. */
    fun parseA(pkt: ByteArray, len: Int, name: String): ByteArray? {
        if (len < 12) return null
        fun u16(o: Int) = ((pkt[o].toInt() and 0xFF) shl 8) or (pkt[o + 1].toInt() and 0xFF)
        val qd = u16(4); val an = u16(6) + u16(8) + u16(10)
        var o = 12
        repeat(qd) { o = skipName(pkt, o, len) + 4; if (o > len) return null }
        repeat(an) {
            val (n, end) = readName(pkt, o, len) ?: return null
            if (end + 10 > len) return null
            val type = u16(end); val rdlen = u16(end + 8)
            val rd = end + 10
            if (rd + rdlen > len) return null
            if (type == QTYPE_A && rdlen == 4 && n.equals(name.trimEnd('.'), ignoreCase = true))
                return pkt.copyOfRange(rd, rd + 4)
            o = rd + rdlen
        }
        return null
    }

    private fun skipName(p: ByteArray, start: Int, len: Int): Int = readName(p, start, len)?.second ?: len + 1

    /** (dotted name, offset after the name in the record). */
    private fun readName(p: ByteArray, start: Int, len: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var o = start
        var end = -1
        var jumps = 0
        while (true) {
            if (o >= len) return null
            val l = p[o].toInt() and 0xFF
            when {
                l == 0 -> { if (end < 0) end = o + 1; break }
                l and 0xC0 == 0xC0 -> {
                    if (o + 1 >= len || ++jumps > 16) return null
                    if (end < 0) end = o + 2
                    o = ((l and 0x3F) shl 8) or (p[o + 1].toInt() and 0xFF)
                }
                else -> {
                    if (o + 1 + l > len) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(String(p, o + 1, l, Charsets.UTF_8))
                    o += 1 + l
                }
            }
        }
        return sb.toString() to end
    }

    fun resolve(name: String, timeoutMs: Int = 1500): InetAddress? {
        val group = InetAddress.getByName("224.0.0.251")
        MulticastSocket().use { s ->
            s.soTimeout = 250
            val q = query(name)
            val buf = ByteArray(1500)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                s.send(DatagramPacket(q, q.size, group, 5353))
                try {
                    while (true) {
                        val pk = DatagramPacket(buf, buf.size)
                        s.receive(pk)
                        parseA(buf, pk.length, name)?.let { return InetAddress.getByAddress(name, it) }
                    }
                } catch (_: SocketTimeoutException) { }
            }
        }
        return null
    }
}
