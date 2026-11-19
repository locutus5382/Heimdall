package com.locutus5382.heimdall.recording

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.locutus5382.heimdall.engine.RecorderStatus
import java.util.Locale

/**
 * One owner (the recorder Service), one WindowManager view, zero secondary
 * samplers. Disabled until the user opts in and grants overlay permission.
 * This is the first functional Pill prototype, not the final three HUD themes.
 */
internal class HudOverlayController(private val app: Context) {
    private val window = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = app.getSharedPreferences("heimdall_ui_v1", Context.MODE_PRIVATE)
    private var view: View? = null
    private var cpu: TextView? = null
    private var battery: TextView? = null
    private var dot: TextView? = null
    private var gpu: TextView? = null
    private var temperature: TextView? = null

    fun render(status: RecorderStatus) {
        if (status !is RecorderStatus.Recording ||
            !prefs.getBoolean("hud_enabled", false) ||
            !Settings.canDrawOverlays(app)) {
            dismiss()
            return
        }
        if (view == null) createWindow()
        cpu?.text = status.last?.cpuLoadPercent?.let {
            String.format(Locale.US, "CPU %.0f%%", it)
        } ?: "CPU —"
        battery?.text = status.last?.batteryLevelPercent?.let {
            "BAT $it%"
        } ?: "BAT —"
        gpu?.text = status.last?.gpuClockMHz?.let { "GPU $it MHz" } ?: "GPU —"
        temperature?.text = status.last?.batteryTemperatureC?.let {
            String.format(Locale.US, "T %.1f°C", it)
        } ?: "T —"
        dot?.text = "●"
    }

    private fun dp(value: Int): Int =
        (value * app.resources.displayMetrics.density + 0.5f).toInt()

    private fun label(
        value: String, color: Int, size: Float, bold: Boolean = false
    ): TextView = TextView(app).apply {
        text = value
        setTextColor(color)
        textSize = size
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(dp(6), dp(10), dp(6), dp(10))
        includeFontPadding = false
    }

    private fun createWindow() {
        if (view != null) return
        val ice = Color.rgb(234, 241, 255)
        val marigold = Color.rgb(242, 169, 0)
        val pill = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.argb(244, 37, 71, 115),
                    Color.argb(243, 13, 28, 55),
                    Color.argb(237, 7, 17, 37)
                )
            ).apply {
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), Color.argb(200, 154, 185, 228))
            }
            elevation = dp(7).toFloat()
        }
        pill.addView(label("HEIMDALL", marigold, 11f, true))
        dot = label("●", Color.rgb(255, 73, 82), 18f)
            .also { pill.addView(it) }
        cpu = label("CPU —", ice, 12f, true).also { pill.addView(it) }
        gpu = label("GPU —", ice, 12f, true).also { pill.addView(it) }
        battery = label("BAT —", ice, 12f, true).also { pill.addView(it) }
        temperature = label("T —", ice, 12f, true).also { pill.addView(it) }
        val grip = label("⋮⋮", Color.rgb(154, 185, 228), 18f, true).apply {
            minWidth = dp(48)
            minHeight = dp(48)
            contentDescription = "Drag Heimdall telemetry"
        }
        pill.addView(grip)
        val stopChip = label("STOP", marigold, 12f, true).apply {
            minWidth = dp(54)
            minHeight = dp(48)
            background = GradientDrawable().apply {
                setColor(Color.argb(229, 38, 68, 131))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), marigold)
            }
            setOnClickListener {
                // Dispatch to the same foreground Service as UI and QS Tile.
                // No HUD-owned engine, database or second sampler.
                try {
                    app.startForegroundService(
                        Intent(app, HeimdallRecorderService::class.java)
                            .setAction(HeimdallRecorderService.STOP))
                } catch (e: Exception) {
                    Log.w("HeimdallHUD", "STOP dispatch refused", e)
                }
            }
        }
        pill.addView(stopChip)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = prefs.getInt("hud_x", dp(16))
            y = prefs.getInt("hud_y", dp(90))
            title = "Heimdall recording HUD"
        }
        val screen = app.resources.displayMetrics
        grip.setOnTouchListener(object : View.OnTouchListener {
            var lastX = 0f
            var lastY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX
                        lastY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x += (event.rawX - lastX).roundToInt()
                        params.y += (event.rawY - lastY).roundToInt()
                        lastX = event.rawX
                        lastY = event.rawY
                        params.x = params.x.coerceIn(
                            0, max(0, screen.widthPixels - pill.width))
                        params.y = params.y.coerceIn(
                            0, max(0, screen.heightPixels - pill.height))
                        runCatching { window.updateViewLayout(pill, params) }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val edge = max(0, screen.widthPixels - pill.width - dp(8))
                        params.x = if (params.x < edge / 2) dp(8) else edge
                        params.x = min(params.x, edge)
                        runCatching { window.updateViewLayout(pill, params) }
                        prefs.edit().putInt("hud_x", params.x)
                            .putInt("hud_y", params.y).apply()
                        return true
                    }
                }
                return false
            }
        })
        try {
            window.addView(pill, params)
            view = pill
        } catch (e: Exception) {
            dot = null
            cpu = null
            battery = null
            gpu = null
            temperature = null
            Log.w("HeimdallHUD", "Overlay unavailable; recording continues", e)
        }
    }

    fun dismiss() {
        val current = view ?: return
        view = null
        dot = null
        cpu = null
        battery = null
        gpu = null
        temperature = null
        try {
            window.removeViewImmediate(current)
        } catch (e: Exception) {
            Log.w("HeimdallHUD", "Overlay removal skipped", e)
        }
    }
}
