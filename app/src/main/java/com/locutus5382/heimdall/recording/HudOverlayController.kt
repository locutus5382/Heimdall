package com.locutus5382.heimdall.recording

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.locutus5382.heimdall.engine.HudProfile
import com.locutus5382.heimdall.engine.HudTelemetryHistory
import com.locutus5382.heimdall.engine.RecorderStatus
import kotlin.math.max
import kotlin.math.min

/**
 * The sole overlay-window owner, created by the foreground recording Service.
 * Canvas never opens windows or reads a sensor. Optional UNLOCK is a SECOND
 * 48dp touch target only while the main HUD is explicitly click-through;
 * it is NOT a duplicate HUD or recorder.
 */
internal class HudOverlayController(private val app: Context) :
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val store = HudProfileStore(app)
    private val main = Handler(Looper.getMainLooper())
    private val history = HudTelemetryHistory(36)
    private var latest: RecorderStatus = RecorderStatus.Idle
    private var view: HeimdallCanvasView? = null
    private var params: WindowManager.LayoutParams? = null
    private var unlock: TextView? = null
    private var unlockParams: WindowManager.LayoutParams? = null
    private var activeSession = -1L
    private var stopSent = false
    private var lastFailure = 0L
    private var disposed = false
    private val density get() = app.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + .5f).toInt()
    private fun toDp(value: Int) = (value / density + .5f).toInt()

    init {
        store.prefs.registerOnSharedPreferenceChangeListener(this)
    }

    fun render(status: RecorderStatus) {
        if (disposed) return
        latest = status
        val recording = status as? RecorderStatus.Recording
        if (recording == null || !store.enabled() ||
            !Settings.canDrawOverlays(app)) {
            dismiss()
            return
        }
        if (activeSession != recording.sessionId) {
            activeSession = recording.sessionId
            stopSent = false
            history.clear()
        }
        history.add(recording.sessionId, recording.last)
        if (view == null && SystemClock.elapsedRealtime() - lastFailure > 3_000L) {
            create()
        }
        draw()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?, key: String?
    ) {
        if (disposed) return
        val update = Runnable { if (!disposed) render(latest) }
        if (Looper.myLooper() == Looper.getMainLooper()) update.run()
        else main.post(update)
    }

    private fun create() {
        if (view != null || disposed) return
        val canvas = HeimdallCanvasView(app,
            object : HeimdallCanvasView.Actions {
                override fun stop() = requestStop()
                override fun cycle() {
                    val next = store.read().layout.next()
                    store.saveLayout(next)
                }
                override fun lock() { store.saveLock(true) }
                override fun drag(dxPixels: Int, dyPixels: Int) = move(dxPixels, dyPixels)
                override fun settle() = snap()
            }
        )
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dp(store.x())
            y = dp(store.y())
            title = "Heimdall Canvas telemetry"
        }
        try {
            wm.addView(canvas, p)
            view = canvas
            params = p
            canvas.addOnLayoutChangeListener { _, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft ||
                    bottom - top != oldBottom - oldTop) {
                    clamp()
                    updateWindow()
                    positionUnlock()
                }
            }
            canvas.post {
                if (view === canvas) {
                    clamp()
                    updateWindow()
                    syncLock(store.read())
                }
            }
        } catch (e: Exception) {
            lastFailure = SystemClock.elapsedRealtime()
            view = null
            params = null
            Log.w("HeimdallHUD", "Canvas window rejected; recording continues", e)
        }
    }

    private fun draw() {
        val recording = latest as? RecorderStatus.Recording ?: return
        val config = store.read()
        val v = view ?: return
        v.update(config, recording.sessionId,
            recording.last, history.snapshot(), saving = stopSent)
        syncLock(config)
    }

    private fun requestStop() {
        if (stopSent) return
        val v = view ?: return
        stopSent = true
        draw()
        try {
            app.startForegroundService(
                Intent(app, HeimdallRecorderService::class.java)
                    .setAction(HeimdallRecorderService.STOP)
            )
        } catch (e: Exception) {
            stopSent = false
            v.invalidate()
            draw()
            Log.w("HeimdallHUD", "STOP dispatch rejected; button restored", e)
        }
    }

    private fun move(dx: Int, dy: Int) {
        val p = params ?: return
        p.x += dx
        p.y += dy
        clamp()
        updateWindow()
        positionUnlock()
    }

    /** Called when the device changes orientation or display geometry. */
    fun realign() {
        val current = view ?: return
        current.requestLayout()
        current.post {
            if (view === current) {
                clamp()
                updateWindow()
                positionUnlock()
            }
        }
    }

    private fun dimensions(): Pair<Int, Int> {
        val v = view ?: return 0 to 0
        return max(v.width, v.measuredWidth) to max(v.height, v.measuredHeight)
    }

    private fun clamp() {
        val p = params ?: return
        val screen = app.resources.displayMetrics
        val (width, height) = dimensions()
        p.x = p.x.coerceIn(0, max(0, screen.widthPixels - width))
        p.y = p.y.coerceIn(0, max(0, screen.heightPixels - height))
    }

    private fun updateWindow() {
        val v = view ?: return
        val p = params ?: return
        runCatching { wm.updateViewLayout(v, p) }
            .onFailure { Log.w("HeimdallHUD", "Window update failed", it) }
    }

    private fun snap() {
        val p = params ?: return
        clamp()
        val screen = app.resources.displayMetrics
        val (width, _) = dimensions()
        val left = dp(8)
        val right = max(0, screen.widthPixels - width - dp(8))
        p.x = if (p.x + width / 2 < screen.widthPixels / 2) left else right
        p.x = min(p.x, max(0, screen.widthPixels - width))
        updateWindow()
        positionUnlock()
        store.savePosition(toDp(p.x), toDp(p.y))
    }

    private fun syncLock(config: HudProfile) {
        val v = view ?: return
        val p = params ?: return
        val newFlags =
            (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) or
            (if (config.locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        // Android 12+ only allows touch pass-through under a qualifying
        // obscuring-opacity limit. Keep the locked main overlay below 0.8,
        // and position the small unlock target outside the HUD rectangle.
        val newAlpha = if (config.locked) .72f else 1f
        if (p.flags != newFlags || p.alpha != newAlpha) {
            p.flags = newFlags
            p.alpha = newAlpha
            updateWindow()
        }
        if (config.locked) showUnlock() else removeUnlock()
        v.contentDescription = if (config.locked)
            "Heimdall HUD locked. Use separate UNLOCK control."
        else "Heimdall HUD. Tap MODE to cycle layouts, LOCK to pass through touches, STOP to save."
    }

    private fun showUnlock() {
        if (unlock == null) {
            val chip = TextView(app).apply {
                text = "UNLOCK"
                textSize = 11f
                setTextColor(Color.rgb(234, 241, 255))
                gravity = Gravity.CENTER
                minWidth = dp(64)
                minHeight = dp(48)
                isClickable = true
                isFocusable = true
                contentDescription = "Unlock Heimdall HUD touch controls"
                background = GradientDrawable().apply {
                    setColor(Color.argb(239, 25, 52, 93))
                    cornerRadius = dp(15).toFloat()
                    setStroke(dp(1), Color.rgb(240, 189, 98))
                }
                setOnClickListener { store.saveLock(false) }
            }
            val p = WindowManager.LayoutParams(
                dp(64), dp(48),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                title = "Heimdall HUD unlock button"
            }
            try {
                wm.addView(chip, p)
                unlock = chip
                unlockParams = p
            } catch (e: Exception) {
                Log.w("HeimdallHUD", "Unlock chip unavailable; release lock", e)
                store.saveLock(false)
                return
            }
        }
        positionUnlock()
    }

    private fun positionUnlock() {
        val parent = params ?: return
        val p = unlockParams ?: return
        val chip = unlock ?: return
        val screen = app.resources.displayMetrics
        val (width, height) = dimensions()
        // Prefer outside the HUD to avoid overlapping obscuring overlays.
        val right = parent.x + width + dp(8)
        val left = parent.x - dp(64) - dp(8)
        if (right + dp(64) <= screen.widthPixels) {
            p.x = right
            p.y = parent.y
        } else if (left >= 0) {
            p.x = left
            p.y = parent.y
        } else {
            p.x = (parent.x + width - dp(64)).coerceIn(0,
                max(0, screen.widthPixels - dp(64)))
            p.y = if (parent.y + height + dp(52) <= screen.heightPixels)
                parent.y + height + dp(4)
            else max(0, parent.y - dp(52))
        }
        runCatching { wm.updateViewLayout(chip, p) }
            .onFailure { Log.w("HeimdallHUD", "Unlock position update", it) }
    }

    private fun removeUnlock() {
        val current = unlock ?: return
        unlock = null
        unlockParams = null
        runCatching { wm.removeViewImmediate(current) }
            .onFailure { Log.w("HeimdallHUD", "Unlock cleanup", it) }
    }

    fun dismiss() {
        removeUnlock()
        val current = view ?: return
        view = null
        params = null
        stopSent = false
        activeSession = -1L
        history.clear()
        runCatching { wm.removeViewImmediate(current) }
            .onFailure { Log.w("HeimdallHUD", "Canvas cleanup", it) }
    }

    fun dispose() {
        disposed = true
        store.prefs.unregisterOnSharedPreferenceChangeListener(this)
        dismiss()
        main.removeCallbacksAndMessages(null)
    }
}
