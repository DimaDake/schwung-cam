package io.github.dimadake.schwungcam.ui

import android.content.Context
import io.github.dimadake.schwungcam.dsp.MasterSettings
import io.github.dimadake.schwungcam.gl.KeyMode
import io.github.dimadake.schwungcam.gl.Overlay

enum class Resolution(val label: String, val w: Int, val h: Int, val bitrate: Int) {
    P720("720p", 1280, 720, 8_000_000),
    P1080("1080p", 1920, 1080, 16_000_000),
    P2160("4K", 3840, 2160, 45_000_000),
}

enum class Target(val label: String, val lufs: Double) {
    YOUTUBE("YouTube −14", -14.0),
    LOUD("Loud −9", -9.0),
}

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("schwungcam", Context.MODE_PRIVATE)

    var host: String
        get() = p.getString("host", "move.local") ?: "move.local"
        set(v) = p.edit().putString("host", v).apply()

    var camera: String?
        get() = p.getString("camera", null)
        set(v) = p.edit().putString("camera", v).apply()

    var resolution: Resolution
        get() = runCatching { Resolution.valueOf(p.getString("res", "P1080")!!) }.getOrDefault(Resolution.P1080)
        set(v) = p.edit().putString("res", v.name).apply()

    /**
     * The overlay, with its place and size kept per orientation: a spot that
     * suits a landscape frame rarely suits a portrait one. On/off, key and
     * opacity are shared.
     */
    fun overlay(portrait: Boolean): Overlay {
        val k = if (portrait) "ovp_" else "ov_"
        val d = if (portrait) Overlay(cx = 0.5f, cy = 0.88f, w = 0.6f) else Overlay()
        return Overlay(
            enabled = p.getBoolean("ov_on", false),
            cx = p.getFloat(k + "cx", d.cx), cy = p.getFloat(k + "cy", d.cy), w = p.getFloat(k + "w", d.w),
            key = runCatching { KeyMode.valueOf(p.getString("ov_key", "BLACK_TRANSPARENT")!!) }.getOrDefault(KeyMode.BLACK_TRANSPARENT),
            opacity = p.getFloat("ov_op", 1f),
        )
    }

    fun setOverlay(portrait: Boolean, o: Overlay) {
        val k = if (portrait) "ovp_" else "ov_"
        p.edit().putBoolean("ov_on", o.enabled).putFloat(k + "cx", o.cx).putFloat(k + "cy", o.cy)
            .putFloat(k + "w", o.w).putString("ov_key", o.key.name).putFloat("ov_op", o.opacity).apply()
    }

    var masterOn: Boolean
        get() = p.getBoolean("master", true)
        set(v) = p.edit().putBoolean("master", v).apply()

    var glue: Boolean
        get() = p.getBoolean("glue", true)
        set(v) = p.edit().putBoolean("glue", v).apply()

    var target: Target
        get() = runCatching { Target.valueOf(p.getString("target", "YOUTUBE")!!) }.getOrDefault(Target.YOUTUBE)
        set(v) = p.edit().putString("target", v.name).apply()

    var keepWav: Boolean
        get() = p.getBoolean("keepwav", false)
        set(v) = p.edit().putBoolean("keepwav", v).apply()

    /** Record the controls and screens beside each take, for captioning with an AI later. */
    var aiTimeline: Boolean
        get() = p.getBoolean("ai", false)
        set(v) = p.edit().putBoolean("ai", v).apply()

    fun master() = MasterSettings(enabled = masterOn, glue = glue, targetLufs = target.lufs)
}
