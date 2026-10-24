package io.github.dimadake.schwungcam.record

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import io.github.dimadake.schwungcam.dsp.MasterReport
import io.github.dimadake.schwungcam.dsp.MasterSettings
import io.github.dimadake.schwungcam.dsp.Mastering
import io.github.dimadake.schwungcam.sync.ClockEnvelope
import io.github.dimadake.schwungcam.sync.Timeline
import java.io.File
import java.nio.ByteBuffer

class FinalResult(val uri: Uri, val name: String, val report: MasterReport?, val warning: String?)

/** After a take: fit the clocks, master, encode AAC, remux onto the audio clock, publish. */
class Finalizer(private val resolver: ContentResolver, private val rate: Int = 44100) {

    fun run(
        dir: File, name: String,
        video: VideoResult, audio: TakeAudio?, envelope: ClockEnvelope,
        master: MasterSettings, keepWav: Boolean,
        progress: (Float) -> Unit,
    ): FinalResult {
        val videoFile = File(dir, "video.mp4")
        val wav = File(dir, "audio.wav")
        val m4a = File(dir, "audio.m4a")
        var warning: String? = null
        var report: MasterReport? = null
        var timeline: Timeline? = null

        if (audio != null && audio.frames > 0) {
            val fromSec = audio.startPos.toDouble() / rate
            val fit = envelope.fit(fromSec, fromSec + audio.frames.toDouble() / rate)
            if (fit != null) {
                val tl = Timeline(fit, video.firstFrameNs, rate)
                timeline = tl
                val (skip, len) = tl.audioRange(audio.startPos, video.lastPtsUs + FRAME_US)
                val enc = AacEncoder(m4a, rate)
                report = Mastering.run(rate, master, { RangeReader(wav, skip, len) }, enc) { progress(it * 0.85f) }
                enc.finish()
                if (keepWav) saveWav(name, wav, skip, len)
                if (audio.endedEarly) warning = "The Move restarted during the take; audio stops there."
                else if (audio.gapFrames > rate / 10) warning = "Audio had ${audio.gapFrames * 1000 / rate} ms of dropouts (filled with silence)."
            }
        }
        if (timeline == null) warning = "No Schwung audio arrived; the video has no sound. Turn on Mirror Display in Schwung."

        val uri = insertVideo(name)
        resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
            remux(videoFile, if (timeline != null) m4a else null, timeline, MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4))
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        progress(1f)
        dir.deleteRecursively()
        return FinalResult(uri, name, report, warning)
    }

    private fun remux(videoFile: File, audioFile: File?, tl: Timeline?, mux: MediaMuxer) {
        val vx = MediaExtractor().apply { setDataSource(videoFile.path) }
        vx.selectTrack(0)
        val vt = mux.addTrack(vx.getTrackFormat(0))
        val ax = audioFile?.let { MediaExtractor().apply { setDataSource(it.path); selectTrack(0) } }
        val at = ax?.let { mux.addTrack(it.getTrackFormat(0)) } ?: -1
        mux.start()
        val buf = ByteBuffer.allocateDirect(8 shl 20)
        val info = MediaCodec.BufferInfo()
        var vDone = false
        var aDone = ax == null
        while (!vDone || !aDone) {
            val vPts = if (vDone) Long.MAX_VALUE else (tl?.videoPtsUs(vx.sampleTime) ?: vx.sampleTime)
            val aPts = if (aDone) Long.MAX_VALUE else ax!!.sampleTime
            val useVideo = vPts <= aPts
            val x = if (useVideo) vx else ax!!
            info.size = x.readSampleData(buf, 0)
            if (info.size < 0) { if (useVideo) vDone = true else aDone = true; continue }
            info.offset = 0
            info.presentationTimeUs = if (useVideo) vPts else aPts
            info.flags = if (x.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            mux.writeSampleData(if (useVideo) vt else at, buf, info)
            if (!x.advance()) { if (useVideo) vDone = true else aDone = true }
        }
        mux.stop(); mux.release()
        vx.release(); ax?.release()
    }

    private fun insertVideo(name: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SchwungCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)!!
    }

    /** The unmastered audio trimmed to the video, so it lines up at 0 in a DAW. */
    private fun saveWav(name: String, wav: File, skip: Long, len: Long) {
        val tmp = File(wav.parentFile, "raw.wav")
        val w = WavWriter(tmp, rate)
        RangeReader(wav, skip, len).use { r ->
            val l = FloatArray(4096); val rr = FloatArray(4096); val s = ShortArray(8192)
            while (true) {
                val n = r.read(l, rr, 4096)
                if (n == 0) break
                for (i in 0 until n) {
                    s[2 * i] = Math.round(l[i] * 32768f).coerceIn(-32768, 32767).toShort()
                    s[2 * i + 1] = Math.round(rr[i] * 32768f).coerceIn(-32768, 32767).toShort()
                }
                w.write(s, 0, n)
            }
        }
        w.close()
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SchwungCam")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v) ?: return
        resolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
    }

    companion object { const val FRAME_US = 33_333L }
}
