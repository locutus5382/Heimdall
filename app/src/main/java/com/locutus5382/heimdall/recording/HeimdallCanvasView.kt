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
import com.locutus5382.heimdall.engine.HudMetric
import com.locutus5382.heimdall.engine.HudProfile
import com.locutus5382.heimdall.engine.HudTelemetryHistory
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Original lightweight Canvas renderer. One window-owned View and NO sensor
 * reads, DB calls, background loops, external bitmap allocation, or 60Hz redraw.
 * Actions are dispatched back to the owning Service/controller.
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
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val graph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val graphPath = Path()
    private val stopRect = RectF()
    private val modeRect = RectF()
    private val lockRect = RectF()

    private var profile = HudProfile()
    private var history: List<HudTelemetryHistory.Point> = emptyList()
    private var current: DeviceSample? = null
    private var sessionId = 0L
    private var saving = false
    private var hotSince = 0L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var touchAction = 0
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var moved = false

    private val signal = Color.rgb(101, 213, 245)
    private val caution = Color.rgb(240, 189, 98)
    private val alert = Color.rgb(241, 107, 120)
    private val cobalt = Color.rgb(55, 108, 210)
    private val ice = Color.rgb(234, 241, 255)
    private val muted = Color.rgb(155, 182, 216)
    private val density = resources.displayMetrics.density

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription =
            "Heimdall recording HUD. Drag from the left area. " +
            "Right controls select layout, lock position, or stop recording."
    }

    fun update(
        profile: HudProfile,
        id: Long,
        sample: DeviceSample?,
        history: List<HudTelemetryHistory.Point>,
        saving: Boolean = false
    ) {
        val valid = profile.sanitized()
        val layoutChanged = this.profile.layout != valid.layout ||
            this.profile.scale != valid.scale
        this.profile = valid
        if (sessionId != id) hotSince = 0L
        sessionId = id
        current = sample
        this.history = history
        this.saving = saving
        if (layoutChanged) requestLayout()
        invalidate()
    }

    private fun designWidth() = when (profile.layout) {
        HudLayout.PILL -> 490f
        HudLayout.CARD -> 388f
        HudLayout.RIBBON -> 690f
    }
    private fun designHeight() = when (profile.layout) {
        HudLayout.PILL -> 86f
        HudLayout.CARD -> 275f
        HudLayout.RIBBON -> 70f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenW = resources.displayMetrics.widthPixels
        val usable = max(1, screenW - (16 * density).roundToInt())
        val intended = designWidth() * density * profile.scale
        val targetW = min(usable, intended.roundToInt()).coerceAtLeast(1)
        val factor = targetW / (designWidth() * density)
        val targetH = (designHeight() * density * factor).roundToInt().coerceAtLeast(1)
        setMeasuredDimension(
            resolveSize(targetW, widthMeasureSpec),
            resolveSize(targetH, heightMeasureSpec)
        )
    }

    private fun text(
        canvas: Canvas, value: String, x: Float, baseline: Float,
        size: Float, color: Int, bold: Boolean = false
    ) {
        pen.style = Paint.Style.FILL
        pen.color = color
        pen.shader = null
        pen.textSize = size
        pen.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        pen.textAlign = Paint.Align.LEFT
        canvas.drawText(value, x, baseline, pen)
    }

    private fun round(canvas: Canvas, rect: RectF, radius: Float, color: Int) {
        fill.shader = null
        fill.color = color
        fill.style = Paint.Style.FILL
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    private fun shell(canvas: Canvas, width: Float, height: Float) {
        val opacity = profile.opacity.coerceIn(.45f, .9f)
        fill.shader = LinearGradient(
            0f, 0f, width, height,
            intArrayOf(
                Color.argb((230 * opacity).roundToInt(), 32, 70, 128),
                Color.argb((250 * opacity).roundToInt(), 12, 31, 66),
                Color.argb((255 * opacity).roundToInt(), 7, 18, 40)
            ), null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(1f, 1f, width - 1f, height - 1f),
            if (profile.layout == HudLayout.PILL) 28f else 19f,
            if (profile.layout == HudLayout.PILL) 28f else 19f, fill)
        fill.shader = null
        line.color = Color.argb(195, 145, 189, 239)
        line.strokeWidth = 1f
        canvas.drawRoundRect(RectF(1f, 1f, width - 1f, height - 1f),
            if (profile.layout == HudLayout.PILL) 28f else 19f,
            if (profile.layout == HudLayout.PILL) 28f else 19f, line)
        line.color = Color.argb(83, 101, 187, 255)
        canvas.drawLine(16f, 3f, width - 20f, 3f, line)
    }

    private fun metricText(metric: HudMetric): String = when (metric) {
        HudMetric.CPU_LOAD -> current?.cpuLoadPercent
            ?.takeIf { it.isFinite() && it in 0.0..100.0 }
            ?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"
        HudMetric.GPU_CLOCK -> current?.gpuClockMHz
            ?.takeIf { it > 0L }?.let { String.format(Locale.US, "%d MHz", it) } ?: "—"
        HudMetric.BATTERY -> current?.batteryLevelPercent
            ?.takeIf { it in 0..100 }?.let { String.format(Locale.US, "%d%%", it) } ?: "—"
        HudMetric.BATTERY_TEMP -> current?.batteryTemperatureC
            ?.takeIf { it.isFinite() && it in -30.0..100.0 }
            ?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—"
        HudMetric.CPU_CLOCK -> current?.cpuClockMHz
            ?.takeIf { it > 0L }?.let { String.format(Locale.US, "%d MHz", it) } ?: "—"
        HudMetric.FREE_MEMORY -> current?.availableMemoryMiB
            ?.takeIf { it >= 0L }?.let { String.format(Locale.US, "%d MiB", it) } ?: "—"
    }

    private fun label(metric: HudMetric) = when (metric) {
        HudMetric.CPU_LOAD -> "CPU"
        HudMetric.GPU_CLOCK -> "GPU CLK"
        HudMetric.BATTERY -> "BAT"
        HudMetric.BATTERY_TEMP -> "BAT T"
        HudMetric.CPU_CLOCK -> "CPU CLK"
        HudMetric.FREE_MEMORY -> "FREE RAM"
    }

    private fun tempColor(): Int {
        val temp = current?.batteryTemperatureC
        if (temp == null || !temp.isFinite()) {
            hotSince = 0
            return ice
        }
        val now = SystemClock.elapsedRealtime()
        return when {
            temp > 42.0 -> {
                if (hotSince == 0L) hotSince = now
                if (now - hotSince >= 15_000L) alert else caution
            }
            temp >= 38.0 -> { hotSince = 0L; caution }
            else -> { hotSince = 0L; ice }
        }
    }

    private fun valueColor(metric: HudMetric): Int =
        if (metric == HudMetric.BATTERY_TEMP) tempColor() else ice

    private fun selected(count: Int) =
        HudMetric.entries.filter { it in profile.metrics }.take(count)

    private fun metricChip(
        canvas: Canvas, metric: HudMetric, x: Float, y: Float, w: Float, h: Float
    ) {
        round(canvas, RectF(x, y, x + w, y + h), 12f,
            Color.argb(93, 52, 90, 142))
        text(canvas, label(metric), x + 10f, y + 18f, 10f, muted, true)
        text(canvas, metricText(metric), x + 10f, y + 40f,
            16f, valueColor(metric), true)
    }

    private fun sparkline(canvas: Canvas, left: Float, top: Float,
                          width: Float, height: Float) {
        val points = history
        text(canvas, "CPU LOAD HISTORY", left, top - 7f, 9f, muted, true)
        round(canvas, RectF(left, top, left + width, top + height),
            8f, Color.argb(82, 3, 14, 32))
        if (points.size < 2) {
            text(canvas, "Waiting for samples", left + 10f, top + 16f, 10f, muted)
            return
        }
        graph.color = signal
        graphPath.reset()
        var active = false
        points.forEachIndexed { i, item ->
            val load = item.cpuLoad
            if (load == null) {
                if (active) {
                    canvas.drawPath(graphPath, graph)
                    graphPath.reset()
                }
                active = false
            } else {
                val x = left + 8f + i.toFloat() /
                    max(1f, (points.size - 1).toFloat()) * (width - 16f)
                val y = top + 5f + (1f - (load / 100.0).toFloat()) * (height - 10f)
                if (!active) graphPath.moveTo(x, y) else graphPath.lineTo(x, y)
                active = true
            }
        }
        if (active) canvas.drawPath(graphPath, graph)
    }

    private fun controls(canvas: Canvas, width: Float, top: Float) {
        modeRect.set(width - 174f, top, width - 126f, top + 48f)
        lockRect.set(width - 121f, top, width - 73f, top + 48f)
        stopRect.set(width - 68f, top, width - 5f, top + 48f)
        round(canvas, modeRect, 13f, Color.argb(175, 36, 67, 114))
        round(canvas, lockRect, 13f, Color.argb(175, 36, 67, 114))
        round(canvas, stopRect, 13f, Color.argb(195, 69, 49, 78))
        text(canvas, "MODE", modeRect.left + 7f, top + 29f, 11f, signal, true)
        text(canvas, "LOCK", lockRect.left + 7f, top + 29f, 11f, caution, true)
        text(canvas, if (saving) "SAVE" else "STOP",
            stopRect.left + 11f, top + 29f, 11f, alert, true)
    }

    private fun recBadge(canvas: Canvas, x: Float, y: Float) {
        fill.shader = null
        fill.color = alert
        canvas.drawCircle(x, y - 3f, 3.4f, fill)
        text(canvas, "REC", x + 10f, y, 10f, ice, true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = designWidth()
        val h = designHeight()
        if (width <= 0 || height <= 0) return
        val save = canvas.save()
        canvas.scale(width / w, height / h)
        shell(canvas, w, h)
        when (profile.layout) {
            HudLayout.PILL -> {
                text(canvas, "◉ HEIMDALL", 18f, 23f, 12f, caution, true)
                recBadge(canvas, 136f, 23f)
                text(canvas, "SESSION " + sessionId, 193f, 23f, 9f, muted)
                val selected = selected(3)
                selected.forEachIndexed { i, metric ->
                    val x = 20f + i * 100f
                    text(canvas, label(metric), x, 48f, 9f, muted, true)
                    text(canvas, metricText(metric), x, 69f,
                        15f, valueColor(metric), true)
                }
                controls(canvas, w, 33f)
            }
            HudLayout.CARD -> {
                text(canvas, "◉ HEIMDALL", 18f, 29f, 14f, caution, true)
                recBadge(canvas, 165f, 27f)
                text(canvas, "SESSION " + sessionId, 237f, 27f, 10f, muted)
                selected(6).forEachIndexed { i, metric ->
                    metricChip(canvas, metric, 16f + (i % 3) * 122f,
                        52f + (i / 3) * 64f, 115f, 57f)
                }
                sparkline(canvas, 17f, 198f, 186f, 24f)
                controls(canvas, w, 223f)
            }
            HudLayout.RIBBON -> {
                text(canvas, "◉ HEIMDALL", 14f, 26f, 12f, caution, true)
                recBadge(canvas, 18f, 48f)
                selected(4).forEachIndexed { i, metric ->
                    val x = 131f + i * 99f
                    text(canvas, label(metric), x, 26f, 9f, muted, true)
                    text(canvas, metricText(metric), x, 47f,
                        14f, valueColor(metric), true)
                }
                controls(canvas, w, 11f)
            }
        }
        canvas.restoreToCount(save)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (profile.locked) return false
        val px = event.x * designWidth() / max(1, width)
        val py = event.y * designHeight() / max(1, height)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchAction = when {
                    stopRect.contains(px, py) -> 1
                    modeRect.contains(px, py) -> 2
                    lockRect.contains(px, py) -> 3
                    else -> 4
                }
                lastRawX = event.rawX
                lastRawY = event.rawY
                downRawX = event.rawX
                downRawY = event.rawY
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchAction == 4) {
                    val dx = (event.rawX - lastRawX).roundToInt()
                    val dy = (event.rawY - lastRawY).roundToInt()
                    if (abs(event.rawX - downRawX) > slop ||
                        abs(event.rawY - downRawY) > slop) moved = true
                    if (moved && (dx != 0 || dy != 0)) actions.drag(dx, dy)
                }
                lastRawX = event.rawX
                lastRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                val finished = touchAction
                touchAction = 0
                if (finished == 4) {
                    if (moved) actions.settle() else performClick()
                } else if (!moved) {
                    when (finished) {
                        1 -> if (stopRect.contains(px, py) && !saving) actions.stop()
                        2 -> if (modeRect.contains(px, py)) actions.cycle()
                        3 -> if (lockRect.contains(px, py)) actions.lock()
                    }
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (touchAction == 4 && moved) actions.settle()
                touchAction = 0
                return true
            }
        }
        return true
    }
}
