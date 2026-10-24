package io.github.dimadake.schwungcam.sync

import io.github.dimadake.schwungcam.stream.PcmChunk
import io.github.dimadake.schwungcam.stream.ScreenFrame

/** A screen frame with the phone time at which the Move showed it. */
class StampedScreen(val lum: ByteArray, val stampNs: Long)

/**
 * display-server sends a tick's screen frame just before that tick's pcm
 * chunk, so the chunk's newest sample dates the frame through the clock
 * envelope, and the frame's own Wi-Fi jitter drops out. With no audio
 * flowing there is nothing to date it by, so it keeps its arrival time.
 */
class ScreenStamper(
    private val envelope: ClockEnvelope,
    private val emit: (StampedScreen) -> Unit,
) {
    private var pending: ScreenFrame? = null
    private var lastPcmNs = Long.MIN_VALUE

    fun onScreen(f: ScreenFrame) {
        pending?.let { emit(StampedScreen(it.lum, it.arrivalNs)) }
        pending = null
        if (lastPcmNs == Long.MIN_VALUE || f.arrivalNs - lastPcmNs > AUDIO_IDLE_NS) emit(StampedScreen(f.lum, f.arrivalNs))
        else pending = f
    }

    /** Call AFTER the chunk was added to the envelope. */
    fun onPcm(c: PcmChunk) {
        lastPcmNs = c.arrivalNs
        val p = pending ?: return
        pending = null
        val stamp = if (c.arrivalNs - p.arrivalNs <= SAME_TICK_NS) envelope.livePhoneNs(c.endPos) else null
        emit(StampedScreen(p.lum, stamp ?: p.arrivalNs))
    }

    companion object {
        const val SAME_TICK_NS = 20_000_000L
        const val AUDIO_IDLE_NS = 500_000_000L
    }
}
