package io.github.dimadake.schwungcam.sync

import kotlin.math.roundToLong

/**
 * The final file runs on the Move's sample clock: audio samples are never
 * resampled, video PTS are mapped onto them. Drift of tens of ppm then moves
 * frame times by microseconds instead of stretching the sound.
 *
 * [firstFrameNs] is the sensor time of the first video frame; raw video PTS
 * in the temp file are microseconds since it.
 */
class Timeline(
    private val fit: ClockFit,
    private val firstFrameNs: Long,
    private val rate: Int,
) {
    /** Device frame position of the first video frame. */
    val p0: Double = fit.posSec(firstFrameNs / 1e9) * rate

    fun videoPtsUs(rawPtsUs: Long): Long {
        val pos = fit.posSec((firstFrameNs + rawPtsUs * 1000) / 1e9) * rate
        return ((pos - p0) / rate * 1e6).roundToLong().coerceAtLeast(0)
    }

    /** Device position at the phone time [ns]. */
    fun posAt(ns: Long): Double = fit.posSec(ns / 1e9) * rate

    /**
     * The audio range for a video ending (last frame + its duration) at raw PTS
     * [endRawUs]: frames to skip in a WAV starting at [wavStartPos] (negative
     * means lead-in silence), and the length.
     */
    fun audioRange(wavStartPos: Long, endRawUs: Long): Pair<Long, Long> {
        val start = p0.roundToLong()
        val end = posAt(firstFrameNs + endRawUs * 1000).roundToLong()
        return (start - wavStartPos) to (end - start).coerceAtLeast(0)
    }
}
