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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoRangeSelection
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.DukoFoundationReport
import com.locutus5382.heimdall.recording.DukoSessionMarker
import com.locutus5382.heimdall.ui.theme.BifrostColors
import java.util.Locale
import kotlin.math.max

private val m20Colors = listOf(
    BifrostColors.Signal, BifrostColors.Amber, BifrostColors.Coral, Color(0xFFA5B4FC)
)
private fun m20Number(v: Double?): String = v?.takeIf { it.isFinite() }
    ?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
private fun m20Offset(bounds: DukoWindow, t: Long): String {
    val sec = (t - bounds.startMs).coerceAtLeast(0) / 1000L
    return String.format(Locale.US, "%02d:%02d", sec / 60L, sec % 60L)
}

@Composable
internal fun DukoRangeWorkbench(
    sessionId: Long,
    original: DukoFoundationReport,
    state: SessionsUiState,
    model: SessionScreenViewModel,
    markers: List<DukoSessionMarker>,
    onExportRange: () -> Unit,
) {
    val bounds = original.window
    var from by rememberSaveable(sessionId, "range-from") { mutableFloatStateOf(0f) }
    var to by rememberSaveable(sessionId, "range-to") { mutableFloatStateOf(1f) }
    val proposed = DukoRangeSelection.select(bounds, from, to)
    val selected = state.analysisWindow
    val current = if (selected == null) original else
        state.selectedAnalysis?.takeIf { it.window == selected }
    Column(
        modifier = Modifier.fillMaxWidth().background(BifrostColors.Blade,
            RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text("G / INTERACTIVE ANALYSIS", color = BifrostColors.Amber)
        Text("Drag the start/end handles, then APPLY. DUKO rereads the original " +
            "selected samples and recalculates all statistics.",
            fontSize = 11.sp, color = BifrostColors.Muted)
        RangeSlider(
            value = from..to,
            onValueChange = { from = it.start.coerceIn(0f, 1f);
                to = it.endInclusive.coerceIn(0f, 1f) },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Recorded range start and end controls"
            }
        )
        Text("+" + (proposed?.let { m20Offset(bounds, it.startMs) } ?: "—") +
            "  –  +" + (proposed?.let { m20Offset(bounds, it.endMs) } ?: "—"),
            color = BifrostColors.Ice)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { proposed?.let { model.selectAnalysisWindow(sessionId, it) } },
                enabled = proposed != null && !state.readingSelection,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) { Text("APPLY RANGE") }
            OutlinedButton(
                onClick = { from = 0f; to = 1f;
                    model.selectAnalysisWindow(sessionId, null) },
                enabled = !state.readingSelection,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) { Text("FULL SESSION") }
        }
        val candidates = original.insights?.suggestions?.intervals.orEmpty()
        if (candidates.isNotEmpty()) {
            Text("DUKO suggested comparison intervals", color = BifrostColors.Muted,
                fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                candidates.forEachIndexed { i, candidate ->
                    FilterChip(
                        selected = selected == candidate.window,
                        enabled = !state.readingSelection,
                        onClick = {
                            from = ((candidate.window.startMs - bounds.startMs).toDouble() /
                                bounds.durationMs).toFloat()
                            to = ((candidate.window.endMs - bounds.startMs).toDouble() /
                                bounds.durationMs).toFloat()
                            model.selectAnalysisWindow(sessionId, candidate.window)
                        },
                        label = { Text("CANDIDATE " + (i + 1)) }
                    )
                }
            }
        }
        if (state.readingSelection) {
            Text("Reading bounded original evidence…", color = BifrostColors.Amber)
        } else if (selected != null && current == null) {
            Text("This interval could not be analyzed. Reset or choose another range.",
                color = BifrostColors.Coral)
        } else current?.let { report ->
            Text((if (selected == null) "FULL" else "SELECTED") +
                " INTERVAL · +" + m20Offset(bounds, report.window.startMs) +
                "–+" + m20Offset(bounds, report.window.endMs),
                color = BifrostColors.Signal)
            for (metric in listOf(DukoMetric.CPU_LOAD, DukoMetric.GPU_LOAD)) {
                val stats = report.integrity.metrics[metric]
                Text(metric.name.replace('_', ' ') + ": median " +
                    m20Number(stats?.median) + "% · P95 " +
                    m20Number(stats?.p95) + "% · coverage " +
                    m20Number(stats?.coverage?.times(100.0)) + "% · " +
                    (stats?.evidence?.name ?: "UNKNOWN"),
                    color = BifrostColors.Ice, fontSize = 11.sp)
            }
        }
        OutlinedButton(
            onClick = onExportRange,
            enabled = selected != null && current != null && !state.exporting &&
                !state.readingSelection && state.pendingExportId == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("EXPORT SELECTED ORIGINAL CSV") }
        Text("Sample IDs, original time_ms and elapsed_ms are retained. " +
            "No interpolation or telemetry edits.", color = BifrostColors.Muted,
            fontSize = 10.sp)
    }
    if (current != null) {
        DukoLayeredSignals(current, markers)
        DukoDistributionBars(current)
    }
}

private enum class M20Graph(val label: String, val units: String, val keys: List<String>) {
    LOAD("LOAD", "%", listOf("CPU LOAD", "GPU LOAD")),
    CLOCKS("CLOCKS", "MHz", listOf("CPU CLOCK", "GPU CLOCK")),
    THERMAL("THERMALS", "°C", listOf("BATTERY TEMP", "CPU TEMP", "GPU TEMP")),
    PRESSURE("PSI", "%", listOf("CPU PSI SOME AVG10", "MEMORY PSI SOME AVG10",
        "MEMORY PSI FULL AVG10"))
}

@Composable
internal fun DukoLayeredSignals(
    report: DukoFoundationReport,
    markers: List<DukoSessionMarker> = emptyList()
) {
    var selected by rememberSaveable(report.sessionId, "layer-choice") {
        mutableStateOf(M20Graph.LOAD.name)
    }
    val graph = M20Graph.entries.firstOrNull { it.name == selected } ?: M20Graph.LOAD
    val traces = graph.keys.mapNotNull { key ->
        report.timelines[key]?.takeIf { it.samples > 0 && it.trace.isNotEmpty() }
            ?.let { key to it }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("G / LAYERED TELEMETRY", color = BifrostColors.Amber)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M20Graph.entries.forEach { option ->
                FilterChip(selected = graph == option,
                    onClick = { selected = option.name },
                    label = { Text(option.label) })
            }
        }
        traces.forEachIndexed { i, entry ->
            Text("● " + entry.first + ": mean " +
                m20Number(entry.second.arithmeticMean) + " " + graph.units +
                " · peak " + m20Number(entry.second.maximum),
                fontSize = 11.sp, color = m20Colors[i % m20Colors.size])
        }
        if (traces.isEmpty()) {
            Text("This sensor group has no recorded evidence.",
                color = BifrostColors.Muted)
            return@Column
        }
        val low = traces.mapNotNull { it.second.minimum }.minOrNull() ?: 0.0
        val high = traces.mapNotNull { it.second.maximum }.maxOrNull() ?: 1.0
        val pad = max(0.5, (high - low) * 0.08)
        val span = max(0.1, high - low + pad * 2)
        val window = report.window
        val events = report.events?.events.orEmpty().filter { it.heuristic }
        val shownMarkers = markers.filter { it.elapsedMs in window.startMs..window.endMs }
        Canvas(
            modifier = Modifier.fillMaxWidth().height(190.dp)
                .background(BifrostColors.Midnight, RoundedCornerShape(12.dp))
                .semantics {
                    contentDescription = "Measured layered signal graph with " +
                        events.size + " heuristic events and " +
                        shownMarkers.size + " user annotations"
                }
        ) {
            val x0 = 12.dp.toPx(); val x1 = size.width - x0
            val y0 = 12.dp.toPx(); val y1 = size.height - y0
            if (x1 <= x0 || y1 <= y0) return@Canvas
            fun x(t: Long): Float = x0 + ((t - window.startMs).toDouble() /
                window.durationMs).coerceIn(0.0, 1.0).toFloat() * (x1 - x0)
            fun y(v: Double): Float = y1 - ((v - (low - pad)) /
                span).coerceIn(0.0, 1.0).toFloat() * (y1 - y0)
            for (i in 0..4) {
                val yy = y0 + (y1 - y0) * i / 4f
                drawLine(BifrostColors.Muted.copy(alpha = .18f),
                    Offset(x0, yy), Offset(x1, yy), 1.dp.toPx())
            }
            traces.forEachIndexed { i, (_, trace) ->
                val color = m20Colors[i % m20Colors.size]
                val gap = max(8_000L, window.durationMs / 150L * 3L)
                trace.trace.zipWithNext().forEach { (a, b) ->
                    if (b.elapsedMs > a.elapsedMs &&
                        b.elapsedMs - a.elapsedMs <= gap)
                        drawLine(color, Offset(x(a.elapsedMs), y(a.value)),
                            Offset(x(b.elapsedMs), y(b.value)), 2.dp.toPx())
                }
                trace.trace.singleOrNull()?.let {
                    drawCircle(color, 3.dp.toPx(), Offset(x(it.elapsedMs), y(it.value)))
                }
            }
            events.take(250).forEach {
                drawCircle(BifrostColors.Coral, 2.dp.toPx(), Offset(x(it.startMs), y1))
            }
            shownMarkers.take(200).forEach {
                drawLine(BifrostColors.Amber, Offset(x(it.elapsedMs), y0),
                    Offset(x(it.elapsedMs), y0 + 11.dp.toPx()), 2.dp.toPx())
            }
        }
        Text("Amber ticks = manual markers; coral dots = heuristic event starts. " +
            "Only same-unit streams share a graph. Missing intervals are disconnected.",
            fontSize = 10.sp, color = BifrostColors.Muted)
    }
}

@Composable
internal fun DukoDistributionBars(report: DukoFoundationReport) {
    val activity = report.coreActivity ?: return
    if (activity.cores.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("G / CORE & CLUSTER ACTIVITY DISTRIBUTION",
            color = BifrostColors.Amber)
        Text("Time-weighted sampled utilization, NOT game-thread ownership.",
            fontSize = 11.sp, color = BifrostColors.Muted)
        activity.cores.take(16).forEach { core ->
            val load = core.averageLoadPercent?.takeIf { it in 0.0..100.0 }
            Text("CPU " + core.coreId + " · " + m20Number(load) +
                "% · led " + (core.ledObservedMs / 1000L) +
                "s · covered " + (core.measuredMs / 1000L) + "s",
                fontSize = 11.sp, color = BifrostColors.Ice)
            Canvas(Modifier.fillMaxWidth().height(9.dp)
                .background(BifrostColors.Midnight, RoundedCornerShape(5.dp))) {
                if (load != null) drawRect(BifrostColors.Signal,
                    size = Size(size.width * (load / 100).toFloat(), size.height))
            }
        }
        if (activity.clusters.isNotEmpty()) {
            Text("POLICY CLUSTERS (fully readable members only)",
                color = BifrostColors.Amber, fontSize = 11.sp)
            activity.clusters.forEach { cluster ->
                val load = cluster.averageLoadPercent?.takeIf { it in 0.0..100.0 }
                Text(cluster.policyId + " · " + m20Number(load) +
                    "% · qualified " + cluster.measuredMs / 1000L + "s",
                    fontSize = 11.sp, color = BifrostColors.Ice)
                Canvas(Modifier.fillMaxWidth().height(9.dp)
                    .background(BifrostColors.Midnight, RoundedCornerShape(5.dp))) {
                    if (load != null) drawRect(BifrostColors.Amber,
                        size = Size(size.width * (load / 100).toFloat(), size.height))
                }
            }
        }
    }
}
