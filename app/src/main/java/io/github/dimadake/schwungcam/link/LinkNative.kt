package io.github.dimadake.schwungcam.link

/** A Link Audio channel in the session. */
data class LinkChannel(val id: String, val peer: String, val name: String) {
    /** What a pick is remembered by: ids last only as long as the channel. */
    val key get() = keyOf(peer, name)

    companion object {
        fun keyOf(peer: String, name: String) = "$peer\t$name"
        fun peerOf(key: String) = key.substringBefore('\t')
        fun nameOf(key: String) = key.substringAfter('\t')

        /**
         * Picks saved before movy padded its track names (8f3e377a: Movy 1 →
         * Movy 01) under the names movy announces now; a pick made twice is kept once.
         */
        fun migratePicks(picks: Set<String>): Set<String> = picks.map { k ->
            val m = Regex("^Movy\tMovy ([1-9])$").find(k)
            if (m != null) keyOf("Movy", "Movy 0" + m.groupValues[1]) else k
        }.toSet()

        /** Numbers compare as numbers, so "10-Audio" follows "9-Audio", not "1-Audio". */
        val natural: Comparator<String> = Comparator { a, b ->
            val ta = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
            val tb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(ta.size, tb.size)) {
                val x = ta[i]; val y = tb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()).let { if (it != 0) it else x.length - y.length }
                else x.compareTo(y, ignoreCase = true)
                if (c != 0) return@Comparator c
            }
            ta.size - tb.size
        }

        /** The picker's order: by peer, then by name with numbers in order. */
        val order: Comparator<LinkChannel> = compareBy<LinkChannel, String>(natural) { it.peer }.thenBy(natural) { it.name }
    }
}

/** What [StemRecorder] needs of Link; [LinkNative] on the phone, a fake in tests. */
interface LinkPort {
    fun setActive(on: Boolean, nowMs: Long)
    fun poll(nowMs: Long)
    fun peers(): Int
    /** Link's clock (CLOCK_MONOTONIC_RAW), µs. */
    fun nowUs(): Long
    fun channels(): List<LinkChannel>
    fun subscribe(ids: List<String>): List<String>
    /** (count, rate, startUs, frames) per block in [meta], their frames in [pcm]; returns the block count. */
    fun drain(id: String, meta: LongArray, pcm: ShortArray): Int
    fun close()
}

/** The Ableton Link SDK (GPL-2.0-or-later) in libschwungcam_link.so. */
class LinkNative private constructor() : LinkPort {
    private var h = create()
    private val ok get() = h != 0L

    override fun setActive(on: Boolean, nowMs: Long) = setActive(h, on, nowMs)
    override fun poll(nowMs: Long) = poll(h, nowMs)
    override fun peers() = peers(h)
    override fun nowUs() = nowUs(h)
    override fun channels() = channels(h).map { s -> s.split('\t', limit = 3).let { LinkChannel(it[0], it[1], it[2]) } }
    override fun subscribe(ids: List<String>) = subscribe(h, ids.toTypedArray()).toList()
    override fun drain(id: String, meta: LongArray, pcm: ShortArray) = drain(h, id, meta, pcm)
    override fun close() { if (h != 0L) { destroy(h); h = 0 } }

    private external fun create(): Long
    private external fun destroy(h: Long)
    private external fun setActive(h: Long, on: Boolean, nowMs: Long)
    private external fun poll(h: Long, nowMs: Long)
    private external fun peers(h: Long): Int
    private external fun nowUs(h: Long): Long
    private external fun channels(h: Long): Array<String>
    private external fun subscribe(h: Long, ids: Array<String>): Array<String>
    private external fun drain(h: Long, id: String, meta: LongArray, pcm: ShortArray): Int

    companion object {
        /** Null where the library cannot load (JVM unit tests). */
        fun open(): LinkPort? = try {
            System.loadLibrary("schwungcam_link"); LinkNative().takeIf { it.ok }
        } catch (e: UnsatisfiedLinkError) { null }
    }
}
