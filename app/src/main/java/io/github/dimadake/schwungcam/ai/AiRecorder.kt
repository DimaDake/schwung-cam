package io.github.dimadake.schwungcam.ai

/**
 * One take's timeline. Fed from the stream thread (screens and surface
 * snapshots, already dated onto the phone clock) and finished on the take's
 * worker, hence the lock.
 */
class AiRecorder {
    private val surface = SurfaceEvents()
    private val picker = ScreenPicker()
    private val events = ArrayList<InputEvent>()
    private var done = false

    @Synchronized
    fun onScreen(lum: ByteArray, stampNs: Long) { if (!done) picker.onScreen(lum, stampNs) }

    @Synchronized
    fun onSurface(s: SurfaceSnapshot, stampNs: Long) {
        if (done) return
        for (e in surface.onSnapshot(s, stampNs)) { events += e; picker.onInput(e) }
        picker.tick(stampNs)
    }

    @Synchronized
    fun finish(): AiTake {
        if (!done) { done = true; picker.finish() }
        return AiTake(events.toList(), picker.picked.toList())
    }
}
