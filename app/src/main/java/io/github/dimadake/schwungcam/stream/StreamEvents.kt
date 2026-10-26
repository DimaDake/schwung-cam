package io.github.dimadake.schwungcam.stream

import io.github.dimadake.schwungcam.ai.SurfaceSnapshot
import java.util.Base64

const val SCREEN_W = 128
const val SCREEN_H = 64

/** The Move screen as 128×64 luminance bytes (0 or 255 for mono1). */
class ScreenFrame(val lum: ByteArray, val arrivalNs: Long)

/** [pcm] is int16 stereo interleaved; [pos] is the device frame of its first sample. */
class PcmChunk(val pos: Long, val n: Int, val rate: Int, val pcm: ShortArray, val arrivalNs: Long) {
    val endPos: Long get() = pos + n
}

/**
 * Just enough JSON for display-server's flat objects. android's org.json is a
 * stub under JVM unit tests, and these payloads never nest.
 */
internal object FlatJson {
    fun parse(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = s.indexOf('{') + 1
        if (i <= 0) return out
        while (i < s.length) {
            while (i < s.length && (s[i] == ',' || s[i].isWhitespace())) i++
            if (i >= s.length || s[i] == '}' || s[i] != '"') break
            val kEnd = s.indexOf('"', i + 1)
            if (kEnd < 0) break
            val key = s.substring(i + 1, kEnd)
            i = s.indexOf(':', kEnd) + 1
            if (i <= 0) break
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break
            if (s[i] == '"') {
                // base64 and format names carry no escapes
                val vEnd = s.indexOf('"', i + 1)
                if (vEnd < 0) break
                out[key] = s.substring(i + 1, vEnd)
                i = vEnd + 1
            } else {
                var e = i
                while (e < s.length && s[e] != ',' && s[e] != '}') e++
                out[key] = s.substring(i, e).trim()
                i = e
            }
        }
        return out
    }
}

object ScreenDecoder {
    fun decode(format: String, raw: ByteArray): ByteArray? {
        val out = ByteArray(SCREEN_W * SCREEN_H)
        if (format.startsWith("gray4")) {
            if (raw.size < SCREEN_W * SCREEN_H / 2) return null
            for (y in 0 until SCREEN_H) for (x in 0 until SCREEN_W step 2) {
                val b = raw[y * (SCREEN_W / 2) + (x shr 1)].toInt() and 0xFF
                out[y * SCREEN_W + x] = (((b shr 4) and 15) * 17).toByte()
                out[y * SCREEN_W + x + 1] = ((b and 15) * 17).toByte()
            }
        } else {
            if (raw.size < SCREEN_W * SCREEN_H / 8) return null
            for (page in 0 until 8) for (col in 0 until SCREEN_W) {
                val b = raw[page * SCREEN_W + col].toInt()
                for (bit in 0 until 8) {
                    out[(page * 8 + bit) * SCREEN_W + col] = if ((b shr bit) and 1 != 0) 0xFF.toByte() else 0
                }
            }
        }
        return out
    }
}

object StreamEvents {
    private val b64 = Base64.getDecoder()

    /** Returns a [ScreenFrame], a [PcmChunk], a [SurfaceSnapshot], or null for anything else (hb, e16…). */
    fun decode(e: SseEvent): Any? = when (e.name) {
        "message" -> {
            val m = FlatJson.parse(e.data)
            val d = m["data"]
            if (d == null) null else ScreenDecoder.decode(m["format"] ?: "mono1_packed", b64.decode(d))
                ?.let { ScreenFrame(it, e.arrivalNs) }
        }
        "pcm" -> {
            val m = FlatJson.parse(e.data)
            val pos = m["pos"]?.toLongOrNull()
            val d = m["d"]
            if (pos == null || d == null) null else {
                val raw = b64.decode(d)
                val n = raw.size / 4
                val pcm = ShortArray(n * 2)
                for (i in 0 until n * 2) {
                    pcm[i] = ((raw[2 * i].toInt() and 0xFF) or (raw[2 * i + 1].toInt() shl 8)).toShort()
                }
                if (n == 0) null else PcmChunk(pos, n, m["rate"]?.toIntOrNull() ?: 44100, pcm, e.arrivalNs)
            }
        }
        "surface" -> {
            val m = FlatJson.parse(e.data)
            val d = m["data"]
            val frame = m["frame"]?.toLongOrNull()
            if (d == null || frame == null || m["v"] != "1") null else SurfaceSnapshot.decode(frame, b64.decode(d), e.arrivalNs)
        }
        else -> null
    }
}
