package io.github.dimadake.schwungcam.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import io.github.dimadake.schwungcam.dsp.FrameSink
import java.io.File
import kotlin.random.Random

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
    private val rnd = Random(1)
    private var eosSeen = false

    init {
        val br = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
            .audioCapabilities?.bitrateRange?.clamp(bitrate) ?: bitrate
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, br)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun write(l: FloatArray, r: FloatArray, n: Int) {
        val pcm = ShortArray(n * 2)
        for (i in 0 until n) { pcm[2 * i] = dither(l[i]); pcm[2 * i + 1] = dither(r[i]) }
        queue(pcm, false)
    }

    /** TPDF dither at 16 bits: the master is float, the encoder input is int16. */
    private fun dither(x: Float): Short {
        val d = (rnd.nextFloat() - rnd.nextFloat())
        val v = Math.round(x * 32767f + d).coerceIn(-32768, 32767)
        return v.toShort()
    }

    private fun queue(pcm: ShortArray, end: Boolean) {
        var off = 0
        while (off < pcm.size || end) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i < 0) { drain(false); continue }
            val buf = codec.getInputBuffer(i)!!
            buf.clear()
            val m = minOf((pcm.size - off), buf.capacity() / 2) and 1.inv()
            // codec buffers are native (little-endian) PCM; ByteBuffer defaults to big-endian
            buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().put(pcm, off, m)
            val pts = fed * 1_000_000 / rate
            fed += m / 2
            off += m
            val last = end && off >= pcm.size
            codec.queueInputBuffer(i, 0, m * 2, pts, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            drain(false)
            if (last) break
        }
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

    fun finish() {
        queue(ShortArray(0), true)
        drain(true)
        codec.stop(); codec.release()
        if (track >= 0) muxer.stop()
        muxer.release()
    }
}
