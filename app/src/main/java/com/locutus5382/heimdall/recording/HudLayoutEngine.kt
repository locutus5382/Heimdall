package com.locutus5382.heimdall.recording

import android.graphics.Paint
import android.graphics.RectF
import com.locutus5382.heimdall.engine.HudLayout
import com.locutus5382.heimdall.engine.HudProfile
import com.locutus5382.heimdall.engine.HudReadout
import com.locutus5382.heimdall.engine.HudRenderState
import kotlin.math.max
import kotlin.math.min

/** All geometry is computed on state/profile/size changes, never in onDraw. */
internal data class HudSlot(
    val readout: HudReadout, val bounds: RectF,
    val labelX: Float, val labelY: Float,
    val valueX: Float, val valueY: Float,
)

internal data class HudGeometry(
    val widthDp: Float,
    val heightDp: Float,
    val slots: List<HudSlot>,
    val menu: RectF,
    val mode: RectF,
    val lock: RectF,
    val stop: RectF,
    val graph: RectF?,
    val headerBaseline: Float,
    val detailBaseline: Float,
)

/**
 * Compact HUD geometry is content driven. Each slot measures its actual label
 * and value using the same type sizes as the Canvas painter. If the screen is
 * narrow, trailing metrics are omitted rather than squeezed into overlap.
 * All active controls have a 48 x 48dp minimum touch region.
 */
internal class HudLayoutEngine(private val fontFactor: Float = 1f) {
    private val measure = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun textWidth(value: String, sp: Float): Float {
        measure.textSize = sp * fontFactor
        return measure.measureText(value)
    }

    private fun slotWidth(readout: HudReadout, valueSp: Float): Float =
        max(textWidth(readout.label, 10f),
            textWidth(readout.value, valueSp)) + 22f

    fun calculate(
        profile: HudProfile,
        state: HudRenderState,
        availableDp: Float,
        expanded: Boolean,
    ): HudGeometry {
        val cap = max(1f, availableDp)
        val metrics = state.readouts.take(profile.layout.metricLimit())
        val slots = ArrayList<HudSlot>(metrics.size)
        val graph: RectF?
        var w: Float
        var h: Float
        var header: Float
        var detail: Float
        var menu: RectF

        when (profile.layout) {
            HudLayout.PILL, HudLayout.RIBBON -> {
                val ribbon = profile.layout == HudLayout.RIBBON
                val captionWidth = max(
                    textWidth("● REC " + state.elapsedLabel, 12f) + 16f,
                    if (ribbon) 94f else 107f
                )
                val fieldSize = if (ribbon) 14f else 14.5f
                val requested = metrics.map { slotWidth(it, fieldSize) }
                val natural = 16f + captionWidth + 6f +
                    requested.sum() + requested.size * 6f + 48f + 8f
                val fitted = min(natural, cap)
                val minHeader = captionWidth + 48f + 30f
                val remaining = max(0f, fitted - (16f + captionWidth + 6f + 48f + 8f))
                var used = 0f
                var x = 16f + captionWidth + 6f
                metrics.forEachIndexed { index, field ->
                    val required = requested[index] + 6f
                    if (used + required <= remaining) {
                        slots += HudSlot(field,
                            RectF(x, 6f, x + requested[index], 57f),
                            x + 5f, 25f, x + 5f, 45f)
                        used += required
                        x += required
                    }
                }
                w = min(cap, max(minHeader, natural))
                h = if (expanded) 116f else 67f
                header = 39f
                detail = 0f
                menu = RectF(w - 55f, 9f, w - 7f, 57f)
                graph = null
            }
            HudLayout.CARD -> {
                val measured = metrics.maxOfOrNull { slotWidth(it, 17f) } ?: 105f
                w = min(cap, max(290f, 2f * (measured + 12f) + 26f))
                h = if (expanded) 324f else 275f
                header = 31f
                detail = 0f
                val gap = 10f
                val cell = max(1f, (w - 28f - gap) / 2f)
                metrics.take(4).forEachIndexed { index, field ->
                    val col = index % 2
                    val row = index / 2
                    val x = 14f + col * (cell + gap)
                    val y = 54f + row * 64f
                    slots += HudSlot(field, RectF(x, y, x + cell, y + 57f),
                        x + 12f, y + 19f, x + 12f, y + 44f)
                }
                graph = RectF(16f, 212f, w - 16f, 252f)
                menu = RectF(w - 57f, 7f, w - 9f, 55f)
            }
            HudLayout.SPINE -> {
                // The vertical width must accommodate measured numerals. A
                // forced 48dp column would clip 680 MHz on many font scales.
                val field = metrics.take(3)
                val widest = field.maxOfOrNull { slotWidth(it, 14f) } ?: 68f
                w = min(cap, max(79f, widest + 8f))
                h = (97f + field.size * 61f) +
                    if (expanded) 148f else 0f
                header = 25f
                detail = 0f
                field.forEachIndexed { i, item ->
                    val y = 48f + i * 61f
                    slots += HudSlot(item, RectF(8f, y, w - 8f, y + 55f),
                        14f, y + 17f, 14f, y + 42f)
                }
                menu = RectF((w - 48f) / 2f, h - (if (expanded) 172f else 54f),
                    (w + 48f) / 2f, h - (if (expanded) 124f else 6f))
                graph = null
            }
        }

        // Keep the action areas inside the actual view and always 48dp tall.
        val mode: RectF
        val lock: RectF
        val stop: RectF
        if (!expanded) {
            mode = RectF()
            lock = RectF()
            stop = RectF()
        } else if (profile.layout == HudLayout.SPINE) {
            mode = RectF((w - 48f) / 2f, h - 151f,
                (w + 48f) / 2f, h - 103f)
            lock = RectF((w - 48f) / 2f, h - 101f,
                (w + 48f) / 2f, h - 53f)
            stop = RectF((w - 48f) / 2f, h - 51f,
                (w + 48f) / 2f, h - 3f)
        } else {
            val right = w - 9f
            stop = RectF(right - 48f, h - 52f, right, h - 4f)
            lock = RectF(right - 98f, h - 52f, right - 50f, h - 4f)
            mode = RectF(right - 148f, h - 52f, right - 100f, h - 4f)
        }
        return HudGeometry(w, h, slots, menu, mode, lock, stop, graph, header, detail)
    }
}
