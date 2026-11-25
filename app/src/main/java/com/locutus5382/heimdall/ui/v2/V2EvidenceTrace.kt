package com.locutus5382.heimdall.ui.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locutus5382.heimdall.engine.duko.TimedSignalSummary
import kotlin.math.max
import kotlin.math.min

/** A drawn segment is permitted ONLY by the engine's original continuity flag. */
internal data class V2Trace(val signal: TimedSignalSummary, val color: Color)

@Composable
internal fun V2RecordedTrace(
    title: String,
    traces: List<V2Trace>,
    fixedRange: ClosedFloatingPointRange<Double>? = null,
) {
    V2Surface {
        Text(title, color = V2Palette.cyan, fontWeight = FontWeight.Bold,
            fontSize = 12.sp)
        if (traces.isEmpty() || traces.none { it.signal.trace.isNotEmpty() }) {
            Text("Recorded trace unavailable. Missing does not mean zero.",
                color = V2Palette.muted, fontSize = 12.sp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                traces.forEach { trace ->
                    Text("● " + trace.signal.label + " (" + trace.signal.unit + ")",
                        color = trace.color, fontSize = 10.sp)
                }
            }
            val minValue = fixedRange?.start ?: traces
                .flatMap { it.signal.trace }.minOfOrNull { it.value } ?: 0.0
            val maxValue = fixedRange?.endInclusive ?: traces
                .flatMap { it.signal.trace }.maxOfOrNull { it.value } ?: 1.0
            val low = minValue.takeIf { it.isFinite() } ?: 0.0
            val high = maxValue.takeIf { it.isFinite() } ?: 1.0
            val span = max(0.01, high - low)
            Canvas(Modifier.fillMaxWidth().height(160.dp)
                .background(V2Palette.background, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)) {
                for (tick in 0..4) {
                    val yy = size.height * tick / 4f
                    drawLine(V2Palette.border, Offset(0f, yy),
                        Offset(size.width, yy), strokeWidth = 1.dp.toPx())
                }
                traces.forEach { trace ->
                    val points = trace.signal.trace
                    val window = trace.signal.window
                    val duration = window.durationMs.coerceAtLeast(1L)
                    fun position(t: Long, reading: Double): Offset {
                        val x = ((t - window.startMs).toDouble() /
                            duration.toDouble()).coerceIn(0.0, 1.0).toFloat() * size.width
                        val normalized = ((reading - low) / span).coerceIn(0.0, 1.0)
                        return Offset(x, size.height * (1f - normalized.toFloat()))
                    }
                    points.forEachIndexed { index, point ->
                        if (!point.value.isFinite()) return@forEachIndexed
                        val current = position(point.elapsedMs, point.value)
                        drawCircle(trace.color, radius = 1.9.dp.toPx(),
                            center = current)
                        if (index > 0 && point.continuousFromPrevious) {
                            val previous = points[index - 1]
                            if (previous.value.isFinite()) {
                                drawLine(trace.color,
                                    position(previous.elapsedMs, previous.value),
                                    current, strokeWidth = 1.7.dp.toPx())
                            }
                        }
                    }
                }
            }
            Text("Measured trace  •  " + formatV2Number(low) +
                "–" + formatV2Number(high) + "  •  gaps are not joined",
                color = V2Palette.muted, fontSize = 10.sp)
            traces.forEach { trace ->
                val s = trace.signal
                Text(s.label + "    min " + formatV2Number(s.minimum) +
                    " at +" + (s.minAtMs?.let {
                        formatV2Time(it - s.window.startMs)
                    } ?: "—") +
                    "    max " + formatV2Number(s.maximum) +
                    " at +" + (s.maxAtMs?.let {
                        formatV2Time(it - s.window.startMs)
                    } ?: "—") +
                    "    mean " + formatV2Number(s.arithmeticMean),
                    color = V2Palette.text, fontSize = 11.sp)
            }
        }
    }
}
