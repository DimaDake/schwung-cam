package io.github.dimadake.schwungcam.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import io.github.dimadake.schwungcam.dsp.FrameSink
import java.io.File
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Float stereo → AAC-LC in an .m4a, synchronously. PTS come from the sample
 * count, so the track is exactly as long as the PCM it was given.
 */
class AacEncoder(out: File, private val rate: Int, bitrate: Int = 384_000) : FrameSink {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var fed = 0L
    private val info = MediaCodec.BufferInfo()
    private var seed = 1
    private var pcm = ShortArray(0)
    private var inIdx = -1
    private var inBuf: ShortBuffer? = null
    private var eosSeen = false

    init {
        val br = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
            .audioCapabilities?.bitrateRange?.clamp(bitrate) ?: bitrate
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, br)
            // 64 KB in a buffer: the encoder runs in another process, and each buffer is a
            // round trip; 16 KB ran a 24 min take at about half the speed
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun write(l: FloatArray, r: FloatArray, n: Int) {
        if (pcm.size != n * 2) pcm = ShortArray(n * 2)
        for (i in 0 until n) { pcm[2 * i] = dither(l[i]); pcm[2 * i + 1] = dither(r[i]) }
        queue(pcm, false)
    }

    /** TPDF dither at 16 bits: the master is float, the encoder input is int16. */
    private fun dither(x: Float): Short {
        val d = uniform() - uniform()
        val v = Math.round(x * 32767f + d).coerceIn(-32768, 32767)
        return v.toShort()
    }

    /** [0, 1) from a 32-bit LCG: dither needs no better, and it runs twice per sample. */
    private fun uniform(): Float {
        seed = seed * 1664525 + 1013904223
        return (seed ushr 8) * (1f / (1 shl 24))
    }

    /**
     * Fills codec input buffers to the brim before handing them over: the
     * encoder runs in another process, so every buffer is a round trip, and
     * Mastering writes in blocks a quarter of one.
     */
    private fun queue(pcm: ShortArray, end: Boolean) {
        var off = 0
        while (off < pcm.size) {
            val b = input()
            val m = minOf(pcm.size - off, b.remaining()) and 1.inv()
            b.put(pcm, off, m)
            off += m
            if (b.remaining() < 2) submit(false)
        }
        if (end) { input(); submit(true) }
    }

    /** The input buffer being filled, taking a free one when there is none. */
    private fun input(): ShortBuffer {
        inBuf?.let { return it }
        while (true) {
            // a full input queue frees only as output is taken: wait on the output, not the input
            drain(false)
            val i = codec.dequeueInputBuffer(0)
            if (i < 0) { drainOne(); continue }
            val bb = codec.getInputBuffer(i)!!
            bb.clear()
            inIdx = i
            // codec buffers are native (little-endian) PCM; ByteBuffer defaults to big-endian
            return bb.order(ByteOrder.nativeOrder()).asShortBuffer().also { inBuf = it }
        }
    }

    private fun submit(eos: Boolean) {
        val n = inBuf!!.position()
        codec.queueInputBuffer(inIdx, 0, n * 2, fed * 1_000_000 / rate, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
        fed += n / 2
        inIdx = -1; inBuf = null
        drain(false)
    }

    private fun drain(untilEos: Boolean) {
        while (!eosSeen) {
            val o = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!
                    if (info.size > 0 && track >= 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) muxer.writeSampleData(track, buf, info)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosSeen = true
                }
            }
        }
    }

    /** Waits briefly for one output buffer and takes it, so a stalled input can move. */
    private fun drainOne() {
        val o = codec.dequeueOutputBuffer(info, 2_000)
        when {
            o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
            o >= 0 -> {
                val buf = codec.getOutputBuffer(o)!!
                if (info.size > 0 && track >= 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) muxer.writeSampleData(track, buf, info)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosSeen = true
            }
        }
    }

    fun finish() {
        queue(ShortArray(0), true)
        drain(true)
        codec.stop(); codec.release()
        if (track >= 0) muxer.stop()
        muxer.release()
    }
}
