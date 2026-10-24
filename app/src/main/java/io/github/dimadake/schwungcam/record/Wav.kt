package io.github.dimadake.schwungcam.record

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** 16-bit stereo PCM WAV; sizes are patched in on close. */
class WavWriter(private val file: File, private val rate: Int = 44100) : PcmSink {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var dataBytes = 0L
    private val buf = ByteArray(16 * 1024)

    init { out.write(header(0)) }

    override fun write(pcm: ShortArray, offFrames: Int, frames: Int) {
        var i = offFrames * 2
        val end = (offFrames + frames) * 2
        while (i < end) {
            val m = minOf(end - i, buf.size / 2)
            for (k in 0 until m) {
                val v = pcm[i + k].toInt()
                buf[2 * k] = v.toByte(); buf[2 * k + 1] = (v shr 8).toByte()
            }
            out.write(buf, 0, m * 2)
            i += m
        }
        dataBytes += frames * 4L
    }

    override fun silence(frames: Long) {
        java.util.Arrays.fill(buf, 0)
        var left = frames * 4
        while (left > 0) {
            val m = minOf(left, buf.size.toLong()).toInt()
            out.write(buf, 0, m); left -= m
        }
        dataBytes += frames * 4
    }

    override fun close() {
        out.close()
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(header(dataBytes)) }
    }

    private fun header(data: Long): ByteArray {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val d = minOf(data, 0xFFFFFFFFL - 36).toInt()
        h.put("RIFF".toByteArray()).putInt(36 + d).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate)
            .putInt(rate * 4).putShort(4).putShort(16)
        h.put("data".toByteArray()).putInt(d)
        return h.array()
    }
}

/** Sequential float reader for the WAVs [WavWriter] makes (44-byte header). */
class WavReader(file: File, startFrame: Long = 0) : Closeable {
    private val input = BufferedInputStream(FileInputStream(file), 1 shl 16)
    private val raw = ByteArray(4 * 4096)
    val frames: Long = (file.length() - 44) / 4

    init {
        var skip = 44 + startFrame * 4
        while (skip > 0) { val s = input.skip(skip); if (s <= 0) break; skip -= s }
    }

    /** Fills [l]/[r] with up to [max] frames; returns the count, 0 at the end. */
    fun read(l: FloatArray, r: FloatArray, max: Int): Int {
        var got = 0
        while (got < max) {
            val want = minOf(max - got, raw.size / 4) * 4
            var n = 0
            while (n < want) { val k = input.read(raw, n, want - n); if (k < 0) break; n += k }
            val f = n / 4
            for (i in 0 until f) {
                val o = i * 4
                l[got + i] = ((raw[o].toInt() and 0xFF) or (raw[o + 1].toInt() shl 8)).toShort() / 32768f
                r[got + i] = ((raw[o + 2].toInt() and 0xFF) or (raw[o + 3].toInt() shl 8)).toShort() / 32768f
            }
            got += f
            if (n < want) break
        }
        return got
    }

    override fun close() = input.close()
}
