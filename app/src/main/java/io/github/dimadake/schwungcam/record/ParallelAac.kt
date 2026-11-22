package io.github.dimadake.schwungcam.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import io.github.dimadake.schwungcam.dsp.Mastering
import io.github.dimadake.schwungcam.dsp.MasterSink
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

/** A stretch of the take one encoder is given, and the frames it keeps of what it makes. */
data class Chunk(val index: Int, val keepFrom: Long, val keepTo: Long, val inFrom: Long, val inTo: Long)

/**
 * How a take's audio is cut for AAC encoders running side by side. Cuts fall
 * on AAC frame boundaries. Each encoder starts [PRE] frames before its cut, so
 * its state has settled on the music by then, and runs [POST] frames past its
 * end, so the frames before the next cut are whole; it keeps frames
 * [Chunk.keepFrom, Chunk.keepTo) only. The last keeps everything to the end.
 */
object ChunkPlan {
    const val FRAME = 1024
    const val PRE = 8
    const val POST = 4
    /** Shorter chunks cost more in start-up than they win. */
    const val MIN_CHUNK_SEC = 60

    fun plan(total: Long, rate: Int, maxChunks: Int): List<Chunk> {
        val frames = (total + FRAME - 1) / FRAME
        val k = (total / (MIN_CHUNK_SEC.toLong() * rate)).toInt().coerceIn(1, maxChunks)
        return (0 until k).map { i ->
            val from = frames * i / k
            val last = i == k - 1
            val to = if (last) Long.MAX_VALUE else frames * (i + 1) / k
            Chunk(i, from, to, maxOf(0L, from - PRE) * FRAME, if (last) total else minOf(total, (to + POST) * FRAME))
        }
    }

    /** The AAC frame an output timestamp stands for: encoders stamp frames from the input's, which are on the take's sample clock. */
    fun frameAt(ptsUs: Long, rate: Int): Long = Math.round(ptsUs * rate / 1e6 / FRAME)

    /** Whether frames numbered so, chunk after chunk, run on with neither gap nor repeat. */
    fun joins(frames: List<LongArray>): Boolean {
        var next = -1L
        for (c in frames) for (f in c) {
            if (next >= 0 && f != next) return false
            next = f + 1
        }
        return next >= 0
    }
}

/**
 * An attempt at the master as dithered 16-bit stereo pcm in [file], which
 * the encoders read while it grows.
 */
class PcmMaster(val file: File) : MasterSink {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 20)
    private var bytes = ByteArray(Mastering.BLOCK * 4)
    private val lock = Object()
    private var buffered = 0L
    private var seed = 1
    /** Frames on disk, safe to read. */
    private var readable = 0L
    private var state = WRITING

    override fun write(l: FloatArray, r: FloatArray, n: Int) {
        if (bytes.size < n * 4) bytes = ByteArray(n * 4)
        var o = 0
        for (i in 0 until n) {
            val a = dither(l[i]); val b = dither(r[i])
            bytes[o] = a.toByte(); bytes[o + 1] = (a shr 8).toByte()
            bytes[o + 2] = b.toByte(); bytes[o + 3] = (b shr 8).toByte()
            o += 4
        }
        out.write(bytes, 0, o)
        buffered += n
        if (buffered - readable >= PUBLISH_FRAMES) publish()
    }

    /** TPDF dither at 16 bits: the master is float, the encoder input is int16. */
    private fun dither(x: Float): Int = Math.round(x * 32767f + uniform() - uniform()).coerceIn(-32768, 32767)

    /** [0, 1) from a 32-bit LCG: dither needs no better, and it runs twice per sample. */
    private fun uniform(): Float {
        seed = seed * 1664525 + 1013904223
        return (seed ushr 8) * (1f / (1 shl 24))
    }

    private fun publish() {
        out.flush()
        synchronized(lock) { readable = buffered; lock.notifyAll() }
    }

    override fun keep() {
        out.close()
        synchronized(lock) { readable = buffered; state = KEPT; lock.notifyAll() }
    }

    override fun drop() {
        try { out.close() } catch (_: Exception) {}
        synchronized(lock) { state = DROPPED; lock.notifyAll() }
    }

    /**
     * Waits until frames up to [upTo] can be read, or the master is complete
     * short of them; returns how far it can be read. Throws when dropped.
     */
    fun await(upTo: Long): Long = synchronized(lock) {
        while (state == WRITING && readable < upTo) lock.wait()
        if (state == DROPPED) throw CancellationException("attempt dropped")
        minOf(readable, upTo)
    }

    companion object {
        private const val WRITING = 0
        private const val KEPT = 1
        private const val DROPPED = 2
        /** About a second of audio: an encoder waits no longer than that behind the mastering. */
        private const val PUBLISH_FRAMES = 44100L
    }
}

/** The take's AAC: the encoder's format and its frames, in order, in [files]. */
class EncodedAudio(val format: MediaFormat, private val files: List<File>) {
    /** Reads the frames one after another into a buffer; null at the end. */
    inner class Reader : AutoCloseable {
        private var i = 0
        private var cur: DataInputStream? = null
        private var bytes = ByteArray(8192)

        fun next(buf: ByteBuffer): Long? {
            while (true) {
                val s = cur ?: run {
                    if (i >= files.size) return null
                    DataInputStream(BufferedInputStream(files[i++].inputStream(), 1 shl 16)).also { cur = it }
                }
                val pts = try { s.readLong() } catch (e: EOFException) { s.close(); cur = null; continue }
                val n = s.readInt()
                if (bytes.size < n) bytes = ByteArray(n)
                s.readFully(bytes, 0, n)
                buf.clear(); buf.put(bytes, 0, n); buf.flip()
                return pts
            }
        }

        override fun close() { cur?.close() }
    }
}

/**
 * A mastering attempt encoded to AAC while it is written: the master goes to
 * a [PcmMaster] in [dir], and one encoder per [Chunk] reads its stretch as
 * soon as it is there. The phone has only a software AAC encoder, in another
 * process, so a few side by side finish in a fraction of the time of one.
 */
class ParallelAac(
    private val dir: File,
    private val attempt: Int,
    private val rate: Int,
    private val total: Long,
    private val bitrate: Int = 384_000,
    maxChunks: Int = MAX_CHUNKS,
) : MasterSink {
    private val pcm = PcmMaster(File(dir, "master-$attempt.pcm"))
    private val plan = ChunkPlan.plan(total, rate, maxChunks)
    private val jobs = plan.map { c -> Job(c, File(dir, "aac-$attempt-${c.index}.bin")) }

    private inner class Job(val chunk: Chunk, val file: File) {
        @Volatile var format: MediaFormat? = null
        @Volatile var error: Throwable? = null
        var frames = LongArray(0)
        val thread = thread(name = "aac-$attempt-${chunk.index}") {
            try { frames = encode(chunk, file) { if (chunk.index == 0) format = it } }
            catch (e: CancellationException) {}
            catch (e: Throwable) { error = e }
        }
    }

    /** The master's waveform, for the take viewer: made here it costs a min/max per frame, against decoding the AAC later. */
    val peaks = io.github.dimadake.schwungcam.viewer.Peaks.Builder(rate)

    override fun write(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) peaks.add((l[i] + r[i]) * 0.5f)
        pcm.write(l, r, n)
    }
    override fun keep() = pcm.keep()

    override fun drop() {
        pcm.drop()
        jobs.forEach { it.thread.join() }
        jobs.forEach { it.file.delete() }
        pcm.file.delete()
    }

    /** After [keep]: waits for the encoders and returns what they made, as one stream. */
    fun await(progress: (Float) -> Unit = {}): EncodedAudio {
        jobs.forEachIndexed { i, j -> j.thread.join(); progress((i + 1f) / jobs.size) }
        jobs.firstNotNullOfOrNull { it.error }?.let { throw it }
        val format = jobs[0].format ?: error("the AAC encoder gave no format")
        if (jobs.size == 1 || ChunkPlan.joins(jobs.map { it.frames })) {
            pcm.file.delete()
            return EncodedAudio(format, jobs.map { it.file })
        }
        // the encoders did not line up on the take's clock: one encoder, as before chunks
        Log.w("SchwungCam", "AAC chunks do not join (" + jobs.joinToString { "${it.frames.firstOrNull()}..${it.frames.lastOrNull()}" } + "); encoding in one")
        jobs.forEach { it.file.delete() }
        val one = File(dir, "aac-$attempt-whole.bin")
        var f: MediaFormat? = null
        encode(ChunkPlan.plan(total, rate, 1)[0], one) { f = it }
        pcm.file.delete()
        return EncodedAudio(f ?: error("the AAC encoder gave no format"), listOf(one))
    }

    /**
     * Encodes [c]'s stretch of the pcm into [out] as (pts, size, bytes)
     * records, keeping only the frames [c] keeps; returns their numbers.
     */
    private fun encode(c: Chunk, out: File, onFormat: (MediaFormat) -> Unit): LongArray {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val kept = ArrayList<Long>()
        try {
            val br = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                .audioCapabilities?.bitrateRange?.clamp(bitrate) ?: bitrate
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, br)
                // the encoder is another process and each buffer a round trip: few, large ones
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            FileChannel.open(pcm.file.toPath(), StandardOpenOption.READ).use { ch ->
                DataOutputStream(BufferedOutputStream(FileOutputStream(out), 1 shl 16)).use { rec ->
                    val info = MediaCodec.BufferInfo()
                    var bytes = ByteArray(8192)
                    var pos = c.inFrom
                    var inDone = false
                    var outDone = false
                    while (!outDone) {
                        if (!inDone) {
                            val i = codec.dequeueInputBuffer(0)
                            if (i >= 0) {
                                val buf = codec.getInputBuffer(i)!!
                                buf.clear()
                                val want = minOf((buf.capacity() / 4).toLong(), c.inTo - pos)
                                val upTo = pcm.await(pos + want)
                                val n = maxOf(0L, minOf(upTo, c.inTo) - pos)
                                buf.limit((n * 4).toInt())
                                var at = pos * 4
                                while (buf.hasRemaining()) { val k = ch.read(buf, at); if (k < 0) break; at += k }
                                val pts = pos * 1_000_000 / rate
                                pos += n
                                // less than asked only when the master came out shorter than planned: end there
                                inDone = pos >= c.inTo || n < want
                                codec.queueInputBuffer(i, 0, buf.position(), pts, if (inDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            }
                        }
                        val o = codec.dequeueOutputBuffer(info, if (inDone) 10_000 else 1_000)
                        when {
                            o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                            o >= 0 -> {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val f = ChunkPlan.frameAt(info.presentationTimeUs, rate)
                                    if (f >= c.keepFrom && f < c.keepTo) {
                                        val b = codec.getOutputBuffer(o)!!
                                        b.position(info.offset); b.limit(info.offset + info.size)
                                        if (bytes.size < info.size) bytes = ByteArray(info.size)
                                        b.get(bytes, 0, info.size)
                                        rec.writeLong(info.presentationTimeUs); rec.writeInt(info.size); rec.write(bytes, 0, info.size)
                                        kept.add(f)
                                    }
                                }
                                codec.releaseOutputBuffer(o, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                            }
                        }
                    }
                }
            }
            codec.stop()
        } finally {
            codec.release()
        }
        return kept.toLongArray()
    }

    companion object {
        /** The phone's cores beside the mastering thread and the encoders' own process. */
        const val MAX_CHUNKS = 4
    }
}
