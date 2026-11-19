package com.locutus5382.heimdall.recording

import android.content.Context
import android.content.SharedPreferences
import com.locutus5382.heimdall.engine.HudLayout
import com.locutus5382.heimdall.engine.HudMetric
import com.locutus5382.heimdall.engine.HudProfile

/** Local-only versioned HUD settings. This does not own the recorder. */
class HudProfileStore(context: Context) {
    val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("heimdall_ui_v1", Context.MODE_PRIVATE)

    fun read(): HudProfile = HudProfile(
        layout = HudLayout.from(prefs.getString("hud_layout_v2", "pill")),
        scale = prefs.getFloat("hud_scale_v2", 1f),
        opacity = prefs.getFloat("hud_opacity_v2", .76f),
        locked = prefs.getBoolean("hud_locked_v2", false),
        metrics = HudMetric.parse(prefs.getString("hud_metrics_v2", null))
    ).sanitized()

    fun save(profile: HudProfile) {
        val valid = profile.sanitized()
        prefs.edit()
            .putString("hud_layout_v2", valid.layout.token)
            .putFloat("hud_scale_v2", valid.scale)
            .putFloat("hud_opacity_v2", valid.opacity)
            .putBoolean("hud_locked_v2", valid.locked)
            .putString("hud_metrics_v2",
                HudMetric.entries.filter { it in valid.metrics }
                    .joinToString(",") { it.token })
            .apply()
    }

    fun saveLayout(layout: HudLayout) {
        prefs.edit().putString("hud_layout_v2", layout.token).apply()
    }

    fun saveLock(locked: Boolean) {
        prefs.edit().putBoolean("hud_locked_v2", locked).apply()
    }

    fun enabled() = prefs.getBoolean("hud_enabled", false)

    /**
     * v0.3 stored positions in raw pixels. v0.4 stores them in dp so a
     * migration does not fling the HUD off-screen on high-density panels.
     */
    private fun oldPixelsAsDp(oldKey: String, fallback: Int): Int {
        if (!prefs.contains(oldKey)) return fallback
        val pixels = prefs.getInt(oldKey, fallback)
        return (pixels / contextDensity + .5f).toInt()
    }

    private val contextDensity: Float = context.resources.displayMetrics.density
    fun x() = prefs.getInt("hud_x_dp_v2", oldPixelsAsDp("hud_x", 16))
    fun y() = prefs.getInt("hud_y_dp_v2", oldPixelsAsDp("hud_y", 84))

    fun savePosition(xDp: Int, yDp: Int) {
        prefs.edit().putInt("hud_x_dp_v2", xDp)
            .putInt("hud_y_dp_v2", yDp).apply()
    }
}
