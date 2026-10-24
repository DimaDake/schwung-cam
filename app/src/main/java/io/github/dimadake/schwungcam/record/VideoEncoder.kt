package io.github.dimadake.schwungcam.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VideoResult(val firstFrameNs: Long, val lastPtsUs: Long, val frames: Int)

/**
 * H.264 from a surface into a video-only temp MP4. PTS in the file are
 * microseconds since the first frame; [VideoResult.firstFrameNs] keeps the
 * absolute sensor time so the finalizer can put the frames on the audio clock.
 */
class VideoEncoder(private val out: File, val width: Int, val height: Int, bitrate: Int) {
    private val thread = HandlerThread("venc").apply { start() }
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var firstUs = -1L
    private var lastUs = 0L
    private var frames = 0
    private val eos = CountDownLatch(1)

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val caps = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        caps.profileLevels.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
            .maxByOrNull { it.level }?.let {
                fmt.setInteger(MediaFormat.KEY_PROFILE, it.profile)
                fmt.setInteger(MediaFormat.KEY_LEVEL, it.level)
            }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, i: Int) {}
            override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {
                val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                track = m.addTrack(f); m.start(); muxer = m
            }
            override fun onOutputBufferAvailable(c: MediaCodec, i: Int, info: MediaCodec.BufferInfo) {
                val buf = c.getOutputBuffer(i)
                val m = muxer
                if (buf != null && m != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    if (firstUs < 0) firstUs = info.presentationTimeUs
                    val raw = info.presentationTimeUs
                    info.presentationTimeUs = raw - firstUs
                    m.writeSampleData(track, buf, info)
                    lastUs = info.presentationTimeUs
                    frames++
                }
                c.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos.countDown()
            }
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { eos.countDown() }
        }, Handler(thread.looper))
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    /** Call after the renderer stopped drawing into [inputSurface]. */
    fun finish(): VideoResult? {
        try { codec.signalEndOfInputStream() } catch (_: Exception) { }
        eos.await(5, TimeUnit.SECONDS)
        try { codec.stop() } catch (_: Exception) { }
        codec.release(); inputSurface.release()
        try { muxer?.stop() } catch (_: Exception) { }
        muxer?.release()
        thread.quitSafely()
        return if (frames == 0) null else VideoResult(firstUs * 1000, lastUs, frames)
    }
}
