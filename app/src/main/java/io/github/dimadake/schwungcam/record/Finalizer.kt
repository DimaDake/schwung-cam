package io.github.dimadake.schwungcam.record

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import io.github.dimadake.schwungcam.ai.AiExport
import io.github.dimadake.schwungcam.ai.AiTake
import io.github.dimadake.schwungcam.dsp.MasterReport
import io.github.dimadake.schwungcam.dsp.MasterSettings
import io.github.dimadake.schwungcam.dsp.Mastering
import io.github.dimadake.schwungcam.link.StemExport
import io.github.dimadake.schwungcam.link.StemsTake
import io.github.dimadake.schwungcam.sync.ClockEnvelope
import io.github.dimadake.schwungcam.sync.Timeline
import java.io.File
import java.nio.ByteBuffer

class FinalResult(val uri: Uri, val name: String, val report: MasterReport?, val warning: String?)

/** After a take: fit the clocks, master, encode AAC, remux onto the audio clock, publish. */
class Finalizer(private val resolver: ContentResolver, private val rate: Int = 44100, private val peaksDir: File? = null) {

    fun run(
        dir: File, name: String,
        video: VideoResult, audio: TakeAudio?, envelope: ClockEnvelope,
        master: MasterSettings, keepWav: Boolean,
        ai: AiTake?,
        stems: StemsTake?,
        progress: (Float) -> Unit,
    ): FinalResult {
        val videoFile = File(dir, "video.mp4")
        val wav = File(dir, "audio.wav")
        var warning: String? = null
        var report: MasterReport? = null
        var timeline: Timeline? = null
        var encoded: EncodedAudio? = null

        if (audio != null && audio.frames > 0) {
            val fromSec = audio.startPos.toDouble() / rate
            val raw = envelope.fit(fromSec, fromSec + audio.frames.toDouble() / rate)
            val transit = if (raw != null && stems?.reference != null) measureTransit(wav, audio, raw, stems.reference) else null
            if (raw != null && stems != null && transit == null)
                warning = "Could not measure the Wi-Fi delay from Link, so the sound may sit ~40 ms late against the picture."
            // the envelope traced offset + minimum transit: take the transit out, so the sound is on time
            val fit = if (raw != null && transit != null) io.github.dimadake.schwungcam.sync.ClockFit(raw.a - transit, raw.b) else raw
            if (fit != null) timeline = Timeline(fit, video.firstFrameNs, rate)
        }
        if (timeline == null) warning = "No Schwung audio arrived; the video has no sound. Turn on Mirror Display in Schwung."

        // camera time → seconds into the video, on the same clock the frames were placed by
        val tl = timeline
        val toSec: (Long) -> Double = if (tl != null) { ns -> (tl.posAt(ns) - tl.p0) / rate } else { ns -> (ns - video.firstFrameNs) / 1e9 }
        val durationSec = (video.lastPtsUs + FRAME_US) / 1e6
        // the timeline and stems live only in memory, and mastering a long take takes
        // minutes: save them first, so they survive the app being closed meanwhile
        if (ai != null) {
            // a failed timeline costs only itself
            try {
                saveAi(name, durationSec, ai, toSec)
            } catch (e: Exception) {
                Log.w("SchwungCam", "ai timeline", e)
                warning = listOfNotNull(warning, "The AI timeline could not be saved: ${e.message}").joinToString("\n\n")
            }
        }
        if (stems != null) {
            val notes = try {
                saveStems(name, stems, durationSec, toSec)
            } catch (e: Exception) {
                Log.w("SchwungCam", "link stems", e)
                listOf("The Link stems could not be saved: ${e.message}")
            }
            if (notes.isNotEmpty()) warning = (listOfNotNull(warning) + notes).joinToString("\n\n")
        }

        if (audio != null && tl != null) {
            val (skip, len) = tl.audioRange(audio.startPos, video.lastPtsUs + FRAME_US)
            if (keepWav) saveWav(name, wav, skip, len)
            // every attempt but the last is dropped, and the last is kept
            var attempt: ParallelAac? = null
            var n = 0
            report = Mastering.run(rate, master, { RangeReader(wav, skip, len) }, { ParallelAac(dir, n++, rate, len).also { attempt = it } }, frames = len) {
                progress(it * MASTER_SHARE)
            }
            encoded = attempt!!.await { progress(MASTER_SHARE + it * ENCODE_SHARE) }
            // a lost waveform only costs the viewer a decode
            peaksDir?.let { d -> runCatching { io.github.dimadake.schwungcam.viewer.PeakCache.saveMaster(d, name, attempt!!.peaks.build()) } }
            val w = if (audio.endedEarly) "The Move restarted during the take; audio stops there."
                else if (audio.gapFrames > rate / 10) "Audio had ${audio.gapFrames * 1000 / rate} ms of dropouts (filled with silence)." else null
            if (w != null) warning = listOfNotNull(warning, w).joinToString("\n\n")
        }

        val uri = insertVideo(name)
        resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
            remux(videoFile, encoded, tl, MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), video.lastPtsUs) {
                progress(MASTER_SHARE + ENCODE_SHARE + it * (1 - MASTER_SHARE - ENCODE_SHARE))
            }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        progress(1f)
        dir.deleteRecursively()
        return FinalResult(uri, name, report, warning)
    }

    /**
     * When finishing failed: the camera's video and the stream's audio as they
     * were recorded, unsynced and unmastered, so the take is not lost with the
     * cache. Best effort; returns what was saved.
     */
    fun rescue(dir: File, name: String): List<String> {
        val saved = ArrayList<String>()
        val video = File(dir, "video.mp4")
        if (video.length() > 0 && copyOut(video, "${name}_raw.mp4", "video/mp4", "Movies/SchwungCam", MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)))
            saved.add("Movies/SchwungCam/${name}_raw.mp4")
        val wav = File(dir, "audio.wav")
        if (wav.length() > 44 && copyOut(wav, "${name}_raw.wav", "audio/wav", "Music/SchwungCam", MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)))
            saved.add("Music/SchwungCam/${name}_raw.wav")
        return saved
    }

    private fun copyOut(src: File, display: String, mime: String, relPath: String, collection: Uri): Boolean = try {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, display)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, v) ?: error("MediaStore refused $display")
        try {
            resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 20) } }
        } catch (e: Exception) {
            resolver.delete(uri, null, null); throw e
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        true
    } catch (e: Exception) {
        Log.w("SchwungCam", "rescue $display", e); false
    }

    /** The camera's frames placed on the audio clock, interleaved with the AAC frames by time. */
    private fun remux(videoFile: File, audio: EncodedAudio?, tl: Timeline?, mux: MediaMuxer, lastPtsUs: Long, progress: (Float) -> Unit) {
        val vx = MediaExtractor().apply { setDataSource(videoFile.path) }
        vx.selectTrack(0)
        val vt = mux.addTrack(vx.getTrackFormat(0))
        val at = audio?.let { mux.addTrack(it.format) } ?: -1
        mux.start()
        val vbuf = ByteBuffer.allocateDirect(8 shl 20)
        val abuf = ByteBuffer.allocateDirect(64 shl 10)
        val info = MediaCodec.BufferInfo()
        val ar = audio?.Reader()
        try {
            var aPts = ar?.next(abuf) ?: Long.MAX_VALUE
            var vDone = false
            var told = 0L
            while (!vDone || aPts != Long.MAX_VALUE) {
                val vPts = if (vDone) Long.MAX_VALUE else (tl?.videoPtsUs(vx.sampleTime) ?: vx.sampleTime)
                if (vPts <= aPts) {
                    info.size = vx.readSampleData(vbuf, 0)
                    if (info.size < 0) { vDone = true; continue }
                    info.offset = 0
                    info.presentationTimeUs = vPts
                    info.flags = if (vx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    mux.writeSampleData(vt, vbuf, info)
                    if (vx.sampleTime - told >= 5_000_000 && lastPtsUs > 0) { told = vx.sampleTime; progress(minOf(1f, told.toFloat() / lastPtsUs)) }
                    if (!vx.advance()) vDone = true
                } else {
                    info.set(0, abuf.limit(), aPts, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    mux.writeSampleData(at, abuf, info)
                    aPts = ar!!.next(abuf) ?: Long.MAX_VALUE
                }
            }
        } finally {
            ar?.close()
        }
        mux.stop(); mux.release()
        vx.release()
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

    /**
     * Beside the take in name, under Documents/: MediaStore takes no zip under
     * Movies/, which is for video only.
     */
    private fun saveAi(name: String, durationSec: Double, ai: AiTake, toSec: (Long) -> Double) {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.ai.zip")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/SchwungCam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v) ?: error("MediaStore refused the file")
        try {
            resolver.openOutputStream(uri)!!.use { AiExport.write(it, name, durationSec, ai, toSec) }
        } catch (e: Exception) {
            resolver.delete(uri, null, null); throw e
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    /**
     * Each stem as `Music/SchwungCam/<take>/<channel>.wav`, 24-bit, from the
     * video's first frame to its last, so all of them line up at 0 in a DAW.
     * Returns what the take's result should say about them.
     */
    private fun saveStems(name: String, take: StemsTake, durationSec: Double, toSec: (Long) -> Double): List<String> {
        val notes = ArrayList<String>()
        val files = StemExport.fileNames(take.stems.map { it.peer to it.name })
        take.stems.forEachIndexed { i, st ->
            val (skip, len) = StemExport.range(toSec(st.anchorNs), st.rate, durationSec)
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "${files[i]}.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SchwungCam/$name")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v) ?: error("MediaStore refused ${files[i]}.wav")
            try {
                resolver.openOutputStream(uri)!!.use { StemExport.write24(it, st.file, st.rate, skip, len) }
            } catch (e: Exception) {
                resolver.delete(uri, null, null); throw e
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            notes.addAll(stemNotes(st))
        }
        if (take.missing.isNotEmpty()) notes.add("No Link Audio arrived from: ${take.missing.joinToString(", ")}.")
        return notes
    }

    /** Seconds the Mirror audio ran late, from the Link reference channel; null if it cannot tell. */
    private fun measureTransit(wav: File, audio: TakeAudio, fit: io.github.dimadake.schwungcam.sync.ClockFit,
                               ref: io.github.dimadake.schwungcam.link.StemCapture): Double? {
        if (ref.rate != rate) return null
        val anchorSec = ref.anchorNs / 1e9
        val d = io.github.dimadake.schwungcam.sync.TransitMeter.measure(
            mirror = { s, n -> io.github.dimadake.schwungcam.sync.TransitMeter.readMono(wav, s, n) },
            ref = { s, n ->
                val at = ((fit.phoneSec((audio.startPos + s).toDouble() / rate) - anchorSec) * rate).let { Math.round(it) }
                io.github.dimadake.schwungcam.sync.TransitMeter.readMono(ref.file, at, n)
            },
            frames = audio.frames, rate = rate,
        )
        Log.i("SchwungCam", "transit from ${ref.peer}/${ref.name}: " + (d?.let { "%.1f ms".format(it * 1000) } ?: "not measured"))
        return d
    }

    /** What a stem's timing did, in words; nothing for a clean one. */
    internal fun stemNotes(st: io.github.dimadake.schwungcam.link.StemCapture): List<String> {
        val ms = { f: Long -> f * 1000 / st.rate }
        val parts = ArrayList<String>()
        if (ms(st.lostFrames) >= 10) parts.add("${ms(st.lostFrames)} ms of lost audio filled with silence")
        if (st.resyncs > 0) {
            val how = listOfNotNull(
                ms(st.gapFrames).takeIf { it > 0 }?.let { "$it ms of silence added" },
                ms(st.droppedFrames).takeIf { it > 0 }?.let { "$it ms of overlap removed" },
            ).joinToString(", ")
            parts.add("its timing jumped ${st.resyncs}×" + if (how.isEmpty()) "" else " ($how)")
        }
        return if (parts.isEmpty()) emptyList() else listOf("Link stem ${st.name}: ${parts.joinToString("; ")}.")
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

    companion object {
        const val FRAME_US = 33_333L
        // of the bar: mastering, then the encoders' tail, then the remux, which copies the whole video
        private const val MASTER_SHARE = 0.55f
        private const val ENCODE_SHARE = 0.05f
    }
}
