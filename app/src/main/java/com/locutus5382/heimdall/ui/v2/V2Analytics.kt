package com.locutus5382.heimdall.ui.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoMetricAnalysis
import com.locutus5382.heimdall.recording.DukoFoundationReport
import com.locutus5382.heimdall.ui.SessionScreenViewModel

/** Direct, evidence-first navigation. No old SessionHistoryPanel or inner accordion. */
private enum class V2AnalysisSection(val label: String) {
    OVERVIEW("Overview"), MINUTES("Minutes"), CORES("Cores"),
    CLOCKS("Clocks & caps"), THERMALS("Thermals"), PRESSURE("PSI / RAM"),
    EVENTS("Events"), INSIGHTS("Insights")
}

@Composable
internal fun V2AnalyticsScreen(
    model: SessionScreenViewModel,
    refreshKey: Long,
    onCompare: (Long) -> Unit,
) {
    val state by model.ui.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(V2AnalysisSection.OVERVIEW.name) }
    LaunchedEffect(refreshKey) { model.refresh(refreshKey) }
    val newest = state.history.firstOrNull()?.id
    LaunchedEffect(newest, state.loadingHistory, state.expandedId) {
        // Do not override an explicit RUNS selection while its archive refresh
        // is still underway. The ViewModel checks pending focus ownership.
        if (newest != null) model.focusNewestIfNone()
    }
    val selectedId = state.expandedId
    val selected = state.history.firstOrNull { it.id == selectedId }
    val report = if (state.analysisWindow == null) state.detail?.duko
        else state.selectedAnalysis?.takeIf { it.window == state.analysisWindow }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                V2PageTitle("DUKO / RECORDED EVIDENCE", "Analysis",
                    "Measured signals, clear coverage, no invented readings")
            }
            if (selectedId != null) OutlinedButton(
                onClick = { onCompare(selectedId) }
            ) { Text("COMPARE", color = V2Palette.cyan) }
        }
        if (state.history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                state.history.take(12).forEach { run ->
                    V2AnalysisChip("RUN #" + run.id, run.id == selectedId) {
                        if (selectedId != run.id) model.focusSession(run.id)
                    }
                }
            }
        }
        if (state.loadingHistory || state.reading || state.readingSelection)
            Text("Reading stored measurements…", color = V2Palette.muted)
        if (selected == null && !state.loadingHistory)
            V2Surface { Text("Select a completed recording from RUNS.",
                color = V2Palette.muted) }
        state.notice?.let { Text(it, color = V2Palette.coral) }
        if (selected != null) {
            Text("SESSION #" + selected.id + "    " +
                formatV2Time(selected.endedAtMs - selected.startedAtMs) +
                "    " + selected.samples + " samples    " + selected.reason,
                color = V2Palette.amber, fontSize = 12.sp)
        }
        if (selectedId != null && state.detail?.duko != null) {
            V2AnalysisRange(selectedId, state.detail!!.duko!!, state, model)
        }
        val ov = state.detail?.overview
        if (ov != null) {
            val cpuEvidence = report?.integrity?.metrics?.get(DukoMetric.CPU_LOAD)
            val gpuEvidence = report?.integrity?.metrics?.get(DukoMetric.GPU_LOAD)
            val fullRunOnly = state.analysisWindow == null
            val kpis = listOf(
                Triple("CPU MEDIAN",
                    (cpuEvidence?.median ?: ov.cpuLoad?.median?.takeIf { fullRunOnly })
                        ?.let { formatV2Number(it) + "%" } ?: "—", V2Palette.cyan),
                Triple("CPU P95",
                    (cpuEvidence?.p95 ?: ov.cpuLoad?.p95?.takeIf { fullRunOnly })
                        ?.let { formatV2Number(it) + "%" } ?: "—", V2Palette.cyan),
                Triple("GPU MEDIAN",
                    gpuEvidence?.median?.let { formatV2Number(it) + "%" } ?: "—",
                    V2Palette.amber),
                Triple("BATTERY / FULL RUN",
                    (ov.batteryAtStart?.toString() ?: "—") + " → " +
                        (ov.batteryAtEnd?.toString() ?: "—") + "%",
                    V2Palette.teal)
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val perRow = if (maxWidth >= 630.dp) 4 else 2
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    kpis.chunked(perRow).forEach { group ->
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            group.forEach { (label, value, color) ->
                                Box(Modifier.weight(1f)) {
                                    V2Value(label, value, accent = color)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (report != null) {
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                V2AnalysisSection.entries.forEach { item ->
                    V2AnalysisChip(item.label, section == item.name) {
                        section = item.name
                    }
                }
            }
            when (V2AnalysisSection.entries.firstOrNull { it.name == section }
                ?: V2AnalysisSection.OVERVIEW) {
                V2AnalysisSection.OVERVIEW -> V2Overview(report)
                V2AnalysisSection.MINUTES -> V2Minutes(report)
                V2AnalysisSection.CORES -> V2CoreDashboard(report)
                V2AnalysisSection.CLOCKS -> V2ClocksDashboard(report)
                V2AnalysisSection.THERMALS -> {
                    V2Thermals(report)
                    V2ThermalExtras(report)
                }
                V2AnalysisSection.PRESSURE -> V2PressureDashboard(report)
                V2AnalysisSection.EVENTS -> V2Events(report)
                V2AnalysisSection.INSIGHTS -> V2Insights(report)
            }
        } else if (!state.reading && selected != null) {
            V2Surface { Text("No qualified monotonic DUKO analysis for this session.",
                color = V2Palette.muted) }
        }
    }
}

@Composable
private fun V2AnalysisChip(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp)
        .background(if (isSelected) V2Palette.raised else V2Palette.panel,
            RoundedCornerShape(9.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 13.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = if (isSelected) V2Palette.cyan else V2Palette.muted,
            fontSize = 12.sp)
    }
}

@Composable
private fun V2Overview(report: DukoFoundationReport) {
    val signals = report.integrity.metrics
    V2RecordedTrace(
        title = "CPU / GPU LOAD   •   RECORDED TIMELINE",
        traces = listOfNotNull(
            report.timelines["CPU LOAD"]?.let { V2Trace(it, V2Palette.cyan) },
            report.timelines["GPU LOAD"]?.let { V2Trace(it, V2Palette.amber) },
        ),
        fixedRange = 0.0..100.0,
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = listOf(
            DukoMetric.CPU_LOAD, DukoMetric.GPU_LOAD,
            DukoMetric.CPU_TEMPERATURE, DukoMetric.GPU_TEMPERATURE
        )
        if (maxWidth >= 490.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                available.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { metric ->
                            Box(Modifier.weight(1f)) {
                                V2MetricEvidence(
                                    metric.name.replace('_', ' '),
                                    signals[metric], metric.unit.symbol
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                available.forEach { metric ->
                    V2MetricEvidence(
                        metric.name.replace('_', ' '),
                        signals[metric], metric.unit.symbol)
                }
            }
        }
    }
    V2Surface {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("60-SECOND WINDOWS", color = V2Palette.cyan,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                val minute = report.minuteAnalysis
                Text(
                    if (minute == null) "Unavailable in this session"
                    else minute.reportedBlocks.toString() + " observed windows" +
                        if (minute.truncated) " · limited display" else "",
                    color = V2Palette.text, fontSize = 12.sp
                )
            }
            Text("OPEN MINUTES →", color = V2Palette.muted, fontSize = 10.sp)
        }
        Text("Evidence: " + report.confidence.sessionOutcome +
            "   •   Battery calibration: " + report.confidence.batteryCalibration,
            color = V2Palette.muted, fontSize = 11.sp)
        Text("System measurements, not rendered FPS or game-only power.",
            color = V2Palette.subtle, fontSize = 10.sp)
    }
}

@Composable
private fun V2MetricEvidence(label: String, result: DukoMetricAnalysis?, unit: String) {
    V2Surface {
        Text(label + "  /  " + (result?.evidence?.name ?: "UNAVAILABLE"),
            color = V2Palette.cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("Median " + formatV2Number(result?.median) + unit +
            "    P95 " + formatV2Number(result?.p95) + unit +
            "    Min " + formatV2Number(result?.minimum) + unit +
            "    Max " + formatV2Number(result?.maximum) + unit,
            color = V2Palette.text, fontSize = 13.sp)
        Text("Coverage " + formatV2Number(result?.coverage?.times(100), 0) +
            "%    Min at " + (result?.minimumAtElapsedMs?.let {
                formatV2Time(it - result.window.startMs)
            } ?: "—") + "    Max at " + (result?.maximumAtElapsedMs?.let {
                formatV2Time(it - result.window.startMs)
            } ?: "—"), color = V2Palette.muted, fontSize = 11.sp)
    }
}

/** Plot actual engine-calculated 60-second medians. Do not bridge nulls. */
@Composable
private fun V2MinuteTrend(cpu: List<Double?>, gpu: List<Double?>) {
    Canvas(Modifier.fillMaxWidth().height(140.dp)
        .background(V2Palette.background, RoundedCornerShape(8.dp))
        .padding(12.dp)) {
        for (i in 0..4) {
            val y = size.height * i / 4f
            drawLine(V2Palette.border, Offset(0f, y),
                Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        fun drawSeries(series: List<Double?>, color: Color) {
            if (series.isEmpty()) return
            series.forEachIndexed { i, v ->
                val current = v?.takeIf { it.isFinite() && it in 0.0..100.0 }
                    ?: return@forEachIndexed
                val x = if (series.size == 1) size.width / 2f
                    else size.width * i / (series.size - 1f)
                val y = size.height * (1f - current.toFloat() / 100f)
                drawCircle(color, 2.8.dp.toPx(), Offset(x, y))
                if (i != 0) {
                    val previous = series[i - 1]?.takeIf {
                        it.isFinite() && it in 0.0..100.0
                    } ?: return@forEachIndexed
                    val px = size.width * (i - 1) / (series.size - 1f)
                    val py = size.height * (1f - previous.toFloat() / 100f)
                    drawLine(color, Offset(px, py), Offset(x, y),
                        strokeWidth = 2.dp.toPx())
                }
            }
        }
        drawSeries(cpu, V2Palette.cyan)
        drawSeries(gpu, V2Palette.amber)
    }
}

@Composable
private fun V2Minutes(report: DukoFoundationReport) {
    val blocks = report.minuteAnalysis?.blocks.orEmpty()
    if (blocks.isEmpty()) V2Surface {
        Text("Minute-by-minute evidence unavailable", color = V2Palette.muted)
    }
    blocks.take(60).forEach { block ->
        V2Surface {
            Text("MINUTE " + block.ordinal + " · " + block.recordedRows +
                " source rows" + if (block.partial) " · partial" else "",
                color = V2Palette.cyan, fontSize = 12.sp)
            Text("CPU median " +
                formatV2Number(block.metrics[DukoMetric.CPU_LOAD]?.median) +
                "%     GPU median " +
                formatV2Number(block.metrics[DukoMetric.GPU_LOAD]?.median) + "%",
                color = V2Palette.text)
        }
    }
    if (blocks.size > 60) Text("Showing first 60 minute windows.",
        color = V2Palette.muted)
}

@Composable
private fun V2Cores(report: DukoFoundationReport) {
    V2Surface {
        Text("INDIVIDUAL CPU CORES", color = V2Palette.cyan,
            fontWeight = FontWeight.Bold)
        if (report.perCoreTimelines.isEmpty())
            Text("Per-core telemetry unavailable", color = V2Palette.muted)
        else {
            Text(report.perCoreTimelines.size.toString() +
                " actual per-core signal streams", color = V2Palette.text)
            report.perCoreTimelines.keys.sorted().forEach { core ->
                Text("Core " + core + " · measured signal available",
                    color = V2Palette.muted, fontSize = 12.sp)
            }
        }
        Text("System-wide core utilization is not a game-thread trace.",
            color = V2Palette.muted, fontSize = 11.sp)
    }
    report.perCoreTimelines.toSortedMap().entries.take(8).forEach { (core, trace) ->
        V2RecordedTrace(
            title = "CORE " + core + "  /  RECORDED UTILIZATION",
            traces = listOf(V2Trace(trace, V2Palette.cyan)),
            fixedRange = 0.0..100.0,
        )
    }
    if (report.perCoreTimelines.size > 8) V2Surface {
        Text("More core traces recorded; showing first eight.",
            color = V2Palette.muted)
    }
}

@Composable
private fun V2Policies(report: DukoFoundationReport) {
    if (report.cpuPolicyResidency.isEmpty()) V2Surface {
        Text("CPU policy readings unavailable", color = V2Palette.muted)
    }
    report.cpuPolicyResidency.forEach { policy ->
        V2Surface {
            Text(policy.policyId + "  ·  " + policy.observedSamples +
                " clock samples", color = V2Palette.cyan)
            Text("Related cores: " +
                policy.relatedCores.joinToString().ifBlank { "unknown" },
                color = V2Palette.muted)
        }
    }
    V2Surface { Text("A measured frequency ceiling never identifies " +
        "whether ClusterTune or thermal protection caused it.",
        color = V2Palette.muted, fontSize = 11.sp) }
}

@Composable
private fun V2Thermals(report: DukoFoundationReport) {
    V2RecordedTrace(
        title = "THERMAL SENSORS  /  RECORDED TIMELINE",
        traces = listOfNotNull(
            report.timelines["BATTERY TEMP"]?.let { V2Trace(it, V2Palette.amber) },
            report.timelines["CPU TEMP"]?.let { V2Trace(it, V2Palette.cyan) },
            report.timelines["GPU TEMP"]?.let { V2Trace(it, V2Palette.coral) },
        ),
    )
    listOf(DukoMetric.BATTERY_TEMPERATURE, DukoMetric.CPU_TEMPERATURE,
        DukoMetric.GPU_TEMPERATURE).forEach { metric ->
        V2MetricEvidence(metric.name.replace('_', ' '),
            report.integrity.metrics[metric], "°C")
    }
}

@Composable
private fun V2Events(report: DukoFoundationReport) {
    val entries = report.events?.events.orEmpty()
    if (entries.isEmpty()) V2Surface {
        Text("No qualifying events in the selected recording.",
            color = V2Palette.muted)
    }
    entries.take(60).forEach { event ->
        V2Surface {
            Text(formatV2Time(event.startMs - report.window.startMs) +
                "  /  " + event.type.name.replace('_', ' '),
                color = V2Palette.cyan, fontSize = 12.sp)
            Text(event.detail, color = V2Palette.text, fontSize = 12.sp)
            if (event.heuristic) Text("Diagnostic heuristic",
                color = V2Palette.muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun V2Insights(report: DukoFoundationReport) {
    val findings = report.insights?.findings.orEmpty()
    if (findings.isEmpty()) V2Surface {
        Text("No supported conclusions in this session.", color = V2Palette.muted)
    }
    findings.forEach { item ->
        V2Surface {
            Text(item.kind.name + "   /   " + item.evidence.name,
                color = V2Palette.cyan, fontSize = 11.sp)
            Text(item.summary, color = V2Palette.text, fontSize = 13.sp)
            Text(item.limitation, color = V2Palette.muted, fontSize = 11.sp)
        }
    }
}
