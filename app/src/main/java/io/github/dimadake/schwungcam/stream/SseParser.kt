package io.github.dimadake.schwungcam.stream

import java.io.ByteArrayOutputStream

/** One Server-Sent Event; [name] is "message" for an unnamed event. */
class SseEvent(val name: String, val data: String, val arrivalNs: Long)

/**
 * Incremental SSE parser. The arrival time is taken when the terminating blank
 * line is seen, because sync is measured from when an event became usable, and
 * a 20 KB pcm event can span many socket reads.
 */
class SseParser(private val onEvent: (SseEvent) -> Unit) {
    private val line = ByteArrayOutputStream(32 * 1024)
    private val data = StringBuilder(32 * 1024)
    private var name: String? = null
    private var hasData = false
    private var lastWasCr = false

    fun feed(buf: ByteArray, len: Int, nowNs: Long) {
        for (i in 0 until len) {
            val b = buf[i]
            when (b) {
                '\n'.code.toByte() -> {
                    if (!lastWasCr) endLine(nowNs)
                    lastWasCr = false
                }
                '\r'.code.toByte() -> { endLine(nowNs); lastWasCr = true }
                else -> { line.write(b.toInt()); lastWasCr = false }
            }
        }
    }

    private fun endLine(nowNs: Long) {
        if (line.size() == 0) {
            if (hasData) onEvent(SseEvent(name ?: "message", data.toString(), nowNs))
            data.setLength(0); name = null; hasData = false
            return
        }
        val s = line.toString(Charsets.UTF_8.name())
        line.reset()
        if (s.startsWith(":")) return
        val colon = s.indexOf(':')
        val field = if (colon < 0) s else s.substring(0, colon)
        var value = if (colon < 0) "" else s.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> name = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value); hasData = true
            }
        }
    }
}
