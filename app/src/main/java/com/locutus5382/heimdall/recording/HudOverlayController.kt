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
import android.hardware.input.InputManager
import com.locutus5382.heimdall.engine.HudLayout
import com.locutus5382.heimdall.engine.HudDock
import com.locutus5382.heimdall.engine.HudSafetyAlertEngine
import com.locutus5382.heimdall.engine.HudSafetyState
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
/** Unlock is never allowed to outlive its parent Canvas window. */
internal fun shouldAttachHudUnlock(canvasOwned: Boolean, locked: Boolean): Boolean =
    canvasOwned && locked

internal class HudOverlayController(private val app: Context) :
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val store = HudProfileStore(app)
    private val main = Handler(Looper.getMainLooper())
    private val history = HudTelemetryHistory(60)
    private var latest: RecorderStatus = RecorderStatus.Idle
    private var view: HeimdallCanvasView? = null
    private var params: WindowManager.LayoutParams? = null
    private var unlock: TextView? = null
    private var unlockParams: WindowManager.LayoutParams? = null
    private var activeSession = -1L
    private var sessionObservedStartMs = 0L
    private val safetyEngine = HudSafetyAlertEngine()
    private var safetyState = HudSafetyState()
    private var stopSent = false
    private var lastFailure = 0L
    private var disposed = false
    // Retain a failing WindowManager owner until detachment is observed;
    // otherwise the next status tick could attach a duplicate Canvas/chip.
    private var releasePending = false
    private val density get() = app.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + .5f).toInt()
    private fun toDp(value: Int) = (value / density + .5f).toInt()

    init {
        store.prefs.registerOnSharedPreferenceChangeListener(this)
    }

    fun render(status: RecorderStatus) {
        if (disposed) return
        latest = status
        if (releasePending) {
            hideWindow()
            if (releasePending) return // no second overlay while one may survive
        }
        val recording = status as? RecorderStatus.Recording
        if (recording == null) {
            dismiss()
            return
        }
        // Track the session even when its overlay is hidden. Hiding the HUD
        // cannot restart the REC elapsed timer or create a second ring buffer.
        if (activeSession != recording.sessionId) {
            activeSession = recording.sessionId
            sessionObservedStartMs =
                recording.startedElapsedMs ?: SystemClock.elapsedRealtime()
            safetyEngine.reset()
            safetyState = HudSafetyState()
            stopSent = false
            history.clear()
        }
        history.add(recording.sessionId, recording.last)
        // Sample-driven even when overlay is not visible; no extra polling.
        safetyState = safetyEngine.observe(recording.sessionId, recording.last)
        if (!store.enabled() || !Settings.canDrawOverlays(app)) {
            hideWindow()
            return
        }
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
        if (view != null || unlock != null || releasePending || disposed) return
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
                override fun drawError() {
                    // An unsupported drawing path must not terminate the recording.
                    Log.e("HeimdallHUD", "Repeated Canvas errors; disabling overlay")
                    store.prefs.edit().putBoolean("hud_enabled", false).apply()
                    dismiss()
                }
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
                    alignDock(store.read())
                    updateWindow()
                    positionUnlock()
                }
            }
            canvas.post {
                if (view === canvas) {
                    clamp()
                    alignDock(store.read())
                    updateWindow()
                    syncLock(store.read())
                }
            }
        } catch (e: Exception) {
            // addView may have succeeded before a later setup step failed.
            // Roll back the actual attached window before clearing ownership.
            lastFailure = SystemClock.elapsedRealtime()
            val detached = releaseOverlayOrRetain(
                // A just-added view may not have received onAttach yet.
                remove = { wm.removeViewImmediate(canvas) },
                attached = { canvas.isAttachedToWindow },
                conceal = { canvas.visibility = View.GONE },
                report = { Log.w("HeimdallHUD", "Canvas rollback failed", it) },
            )
            if (detached) {
                view = null
                params = null
            } else {
                // The window may still be attached; keep its owner and retry
                // later instead of building a second Canvas over the game.
                view = canvas
                params = p
                releasePending = true
            }
            Log.w("HeimdallHUD", "Canvas window rejected; recording continues", e)
        }
    }

    private fun draw() {
        val recording = latest as? RecorderStatus.Recording ?: return
        val config = store.read()
        val v = view ?: return
        val now = SystemClock.elapsedRealtime()
        val safety = safetyEngine.visibleStateAt(now)
        v.update(config, recording.sessionId,
            recording.last, history.snapshot(), saving = stopSent,
            elapsedMs = now - sessionObservedStartMs,
            thermal = safety.batteryIndicator,
            alert = safety.primary)
        val originalX = params?.x
        val originalY = params?.y
        alignDock(config)
        if (originalX != params?.x || originalY != params?.y) {
            updateWindow()
            if (releasePending) return
            positionUnlock()
        }
        if (!releasePending) syncLock(config)
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

    /**
     * Bezel layouts always stay at a real edge. Floating layouts retain the
     * previous snap-to-side behavior. No second overlay is created.
     */
    private fun alignDock(config: HudProfile) {
        val p = params ?: return
        val (w, h) = dimensions()
        if (w <= 0 || h <= 0) return
        val screen = app.resources.displayMetrics
        when (config.layout) {
            HudLayout.RIBBON -> {
                p.x = max(0, (screen.widthPixels - w) / 2)
                p.y = if (config.dock == HudDock.BOTTOM)
                    max(0, screen.heightPixels - h) else 0
            }
            HudLayout.SPINE -> {
                p.x = if (config.dock == HudDock.RIGHT)
                    max(0, screen.widthPixels - w) else 0
                p.y = max(0, (screen.heightPixels - h) / 2)
            }
            else -> return
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
        if (releasePending) return
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
        updateCanvasOrRelease(
            update = { wm.updateViewLayout(v, p) },
            release = { failure ->
                // A detached/invalid window must not remain our cached owner.
                // Throttle recreation to avoid an overlay-crash loop.
                lastFailure = SystemClock.elapsedRealtime()
                Log.w("HeimdallHUD", "Window update failed; releasing HUD", failure)
                hideWindow()
            }
        )
    }

    private fun snap() {
        val p = params ?: return
        clamp()
        val screen = app.resources.displayMetrics
        val (width, height) = dimensions()
        val config = store.read()
        when (config.layout) {
            HudLayout.RIBBON -> {
                val side = if (p.y + height / 2 < screen.heightPixels / 2)
                    HudDock.TOP else HudDock.BOTTOM
                store.save(config.copy(dock = side))
                alignDock(store.read())
            }
            HudLayout.SPINE -> {
                val side = if (p.x + width / 2 < screen.widthPixels / 2)
                    HudDock.LEFT else HudDock.RIGHT
                store.save(config.copy(dock = side))
                alignDock(store.read())
            }
            else -> {
                val left = dp(8)
                val right = max(0, screen.widthPixels - width - dp(8))
                p.x = if (p.x + width / 2 < screen.widthPixels / 2) left else right
                p.x = min(p.x, max(0, screen.widthPixels - width))
                store.savePosition(toDp(p.x), toDp(p.y))
            }
        }
        updateWindow()
        positionUnlock()
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
        val maxOpacity = runCatching {
            (app.getSystemService(Context.INPUT_SERVICE) as InputManager)
                .maximumObscuringOpacityForTouch
        }.getOrDefault(.8f)
        val newAlpha = if (config.locked)
            min(config.opacity, maxOpacity * .95f).coerceIn(.1f, .76f)
        else 1f
        if (p.flags != newFlags || p.alpha != newAlpha) {
            p.flags = newFlags
            p.alpha = newAlpha
            updateWindow()
            // updateWindow may invalidate and remove the Canvas owner.
            // Never create a standalone unlock overlay after that failure.
            if (view !== v) return
        }
        if (shouldAttachHudUnlock(view === v && params != null, config.locked))
            showUnlock() else removeUnlock()
        v.contentDescription = if (config.locked)
            "Heimdall HUD locked. Use separate UNLOCK control."
        else "Heimdall HUD. Tap MODE to cycle layouts, LOCK to pass through touches, STOP to save."
    }

    private fun showUnlock() {
        // The unlock chip is meaningful only while its Canvas owner exists.
        if (view == null || params == null || releasePending) return
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
                // addView can attach before throwing; do not leave an orphan chip.
                val detached = releaseOverlayOrRetain(
                    remove = { wm.removeViewImmediate(chip) },
                    attached = { chip.isAttachedToWindow },
                    conceal = { chip.visibility = View.GONE },
                    report = { Log.w("HeimdallHUD", "Unlock rollback failed", it) },
                )
                if (detached) {
                    unlock = null
                    unlockParams = null
                } else {
                    unlock = chip
                    unlockParams = p
                    releasePending = true
                }
                Log.w("HeimdallHUD", "Unlock chip unavailable; release lock", e)
                store.saveLock(false)
                return
            }
        }
        positionUnlock()
    }

    private fun positionUnlock() {
        // A WindowManager cleanup is in progress: do not update or recreate
        // any window whose original owner may still be attached.
        if (releasePending) return
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
        // Injectable failure boundary: a rejected WindowManager update must
        // release stale chip ownership and restore main Canvas touch controls.
        val updated = updateUnlockOrReleaseLock(
            update = { wm.updateViewLayout(chip, p) },
            releaseWindow = { removeUnlock() },
            releaseLock = { store.saveLock(false) }
        )
        if (!updated) Log.w("HeimdallHUD", "Unlock position update failed; released lock")
    }

    private fun removeUnlock() {
        val current = unlock ?: return
        val detached = releaseOverlayOrRetain(
            remove = { wm.removeViewImmediate(current) },
            attached = { current.isAttachedToWindow },
            conceal = { current.visibility = View.GONE },
            report = { Log.w("HeimdallHUD", "Unlock cleanup", it) },
        )
        if (detached) {
            unlock = null
            unlockParams = null
        } else {
            releasePending = true
        }
    }

    private fun hideWindow() {
        removeUnlock()
        val current = view
        if (current != null) {
            val detached = releaseOverlayOrRetain(
                remove = { wm.removeViewImmediate(current) },
                attached = { current.isAttachedToWindow },
                conceal = { current.visibility = View.GONE },
                report = { Log.w("HeimdallHUD", "Canvas cleanup", it) },
            )
            if (detached) {
                view = null
                params = null
            }
        }
        // Suppress creation until BOTH windows are known detached.
        releasePending = view != null || unlock != null
    }

    fun dismiss() {
        hideWindow()
        stopSent = false
        activeSession = -1L
        sessionObservedStartMs = 0L
        safetyEngine.reset()
        safetyState = HudSafetyState()
        history.clear()
    }

    fun dispose() {
        disposed = true
        store.prefs.unregisterOnSharedPreferenceChangeListener(this)
        dismiss()
        main.removeCallbacksAndMessages(null)
    }
}
