package io.github.dimadake.schwungcam.record

import io.github.dimadake.schwungcam.ai.AiRecorder
import io.github.dimadake.schwungcam.ai.SurfaceSnapshot
import io.github.dimadake.schwungcam.dsp.MasterSettings
import io.github.dimadake.schwungcam.gl.RenderThread
import io.github.dimadake.schwungcam.link.StemRecorder
import io.github.dimadake.schwungcam.sync.ClockEnvelope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** One take at a time: start → stop → finalize on a worker thread. */
class TakeController(
    private val cacheDir: File,
    private val render: RenderThread,
    private val pcm: PcmRecorder,
    private val envelope: ClockEnvelope,
    private val finalizer: Finalizer,
    private val stems: StemRecorder? = null,
    private val rate: Int = 44100,
) {
    private var enc: VideoEncoder? = null
    private var dir: File? = null
    private var name = ""
    @Volatile private var lastFrameNs = 0L
    /** The take's AI timeline, when that option is on; fed from the stream thread. */
    @Volatile private var ai: AiRecorder? = null
    val recording get() = enc != null

    fun onScreen(lum: ByteArray, stampNs: Long) { ai?.onScreen(lum, stampNs) }
    fun onSurface(s: SurfaceSnapshot, stampNs: Long) { ai?.onSurface(s, stampNs) }

    /** [linkStems]: also record the picked Link Audio channels. */
    fun start(width: Int, height: Int, bitrate: Int, aiTimeline: Boolean, linkStems: Boolean = false) {
        check(enc == null)
        name = "SchwungCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val d = File(cacheDir, "take-$name").apply { deleteRecursively(); mkdirs() }
        dir = d
        val e = VideoEncoder(File(d, "video.mp4"), width, height, bitrate)
        enc = e
        pcm.start(WavWriter(File(d, "audio.wav"), rate))
        render.onFrameDrawn = { lastFrameNs = it }
        ai = if (aiTimeline) AiRecorder() else null
        if (linkStems) stems?.startTake(d)
        render.setEncoder(e.inputSurface, width, height)
    }

    /**
     * Stops feeding the encoder now, then keeps the audio running until the
     * stream has covered the last frame plus [TAIL_MS] (pcm for a moment
     * arrives a network delay after it), and finalizes on a worker thread.
     */
    fun stop(master: MasterSettings, keepWav: Boolean, progress: (Float) -> Unit, done: (Result<FinalResult>) -> Unit) {
        val e = enc ?: return
        enc = null
        render.setEncoder(null, 0, 0)
        render.onFrameDrawn = null
        val d = dir!!
        val takeAi = ai?.finish()
        ai = null
        thread(name = "finalize") {
            val res = runCatching {
                val video = e.finish() ?: error("no video frames were recorded")
                waitForAudioTail()
                val audio = pcm.stop()
                waitForStemsTail()
                val takeStems = stems?.stopTake()
                finalizer.run(d, name, video, audio, envelope, master, keepWav, takeAi, takeStems, progress)
            }
            if (res.isFailure) { pcm.stop(); stems?.stopTake(); d.deleteRecursively() }
            done(res)
        }
    }

    private fun waitForAudioTail() {
        val off = envelope.liveOffset() ?: return
        val targetPos = ((lastFrameNs / 1e9 + TAIL_MS / 1000.0 - off) * rate).toLong()
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline && !pcm.isEnded()) {
            val c = pcm.coveredPos() ?: return
            if (c >= targetPos) return
            Thread.sleep(20)
        }
    }

    /** Link buffers for the last frame arrive a network delay after it, as the stream's do. */
    private fun waitForStemsTail() {
        val s = stems ?: return
        val target = lastFrameNs + TAIL_MS * 1_000_000L
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val c = s.coveredNs() ?: return
            if (c >= target) return
            Thread.sleep(20)
        }
    }

    companion object {
        const val TAIL_MS = 300
        const val MAX_WAIT_MS = 1500L
    }
}
