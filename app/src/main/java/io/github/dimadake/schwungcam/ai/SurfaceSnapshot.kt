package io.github.dimadake.schwungcam.ai

/** One slot of the snapshot's input ring: a raw MIDI_IN message and the SPI frame it arrived in. */
class RawEvent(val frame: Long, val status: Int, val d1: Int, val d2: Int)

/**
 * Schwung's `surface_live_shm_t` (src/host/surface_live_shm.h, v1), as
 * display-server streams it in `event: surface`. The layout is pinned there
 * by static asserts, so the offsets are copied rather than discovered.
 */
class SurfaceSnapshot(
    /** The SPI frame the snapshot was taken at (the event's top-level `frame`). */
    val frame: Long,
    val eventCount: Long,
    /** All [RING] slots; slot n % RING holds input number n. */
    val ring: List<RawEvent>,
    val noteLed: IntArray,
    val ccLed: IntArray,
    /** Per CC LED painted by RGB SysEx: lit or not; null when the palette value is the latest write. */
    val ccRgbLit: Array<Boolean?>,
    val notePressure: IntArray,
    val encPos: IntArray,
    val arrivalNs: Long,
) {
    companion object {
        const val RING = 32
        const val ENCODERS = 10
        /** One SPI frame is 128 samples at 44.1 kHz. */
        const val FRAME_NS = 128 * 1_000_000_000L / 44100
        private const val MAGIC = "SURFLV1"
        private const val NOTE_LED = 24
        private const val CC_LED = 24 + 2 * 128
        private const val RGB = 24 + 4 * 128
        private const val NOTE_PRESSURE = 24 + 13 * 128
        private const val ENC_POS = 24 + 15 * 128
        private const val EVENTS = ENC_POS + 32
        const val SIZE = EVENTS + RING * 8

        fun decode(jsonFrame: Long, raw: ByteArray, arrivalNs: Long): SurfaceSnapshot? {
            if (raw.size < SIZE || String(raw, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) return null
            fun u8(o: Int) = raw[o].toInt() and 0xFF
            fun u32(o: Int) = (u8(o).toLong() or (u8(o + 1).toLong() shl 8) or (u8(o + 2).toLong() shl 16) or (u8(o + 3).toLong() shl 24))
            fun i16(o: Int) = ((u8(o) or (u8(o + 1) shl 8)).toShort()).toInt()
            val ring = (0 until RING).map { i ->
                val o = EVENTS + i * 8
                RawEvent(u32(o), u8(o + 4), u8(o + 5), u8(o + 6))
            }
            val rgb = Array<Boolean?>(128) { cc ->
                val o = RGB + (128 + cc) * 4
                if (u8(o + 3) == 0) null else (u8(o) or u8(o + 1) or u8(o + 2)) != 0
            }
            return SurfaceSnapshot(
                frame = jsonFrame, eventCount = u32(20), ring = ring,
                noteLed = IntArray(128) { u8(NOTE_LED + it) }, ccLed = IntArray(128) { u8(CC_LED + it) },
                ccRgbLit = rgb, notePressure = IntArray(128) { u8(NOTE_PRESSURE + it) },
                encPos = IntArray(ENCODERS) { i16(ENC_POS + 2 * it) }, arrivalNs = arrivalNs,
            )
        }
    }
}
