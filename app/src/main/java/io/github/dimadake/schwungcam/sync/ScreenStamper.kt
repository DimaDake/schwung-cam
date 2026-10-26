package io.github.dimadake.schwungcam.sync

import io.github.dimadake.schwungcam.stream.PcmChunk
import io.github.dimadake.schwungcam.stream.ScreenFrame

/** A screen frame with the phone time at which the Move showed it. */
class StampedScreen(val lum: ByteArray, val stampNs: Long)

/**
 * display-server sends a tick's screen frame (and surface snapshot) just
 * before that tick's pcm chunk, so the chunk's newest sample dates the item
 * through the clock envelope, and the item's own Wi-Fi jitter drops out. With
 * no audio flowing there is nothing to date it by, so it keeps its arrival time.
 */
class TickStamper<T>(
    private val envelope: ClockEnvelope,
    private val emit: (T, Long) -> Unit,
) {
    private var pending: T? = null
    private var pendingNs = 0L
    private var lastPcmNs = Long.MIN_VALUE

    fun onItem(item: T, arrivalNs: Long) {
        pending?.let { emit(it, pendingNs) }
        pending = null
        if (lastPcmNs == Long.MIN_VALUE || arrivalNs - lastPcmNs > AUDIO_IDLE_NS) emit(item, arrivalNs)
        else { pending = item; pendingNs = arrivalNs }
    }

    /** Call AFTER the chunk was added to the envelope. */
    fun onPcm(c: PcmChunk) {
        lastPcmNs = c.arrivalNs
        val p = pending ?: return
        pending = null
        val stamp = if (c.arrivalNs - pendingNs <= SAME_TICK_NS) envelope.livePhoneNs(c.endPos) else null
        emit(p, stamp ?: pendingNs)
    }

    companion object {
        const val SAME_TICK_NS = 20_000_000L
        const val AUDIO_IDLE_NS = 500_000_000L
    }
}

class ScreenStamper(envelope: ClockEnvelope, emit: (StampedScreen) -> Unit) {
    private val s = TickStamper<ScreenFrame>(envelope) { f, ns -> emit(StampedScreen(f.lum, ns)) }
    fun onScreen(f: ScreenFrame) = s.onItem(f, f.arrivalNs)
    fun onPcm(c: PcmChunk) = s.onPcm(c)
}
