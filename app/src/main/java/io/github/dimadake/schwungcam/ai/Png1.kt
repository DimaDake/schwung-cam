package io.github.dimadake.schwungcam.ai

import io.github.dimadake.schwungcam.stream.SCREEN_H
import io.github.dimadake.schwungcam.stream.SCREEN_W
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** A black and white picture, 1 = white. */
class Bitmap1(val w: Int, val h: Int) {
    private val stride = (w + 7) / 8
    val rows = ByteArray(stride * h)

    operator fun set(x: Int, y: Int, on: Boolean) {
        if (x !in 0 until w || y !in 0 until h) return
        val i = y * stride + (x shr 3)
        val m = 0x80 ushr (x and 7)
        rows[i] = (if (on) rows[i].toInt() or m else rows[i].toInt() and m.inv()).toByte()
    }

    operator fun get(x: Int, y: Int) = (rows[y * stride + (x shr 3)].toInt() and (0x80 ushr (x and 7))) != 0

    fun fill(x: Int, y: Int, fw: Int, fh: Int, on: Boolean) { for (yy in y until y + fh) for (xx in x until x + fw) this[xx, yy] = on }

    /** The Move screen at [scale]×, white on black as the device shows it. */
    fun blit(bits: ByteArray, x0: Int, y0: Int, scale: Int) {
        for (y in 0 until SCREEN_H) for (x in 0 until SCREEN_W) if (Mono.lit(bits, x, y)) fill(x0 + x * scale, y0 + y * scale, scale, scale, true)
    }

    companion object {
        fun screen(bits: ByteArray, scale: Int) = Bitmap1(SCREEN_W * scale, SCREEN_H * scale).apply { blit(bits, 0, 0, scale) }
    }
}

/**
 * PNG at bit depth 1, greyscale. A 4× Move screen is 1-3 KB this way; an
 * 8-bit RGBA one from Bitmap.compress is several times that, for the same picture.
 */
object Png1 {
    fun encode(b: Bitmap1): ByteArray {
        val stride = (b.w + 7) / 8
        val raw = ByteArray((stride + 1) * b.h)
        for (y in 0 until b.h) System.arraycopy(b.rows, y * stride, raw, y * (stride + 1) + 1, stride)   // filter 0
        val z = Deflater(9)
        z.setInput(raw); z.finish()
        val idat = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!z.finished()) idat.write(buf, 0, z.deflate(buf))
        z.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(b.w); writeInt(b.h); write(1); write(0); write(0); write(0); write(0) } }
        chunk(out, "IHDR", ihdr.toByteArray())
        chunk(out, "IDAT", idat.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        d.write(t); d.write(data)
        val crc = CRC32().apply { update(t); update(data) }
        d.writeInt(crc.value.toInt())
    }
}

/** 5×7 glyphs for contact-sheet labels: digits and a few marks. */
object Font5x7 {
    private val glyphs = mapOf(
        '0' to "01110 10001 10011 10101 11001 10001 01110", '1' to "00100 01100 00100 00100 00100 00100 01110",
        '2' to "01110 10001 00001 00010 00100 01000 11111", '3' to "11111 00010 00100 00010 00001 10001 01110",
        '4' to "00010 00110 01010 10010 11111 00010 00010", '5' to "11111 10000 11110 00001 00001 10001 01110",
        '6' to "00110 01000 10000 11110 10001 10001 01110", '7' to "11111 00001 00010 00100 01000 01000 01000",
        '8' to "01110 10001 10001 01110 10001 10001 01110", '9' to "01110 10001 10001 01111 00001 00010 01100",
        '.' to "00000 00000 00000 00000 00000 01100 01100", ':' to "00000 01100 01100 00000 01100 01100 00000",
        '#' to "01010 01010 11111 01010 11111 01010 01010", 's' to "00000 00000 01110 10000 01110 00001 11110",
    )

    /** Draws [text] at [scale]×, one blank column between glyphs; unknown characters are spaces. */
    fun draw(b: Bitmap1, text: String, x0: Int, y0: Int, scale: Int) {
        var x = x0
        for (c in text) {
            glyphs[c]?.split(' ')?.forEachIndexed { row, bits ->
                bits.forEachIndexed { col, ch -> if (ch == '1') b.fill(x + col * scale, y0 + row * scale, scale, scale, true) }
            }
            x += 6 * scale
        }
    }
}

/**
 * Sixteen screens to an image, so an agent can skim a long take in a few dozen
 * pictures and open a single screen only where it needs the detail.
 */
object ContactSheet {
    const val PER_SHEET = 16
    const val COLS = 4
    private const val SCALE = 2
    private const val LABEL_SCALE = 2
    private const val GAP = 8
    private const val LABEL_H = 7 * LABEL_SCALE + 4
    private const val TILE_W = SCREEN_W * SCALE + 2
    private const val TILE_H = SCREEN_H * SCALE + 2

    /** [tiles] are (label, bits), at most [PER_SHEET]. */
    fun render(tiles: List<Pair<String, ByteArray>>): Bitmap1 {
        val rows = (tiles.size + COLS - 1) / COLS
        val b = Bitmap1(COLS * TILE_W + (COLS + 1) * GAP, rows * (LABEL_H + TILE_H) + (rows + 1) * GAP)
        tiles.forEachIndexed { i, (label, bits) ->
            val x = GAP + (i % COLS) * (TILE_W + GAP)
            val y = GAP + (i / COLS) * (LABEL_H + TILE_H + GAP)
            Font5x7.draw(b, label, x, y, LABEL_SCALE)
            val ty = y + LABEL_H
            // a frame, since the screen's own edge is black on black
            b.fill(x, ty, TILE_W, 1, true); b.fill(x, ty + TILE_H - 1, TILE_W, 1, true)
            b.fill(x, ty, 1, TILE_H, true); b.fill(x + TILE_W - 1, ty, 1, TILE_H, true)
            b.blit(bits, x + 1, ty + 1, SCALE)
        }
        return b
    }
}
