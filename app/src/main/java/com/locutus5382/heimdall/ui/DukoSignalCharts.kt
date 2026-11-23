package com.locutus5382.heimdall.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locutus5382.heimdall.engine.duko.CoreActivityReport
import com.locutus5382.heimdall.engine.duko.FrequencyCapTimeline
import com.locutus5382.heimdall.engine.duko.TimedSignalSummary
import com.locutus5382.heimdall.recording.DukoFoundationReport
import com.locutus5382.heimdall.ui.theme.BifrostColors
import java.util.Locale
import kotlin.math.max

private fun signalNumber(n: Double?): String =
    n?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
private fun signalTime(windowStart: Long, t: Long?): String = if (t == null) "unavailable" else {
    val seconds = ((t - windowStart).coerceAtLeast(0L) / 1000L)
    "%02d:%02d".format(Locale.US, seconds / 60L, seconds % 60L)
}

/**
 * Real sampled-history graph. Only measured bucket averages are connected,
 * interrupted intervals are broken, min/max dots use original sample times.
 * Thin amber mean line and understated standard deviation band refer to the
 * active analysis window, not other games or synthetic readings.
 */
@Composable
internal fun DukoSignalCharts(report: DukoFoundationReport) {
    val all = linkedMapOf<String, TimedSignalSummary>()
    all.putAll(report.timelines)
    report.perCoreTimelines.toSortedMap().forEach { (id, trace) ->
        all["CORE $id"] = trace
    }
    if (all.isEmpty()) return
    var chosen by rememberSaveable(report.sessionId) {
        mutableStateOf("CPU LOAD")
    }
    val selected = all[chosen] ?: all.values.first()

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("DUKO / RECORDED TIMELINE", color = BifrostColors.Amber)
        Text("Select a measured signal. The thin amber line is its arithmetic " +
            "mean; the band is ±1 sample standard deviation.",
            color = BifrostColors.Muted, fontSize = 11.sp)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            all.forEach { (key, item) ->
                FilterChip(
                    selected = selected.label == item.label,
                    onClick = { chosen = key },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(key, fontSize = 11.sp) }
                )
            }
        }
        Text(selected.label + "  /  " + selected.samples + " actual readings",
            color = BifrostColors.Ice)
        if (selected.samples == 0 || selected.minimum == null ||
            selected.maximum == null || selected.trace.isEmpty()) {
            Text("No readable samples for this signal. This is not zero " +
                "utilization or zero pressure.", color = BifrostColors.Muted)
        } else {
            val statsText = "Minimum " + signalNumber(selected.minimum) +
                " " + selected.unit + " at +" +
                signalTime(selected.window.startMs, selected.minAtMs) +
                "; maximum " + signalNumber(selected.maximum) + " " +
                selected.unit + " at +" +
                signalTime(selected.window.startMs, selected.maxAtMs) +
                "; arithmetic mean " + signalNumber(selected.arithmeticMean) +
                "; sample standard deviation " +
                signalNumber(selected.sampleStandardDeviation)
            Canvas(
                modifier = Modifier.fillMaxWidth().height(185.dp)
                    .background(BifrostColors.Midnight, RoundedCornerShape(12.dp))
                    .semantics { contentDescription = statsText }
            ) {
                val w = size.width
                val h = size.height
                if (w < 2f || h < 2f) return@Canvas
                val left = 12.dp.toPx()
                val right = w - 12.dp.toPx()
                val top = 14.dp.toPx()
                val bottom = h - 14.dp.toPx()
                val mean = selected.arithmeticMean ?: 0.0
                val sigma = selected.sampleStandardDeviation ?: 0.0
                var low = minOf(selected.minimum!!, mean - sigma)
                var high = maxOf(selected.maximum!!, mean + sigma)
                val padding = max(1.0, (high - low) * 0.12)
                low -= padding
                high += padding
                val span = max(0.0001, high - low)
                fun x(t: Long): Float = left +
                    ((t - selected.window.startMs).toDouble() /
                        selected.window.durationMs).coerceIn(0.0, 1.0).toFloat() *
                        (right - left)
                fun y(v: Double): Float = bottom -
                    ((v - low) / span).coerceIn(0.0, 1.0).toFloat() * (bottom - top)
                for (i in 0..3) {
                    val yy = top + (bottom - top) * i / 3f
                    drawLine(BifrostColors.Muted.copy(alpha = 0.13f),
                        Offset(left, yy), Offset(right, yy), 1.dp.toPx())
                }
                if (sigma > 0.0) {
                    val yTop = y(mean + sigma)
                    val yBottom = y(mean - sigma)
                    drawRect(BifrostColors.Amber.copy(alpha = 0.10f),
                        Offset(left, yTop),
                        Size(right - left, yBottom - yTop))
                }
                drawLine(BifrostColors.Amber.copy(alpha = 0.88f),
                    Offset(left, y(mean)), Offset(right, y(mean)), 1.dp.toPx())
                // A missing interval must not be smoothed into invented load.
                val maximumGap = maxOf(8_000L,
                    (selected.window.durationMs / 150L) * 3L)
                selected.trace.zipWithNext().forEach { (a, b) ->
                    if (b.elapsedMs > a.elapsedMs &&
                        b.elapsedMs - a.elapsedMs <= maximumGap) {
                        drawLine(BifrostColors.Signal,
                            Offset(x(a.elapsedMs), y(a.value)),
                            Offset(x(b.elapsedMs), y(b.value)), 2.dp.toPx())
                    }
                }
                val minTime = selected.minAtMs
                val maxTime = selected.maxAtMs
                if (minTime != null) drawCircle(BifrostColors.Signal,
                    4.dp.toPx(), Offset(x(minTime), y(selected.minimum!!)))
                if (maxTime != null) drawCircle(BifrostColors.Coral,
                    4.dp.toPx(), Offset(x(maxTime), y(selected.maximum!!)))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("● MIN " + signalNumber(selected.minimum) + " " +
                    selected.unit + "  +" +
                    signalTime(selected.window.startMs, selected.minAtMs),
                    color = BifrostColors.Signal, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                Text("● PEAK " + signalNumber(selected.maximum) + " " +
                    selected.unit + "  +" +
                    signalTime(selected.window.startMs, selected.maxAtMs),
                    color = BifrostColors.Coral, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
            }
            Text("MEAN " + signalNumber(selected.arithmeticMean) + " " +
                selected.unit + "   ·   SAMPLE SD ±" +
                signalNumber(selected.sampleStandardDeviation) + " " + selected.unit,
                color = BifrostColors.Amber, fontSize = 12.sp)
            Text("Recorded sample-bucket averages. Gaps remain unconnected; " +
                "PSI values are system-wide rolling 10-second percentages.",
                color = BifrostColors.Muted, fontSize = 10.sp)
        }
    }
}

@Composable
internal fun DukoCoreAndPressurePanel(report: DukoFoundationReport) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("DUKO / CPU CORE & CLUSTER ACTIVITY", color = BifrostColors.Amber)
        val core = report.coreActivity
        if (core == null || !core.available) {
            Text("Per-core utilization unavailable. Android may restrict /proc/stat; " +
                "core clock speeds are not substituted as CPU load.",
                color = BifrostColors.Muted)
        } else {
            Text("Busiest by measured time-weighted average: " +
                (core.busiestCore?.let { "CPU $it" } ?: "unavailable") +
                " · coverage " + signalNumber(core.coverageFraction * 100.0) + "%",
                color = BifrostColors.Ice)
            core.cores.take(16).forEachIndexed { index, item ->
                Column(Modifier.fillMaxWidth()
                    .background(BifrostColors.Blade, RoundedCornerShape(8.dp))
                    .padding(9.dp)) {
                    Text("#${index + 1}  CPU ${item.coreId}  ·  mean " +
                        signalNumber(item.averageLoadPercent) + "%  ·  " +
                        "peak " + signalNumber(item.maximumLoadPercent) + "% at +" +
                        signalTime(core.window.startMs, item.maximumAtMs),
                        color = BifrostColors.Ice, fontSize = 12.sp)
                    Text("Sample SD ±" + signalNumber(item.sampleStandardDeviation) +
                        "%  ·  leading " + (item.ledObservedMs / 1000L) +
                        "s  ·  measured " + (item.measuredMs / 1000L) + "s",
                        color = BifrostColors.Muted, fontSize = 11.sp)
                }
            }
            if (core.clusters.isNotEmpty()) {
                Text("CPU POLICY / CLUSTER ACTIVITY", color = BifrostColors.Amber)
                core.clusters.forEach { cluster ->
                    Text(cluster.policyId + " (CPU " +
                        cluster.cores.joinToString(",") + ") · " +
                        signalNumber(cluster.averageLoadPercent) +
                        "% mean · " + (cluster.measuredMs / 1000L) +
                        "s qualified", color = BifrostColors.Ice, fontSize = 12.sp)
                }
            }
            Text("Busiest refers to observed load, not game-thread ownership. " +
                "Unavailable or partially readable clusters remain unranked.",
                color = BifrostColors.Muted, fontSize = 11.sp)
        }

        Text("DUKO / OBSERVED CPU FREQUENCY CEILINGS", color = BifrostColors.Amber)
        val caps: FrequencyCapTimeline? = report.frequencyCaps
        if (caps == null || caps.policies.none { it.observedMs > 0L }) {
            Text("Insufficient policy-ceiling data to assess persistent restrictions.",
                color = BifrostColors.Muted)
        } else {
            caps.policies.filter { it.observedMs > 0 }.forEach { cap ->
                Text(cap.policyId + " · restricted " +
                    signalNumber(cap.restrictedFraction * 100.0) +
                    "% of measured time (" + (cap.restrictedMs / 1000L) +
                    "s) · longest continuous " +
                    (cap.longestContinuousRestrictedMs / 1000L) + "s",
                    color = BifrostColors.Ice)
                if (cap.presetLike) {
                    Text("PRESET-LIKE SUSTAINED LIMIT: present from the start " +
                        "through most measured intervals; cause NOT verified." +
                        if (report.declaredClusterTuneEnabled == true)
                            " ClusterTune was declared ON by user."
                        else " ClusterTune is not confirmed.",
                        color = BifrostColors.Amber, fontSize = 11.sp)
                }
            }
            caps.events.take(12).forEach { event ->
                val capLabel = if (event.restricted) "CAP OBSERVED" else "NO CAP OBSERVED"
                Text("+" + signalTime(caps.window.startMs, event.elapsedMs) +
                    "  " + event.policyId + "  " + capLabel +
                    "  (" + event.activeMaxMHz + "/" +
                    event.hardwareMaxMHz + " MHz)",
                    color = BifrostColors.Muted, fontSize = 11.sp)
            }
            Text("A frequency ceiling is not proof of thermal throttling, " +
                "ClusterTune, a fan setting or a named software cause.",
                color = BifrostColors.Muted, fontSize = 11.sp)
        }
    }
}
