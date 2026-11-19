package com.locutus5382.heimdall.recording

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.HudLayout
import com.locutus5382.heimdall.engine.HudProfile
import com.locutus5382.heimdall.engine.HudRenderState
import com.locutus5382.heimdall.engine.HudTelemetryHistory
import com.locutus5382.heimdall.engine.ThermalIndicatorState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One original renderer, four presentations. No sensor, database, clock or
 * filesystem access in onDraw. State is formatted before it reaches Canvas.
 */
internal class HeimdallCanvasView(
    context: Context,
    private val actions: Actions,
) : View(context) {
    interface Actions {
        fun stop()
        fun cycle()
        fun lock()
        fun drag(dxPixels: Int, dyPixels: Int)
        fun settle()
        fun drawError()
    }

    private val density = resources.displayMetrics.density
    private val labelFace: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val numberFace: Typeface = Typeface.MONOSPACE
    private val fontFactor = resources.configuration.fontScale
    private val layoutEngine = HudLayoutEngine(fontFactor)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val plot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val borderPath = Path()
    private val tempRect = RectF()
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gradient: LinearGradient? = null

    private val midnight = Color.rgb(11, 20, 37)
    private val cobalt = Color.rgb(25, 43, 73)
    private val signal = Color.rgb(101, 213, 245)
    private val caution = Color.rgb(240, 189, 98)
    private val coral = Color.rgb(241, 107, 120)
    private val ice = Color.rgb(242, 246, 252)
    private val muted = Color.rgb(148, 167, 196)

    private var profile = HudProfile()
    private var state = HudRenderState.recording(0L, 0L, null, profile, emptyList())
    private var geometry: HudGeometry? = null
    private var menuExpanded = false
    private var drawFailures = 0
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var touchAction = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragged = false

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Heimdall recording telemetry. Tap the menu at the end " +
            "to access layout, touch lock, and stop controls."
    }

    fun update(
        profile: HudProfile,
        id: Long,
        sample: DeviceSample?,
        history: List<HudTelemetryHistory.Point>,
        saving: Boolean = false,
        elapsedMs: Long = 0L,
        thermal: ThermalIndicatorState = ThermalIndicatorState.NOMINAL,
    ) {
        val valid = profile.sanitized()
        val newState = HudRenderState.recording(
            id, elapsedMs, sample, valid, history, thermal, saving
        )
        val previous = this.state
        val relayout = this.profile.layout != valid.layout ||
            this.profile.scale != valid.scale ||
            previous.readouts != newState.readouts ||
            this.profile.metrics != valid.metrics ||
            this.profile.locked != valid.locked
        this.profile = valid
        this.state = newState
        if (relayout || geometry == null) requestLayout()
        invalidate()
    }

    private fun calculate(maxAvailablePx: Int): HudGeometry {
        val availableDp = max(1f,
            (maxAvailablePx - 16f * density) / (density * profile.scale))
        return layoutEngine.calculate(profile, state, availableDp, menuExpanded)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenWidth = resources.displayMetrics.widthPixels
        val cap = min(screenWidth,
            View.MeasureSpec.getSize(widthMeasureSpec).takeIf {
                View.MeasureSpec.getMode(widthMeasureSpec) !=
                    View.MeasureSpec.UNSPECIFIED && it > 0
            } ?: screenWidth)
        val newGeometry = calculate(cap)
        geometry = newGeometry
        gradient = LinearGradient(
            0f, 0f, newGeometry.widthDp, newGeometry.heightDp,
            intArrayOf(
                Color.rgb(26, 52, 91),
                midnight,
                Color.rgb(7, 16, 32)
            ), null, Shader.TileMode.CLAMP)
        val factor = density * profile.scale
        val w = (newGeometry.widthDp * factor).roundToInt().coerceAtLeast(1)
        val h = (newGeometry.heightDp * factor).roundToInt().coerceAtLeast(1)
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }

    private fun rect(canvas: Canvas, r: RectF, radius: Float, color: Int) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRoundRect(r, radius, radius, paint)
    }

    private fun label(
        canvas: Canvas, value: String, x: Float, baseline: Float,
        sp: Float, color: Int, numeric: Boolean = false
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = sp * fontFactor
        paint.typeface = if (numeric) numberFace else labelFace
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(value, x, baseline, paint)
    }

    private fun surface(canvas: Canvas, g: HudGeometry) {
        paint.shader = gradient
        paint.color = Color.WHITE
        paint.alpha = (255f * profile.opacity).roundToInt()
        val w = g.widthDp
        val h = g.heightDp
        if (profile.layout == HudLayout.RIBBON ||
            profile.layout == HudLayout.SPINE) {
            borderPath.reset()
            if (profile.layout == HudLayout.RIBBON) {
                borderPath.moveTo(0f, 0f)
                borderPath.lineTo(w, 0f)
                borderPath.lineTo(w - 16f, h - 1f)
                borderPath.lineTo(16f, h - 1f)
            } else {
                borderPath.moveTo(0f, 0f)
                borderPath.lineTo(w - 13f, 0f)
                borderPath.lineTo(w - 1f, 14f)
                borderPath.lineTo(w - 1f, h - 14f)
                borderPath.lineTo(w - 13f, h)
                borderPath.lineTo(0f, h)
            }
            borderPath.close()
            canvas.drawPath(borderPath, paint)
            stroke.color = Color.rgb(59, 99, 175)
            canvas.drawPath(borderPath, stroke)
        } else {
            tempRect.set(1f, 1f, w - 1f, h - 1f)
            canvas.drawRoundRect(tempRect,
                if (profile.layout == HudLayout.PILL) 30f else 15f,
                if (profile.layout == HudLayout.PILL) 30f else 15f, paint)
            stroke.color = Color.rgb(59, 99, 175)
            canvas.drawRoundRect(tempRect,
                if (profile.layout == HudLayout.PILL) 30f else 15f,
                if (profile.layout == HudLayout.PILL) 30f else 15f, stroke)
        }
        paint.shader = null
        paint.alpha = 255
        stroke.color = signal
        stroke.strokeWidth = 2f
        if (profile.layout == HudLayout.SPINE)
            canvas.drawLine(w - 3f, 14f, w - 3f, h - 14f, stroke)
        else if (profile.layout == HudLayout.RIBBON)
            canvas.drawLine(22f, h - 4f, w - 22f, h - 4f, stroke)
        else if (profile.layout == HudLayout.CARD)
            canvas.drawLine(18f, 4f, w - 18f, 4f, stroke)
        else canvas.drawLine(3f, h * .40f, 3f, h * .60f, stroke)
        stroke.strokeWidth = 1f
    }

    private fun drawHeader(canvas: Canvas, g: HudGeometry) {
        val rec = state.recordingLabel
        when (profile.layout) {
            HudLayout.CARD -> {
                label(canvas, "HEIMDALL", 16f, 31f, 13f, ice)
                label(canvas, rec, max(122f, g.widthDp - 184f),
                    31f, 11f, coral, true)
            }
            HudLayout.SPINE -> {
                label(canvas, "● REC", 12f, 26f, 12f, coral, true)
                label(canvas, state.elapsedLabel, 12f, 43f, 11f, ice, true)
            }
            else -> label(canvas, rec, 14f, 39f, 12f, coral, true)
        }
    }

    private fun segmentedGauge(canvas: Canvas, s: HudSlot) {
        val fraction = s.readout.normalized ?: return
        val bars = 6
        val left = s.bounds.left + 5f
        val width = (s.bounds.width() - 12f - (bars - 1) * 3f) / bars
        if (width < 1f) return
        for (i in 0 until bars) {
            val color = if (i < (fraction * bars).roundToInt())
                if (s.readout.alert) coral
                else if (s.readout.caution) caution else signal
            else Color.rgb(48, 68, 95)
            tempRect.set(left + i * (width + 3f), s.bounds.bottom - 5f,
                left + i * (width + 3f) + width, s.bounds.bottom - 2f)
            rect(canvas, tempRect, 1f, color)
        }
    }

    private fun metrics(canvas: Canvas, g: HudGeometry) {
        g.slots.forEach { slot ->
            if (profile.layout == HudLayout.CARD) {
                rect(canvas, slot.bounds, 9f, Color.argb(138, 34, 53, 83))
            } else if (profile.layout == HudLayout.SPINE) {
                stroke.color = cobalt
                canvas.drawLine(9f, slot.bounds.bottom - 1f,
                    g.widthDp - 9f, slot.bounds.bottom - 1f, stroke)
            }
            label(canvas, slot.readout.label,
                slot.labelX, slot.labelY, 10f, muted)
            label(canvas, slot.readout.value, slot.valueX, slot.valueY,
                if (profile.layout == HudLayout.CARD) 17f
                else if (profile.layout == HudLayout.SPINE) 14f
                else 14.5f,
                if (slot.readout.alert) coral
                else if (slot.readout.caution) caution else ice, true)
            if (profile.layout == HudLayout.PILL ||
                profile.layout == HudLayout.RIBBON) segmentedGauge(canvas, slot)
        }
    }

    private fun sparkline(canvas: Canvas, g: HudGeometry) {
        val bounds = g.graph ?: return
        label(canvas, "CPU LOAD HISTORY · MEASURED", bounds.left,
            bounds.top - 9f, 10f, muted)
        rect(canvas, bounds, 7f, Color.argb(100, 4, 13, 29))
        stroke.color = cobalt
        for (i in 1..2) {
            val y = bounds.top + i * bounds.height() / 3f
            canvas.drawLine(bounds.left + 6f, y, bounds.right - 6f, y, stroke)
        }
        val points = state.cpuHistory
        if (points.size < 2) {
            label(canvas, "Waiting for real samples", bounds.left + 9f,
                bounds.centerY() + 3f, 10f, muted)
            return
        }
        plot.color = signal
        path.reset()
        var open = false
        for (index in points.indices) {
            val load = points[index].cpuLoad
            if (load == null) {
                if (open) canvas.drawPath(path, plot)
                path.reset()
                open = false
            } else {
                val x = bounds.left + 6f +
                    (index.toFloat() / max(1f, (points.size - 1).toFloat())) *
                    (bounds.width() - 12f)
                val y = bounds.bottom - 5f -
                    (load.toFloat() / 100f) * (bounds.height() - 10f)
                if (open) path.lineTo(x, y) else path.moveTo(x, y)
                open = true
            }
        }
        if (open) canvas.drawPath(path, plot)
    }

    private fun controls(canvas: Canvas, g: HudGeometry) {
        rect(canvas, g.menu, 11f, Color.argb(175, 36, 59, 98))
        label(canvas, if (menuExpanded) "×" else "≡",
            g.menu.centerX() - 7f, g.menu.centerY() + 6f, 20f, signal)
        if (!menuExpanded) return
        drawAction(canvas, g.mode, "MODE", false)
        drawAction(canvas, g.lock, "LOCK", false)
        drawAction(canvas, g.stop, if (state.saving) "SAVE" else "STOP", true)
    }

    private fun drawAction(canvas: Canvas, r: RectF, label: String, dangerous: Boolean) {
        rect(canvas, r, 10f,
            if (dangerous) Color.argb(204, 77, 43, 68)
            else Color.argb(198, 29, 54, 90))
        label(canvas, label, r.left + 5f, r.centerY() + 4f, 10f,
            if (dangerous) coral else signal)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = geometry ?: return
        if (width <= 0 || height <= 0) return
        val save = canvas.save()
        try {
            canvas.scale(width / g.widthDp, height / g.heightDp)
            surface(canvas, g)
            drawHeader(canvas, g)
            metrics(canvas, g)
            if (profile.layout == HudLayout.CARD) sparkline(canvas, g)
            controls(canvas, g)
            drawFailures = 0
        } catch (error: RuntimeException) {
            // Contain recoverable renderer errors; the recorder continues.
            drawFailures++
            if (drawFailures >= 3) post { actions.drawError() }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    /** Real view-coordinate touch bounds, also used by Android integration tests. */
    internal fun actionBoundsForTest(action: String): RectF {
        val g = geometry ?: return RectF()
        val design = when (action) {
            "menu" -> g.menu
            "mode" -> g.mode
            "lock" -> g.lock
            "stop" -> g.stop
            else -> RectF()
        }
        return RectF(design.left * width / g.widthDp,
            design.top * height / g.heightDp,
            design.right * width / g.widthDp,
            design.bottom * height / g.heightDp)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (profile.locked) return false
        val g = geometry ?: return false
        val x = event.x * g.widthDp / max(1, width)
        val y = event.y * g.heightDp / max(1, height)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchAction = when {
                    g.menu.contains(x, y) -> 1
                    menuExpanded && g.mode.contains(x, y) -> 2
                    menuExpanded && g.lock.contains(x, y) -> 3
                    menuExpanded && g.stop.contains(x, y) -> 4
                    else -> 5
                }
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downRawX) > slop ||
                    abs(event.rawY - downRawY) > slop) dragged = true
                if (touchAction == 5 && dragged) {
                    val dx = (event.rawX - lastRawX).roundToInt()
                    val dy = (event.rawY - lastRawY).roundToInt()
                    if (dx != 0 || dy != 0) actions.drag(dx, dy)
                }
                lastRawX = event.rawX
                lastRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (touchAction == 5 && dragged) actions.settle()
                else if (!dragged) {
                    when (touchAction) {
                        1 -> if (g.menu.contains(x, y)) {
                            menuExpanded = !menuExpanded
                            requestLayout()
                            invalidate()
                        }
                        2 -> if (g.mode.contains(x, y)) {
                            menuExpanded = false
                            actions.cycle()
                        }
                        3 -> if (g.lock.contains(x, y)) {
                            menuExpanded = false
                            actions.lock()
                        }
                        4 -> if (g.stop.contains(x, y) && !state.saving) {
                            menuExpanded = false
                            actions.stop()
                        }
                    }
                    performClick()
                }
                touchAction = 0
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (touchAction == 5 && dragged) actions.settle()
                touchAction = 0
                return true
            }
        }
        return true
    }
}
