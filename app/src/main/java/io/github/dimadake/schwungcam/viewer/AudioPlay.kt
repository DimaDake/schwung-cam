package io.github.dimadake.schwungcam.viewer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Waveforms, worked out once per file and kept in the cache dir. */
object PeakCache {
    fun get(ctx: Context, row: MediaRow): Peaks? {
        val dir = File(ctx.cacheDir, "peaks").apply { mkdirs() }
        val f = File(dir, row.id.substringAfterLast('/') + "_" + row.id.hashCode().toUInt() + "_" + row.size + ".bin")
        if (f.exists()) f.inputStream().buffered().use { Peaks.read(it) }?.let { return it }
        val p = try {
            if (row.name.endsWith(".wav")) wav(ctx, row) else mp4(ctx, row)
        } catch (e: Exception) {
            Log.w("SchwungCam", "peaks ${row.name}", e); null
        } ?: return null
        runCatching { File(dir, f.name + ".tmp").also { t -> t.outputStream().buffered().use { p.write(it) }; t.renameTo(f) } }
        return p
    }

    private fun wav(ctx: Context, row: MediaRow): Peaks? {
        ctx.contentResolver.openFileDescriptor(row.uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val w = WavReader.info(ch) ?: return null
                val b = Peaks.Builder(w.rate)
                val frames = 16384
                val bytes = ByteArray(frames * w.frameBytes)
                val st = FloatArray(frames * 2)
                var pos = w.dataOffset
                val end = w.dataOffset + w.dataBytes
                val buf = ByteBuffer.wrap(bytes)
                while (pos < end) {
                    buf.clear(); buf.limit(minOf(bytes.size.toLong(), (end - pos) / w.frameBytes * w.frameBytes).toInt())
                    val n = ch.read(buf, pos)
                    if (n <= 0) break
                    val nf = w.toStereo(bytes, n - n % w.frameBytes, st)
                    for (i in 0 until nf) b.add((st[2 * i] + st[2 * i + 1]) * 0.5f)
                    pos += n
                }
                return b.build()
            }
        }
    }

    /** The video's own track, decoded to pcm. */
    private fun mp4(ctx: Context, row: MediaRow): Peaks? {
        val x = MediaExtractor()
        try {
            x.setDataSource(ctx, row.uri, null)
            val track = (0 until x.trackCount).firstOrNull { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            x.selectTrack(track)
            val fmt = x.getTrackFormat(track)
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            val b = Peaks.Builder(rate)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var float = false
            try {
                while (true) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = x.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(i, 0, n, x.sampleTime, 0); x.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        val ob = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        if (float) {
                            val fb = ob.asFloatBuffer()
                            while (fb.remaining() >= channels) { var s = 0f; repeat(channels) { s += fb.get() }; b.add(s / channels) }
                        } else {
                            val sb = ob.asShortBuffer()
                            while (sb.remaining() >= channels) { var s = 0f; repeat(channels) { s += sb.get() / 32768f }; b.add(s / channels) }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                codec.stop(); codec.release()
            }
            return b.build()
        } finally {
            x.release()
        }
    }
}

object WavReader {
    fun info(ch: FileChannel): WavInfo? {
        val h = ByteBuffer.allocate(64 * 1024)
        ch.read(h, 0)
        return WavInfo.parse(h.array().copyOf(h.position()), ch.size())
    }
}

/**
 * Plays WAVs that start at the video's first frame, mixed with solo/mute
 * gains, through an AudioTrack. The video is the clock: the screen calls
 * [seek] when [positionMs] drifts from it.
 */
class MixPlayer(ctx: Context, rows: List<MediaRow>) {
    private class Input(val pfd: ParcelFileDescriptor, val ch: FileChannel, val w: WavInfo)

    private val inputs: List<Input?>
    val rate: Int
    @Volatile var gains = FloatArray(rows.size) { 1f }
    private val lock = Object()
    @Volatile private var playing = false
    @Volatile private var seekTo = -1L
    @Volatile private var dead = false
    @Volatile private var base = 0L
    @Volatile private var headAtBase = 0L
    private val track: AudioTrack
    private val thread: Thread

    init {
        val opened = rows.map { r ->
            runCatching {
                val pfd = ctx.contentResolver.openFileDescriptor(r.uri, "r")!!
                val ch = FileInputStream(pfd.fileDescriptor).channel
                WavReader.info(ch)?.let { Input(pfd, ch, it) } ?: run { pfd.close(); null }
            }.getOrNull()
        }
        rate = opened.firstNotNullOfOrNull { it }?.w?.rate ?: 44100
        // stems of another rate would play at the wrong speed: leave them out
        inputs = opened.map { i -> if (i != null && i.w.rate != rate) { i.pfd.close(); null } else i }
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min, rate / 8 * 8)).setTransferMode(AudioTrack.MODE_STREAM).build()
        thread = Thread(::loop, "mix").apply { start() }
    }

    val missing: Int get() = inputs.count { it == null }

    fun play(atMs: Long) = synchronized(lock) { seekTo = atMs; playing = true; lock.notifyAll() }
    fun pause() = synchronized(lock) { playing = false; track.pause(); lock.notifyAll() }
    fun seek(ms: Long) = synchronized(lock) { seekTo = ms; lock.notifyAll() }

    fun positionMs(): Long = (base + (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) - headAtBase) * 1000 / rate

    fun release() {
        synchronized(lock) { dead = true; lock.notifyAll() }
        thread.join(500)
        track.release()
        inputs.forEach { runCatching { it?.pfd?.close() } }
    }

    private fun loop() {
        val n = 2048
        val acc = FloatArray(n * 2)
        val tmp = FloatArray(n * 2)
        val bytes = ByteArray(n * 8 * 4)
        var cursor = 0L
        while (true) {
            synchronized(lock) {
                while (!dead && !playing && seekTo < 0) lock.wait()
                if (dead) return
                if (seekTo >= 0) {
                    track.pause(); track.flush()
                    cursor = seekTo * rate / 1000
                    base = cursor; headAtBase = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    seekTo = -1
                    if (playing) track.play()
                }
            }
            if (!playing) continue
            java.util.Arrays.fill(acc, 0f)
            var any = false
            val g = gains
            inputs.forEachIndexed { i, inp ->
                if (inp == null || g.getOrElse(i) { 0f } == 0f) return@forEachIndexed
                val w = inp.w
                val at = w.dataOffset + cursor * w.frameBytes
                val want = minOf(n.toLong(), w.frames - cursor).toInt()
                if (want <= 0) return@forEachIndexed
                any = true
                val bb = ByteBuffer.wrap(bytes, 0, want * w.frameBytes)
                var got = 0
                while (bb.hasRemaining()) { val k = inp.ch.read(bb, at + got); if (k <= 0) break; got += k }
                val nf = w.toStereo(bytes, got - got % w.frameBytes, tmp)
                StemMix.add(acc, tmp, g[i], nf * 2)
            }
            if (!any && inputs.all { it == null || cursor >= it.w.frames }) {
                synchronized(lock) { if (seekTo < 0) playing = false }
                continue
            }
            for (i in acc.indices) acc[i] = StemMix.soft(acc[i])
            // not blocking: a paused track would hold a blocking write, and with it the next seek
            var off = 0
            while (off < acc.size && playing && seekTo < 0 && !dead) {
                val k = track.write(acc, off, acc.size - off, AudioTrack.WRITE_NON_BLOCKING)
                if (k < 0) break
                off += k
                if (off < acc.size) Thread.sleep(5)
            }
            cursor += off / 2
        }
    }
}
