package com.locutus5382.heimdall.ui.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoRangeSelection
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.DukoFoundationReport

private const val PAGE_SIZE = 16

/** Paging bounds compose work, while preserving the engine's real capped results. */
@Composable
private fun V2PageControls(total: Int, page: Int, onPage: (Int) -> Unit) {
    if (total <= PAGE_SIZE) return
    val pages = (total + PAGE_SIZE - 1) / PAGE_SIZE
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { onPage((page - 1).coerceAtLeast(0)) },
            enabled = page > 0) { Text("PREVIOUS") }
        Text("PAGE " + (page + 1) + " / " + pages,
            color = V2Palette.muted,
            modifier = Modifier.weight(1f), fontSize = 11.sp)
        OutlinedButton(onClick = { onPage((page + 1).coerceAtMost(pages - 1)) },
            enabled = page < pages - 1) { Text("NEXT") }
    }
}

@Composable
internal fun V2MinuteExplorer(
    report: DukoFoundationReport,
    onWindow: (DukoWindow) -> Unit,
) {
    val minutes = report.minuteAnalysis?.blocks.orEmpty()
    var page by remember(report.sessionId, report.window) { mutableIntStateOf(0) }
    val safePage = page.coerceIn(0, ((minutes.size - 1).coerceAtLeast(0)) / PAGE_SIZE)
    V2Surface {
        Text("ONE-MINUTE WINDOWS  /  ORIGINAL EVIDENCE",
            color = V2Palette.cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("Each row is a true 60-second interval (last may be partial). " +
            "Selecting one rereads original source rows and recalculates DUKO.",
            color = V2Palette.muted, fontSize = 11.sp)
        val total = report.minuteAnalysis?.totalBlocks
        if (report.minuteAnalysis?.truncated == true) Text(
            "Displayed first " + minutes.size + " of " + total +
                " intervals. Select a later time range to inspect omitted minutes.",
            color = V2Palette.amber, fontSize = 11.sp)
    }
    if (minutes.isEmpty()) V2Surface {
        Text("No qualifying minute windows.", color = V2Palette.muted)
    }
    V2PageControls(minutes.size, safePage) { page = it }
    minutes.drop(safePage * PAGE_SIZE).take(PAGE_SIZE).forEach { block ->
        V2Surface {
            Row(Modifier.fillMaxWidth()) {
                Text("MINUTE " + block.ordinal +
                    (if (block.partial) " / PARTIAL" else ""),
                    color = V2Palette.cyan, fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                Text(block.recordedRows.toString() + " rows",
                    color = V2Palette.muted, fontSize = 11.sp)
            }
            val cpu = block.metrics[DukoMetric.CPU_LOAD]
            val gpu = block.metrics[DukoMetric.GPU_LOAD]
            Text("CPU median " + formatV2Number(cpu?.median) +
                "% (P95 " + formatV2Number(cpu?.p95) +
                "%)   ·   GPU median " + formatV2Number(gpu?.median) + "%",
                color = V2Palette.text, fontSize = 11.sp)
            Text("Coverage: CPU " + formatV2Number(cpu?.coverage?.times(100), 0) +
                "%  /  GPU " + formatV2Number(gpu?.coverage?.times(100), 0) +
                "%  ·  " + (cpu?.evidence?.name ?: "UNKNOWN"),
                color = V2Palette.muted, fontSize = 10.sp)
            OutlinedButton(
                enabled = block.window.durationMs >= DukoRangeSelection.MIN_WINDOW_MS,
                onClick = { onWindow(block.window) }
            ) { Text("INSPECT THIS MINUTE", fontSize = 11.sp) }
        }
    }
    V2PageControls(minutes.size, safePage) { page = it }
}

@Composable
internal fun V2EventExplorer(
    report: DukoFoundationReport,
    fullBounds: DukoWindow,
    onWindow: (DukoWindow) -> Unit,
) {
    val events = report.events?.events.orEmpty()
    var page by remember(report.sessionId, report.window) { mutableIntStateOf(0) }
    val safePage = page.coerceIn(0, ((events.size - 1).coerceAtLeast(0)) / PAGE_SIZE)
    V2Surface {
        Text("UNIFIED EVENT TIMELINE", color = V2Palette.cyan,
            fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("Events are derived from recorded observations and have experimental " +
            "thresholds. Tap an event to reanalyze its nearby original evidence.",
            color = V2Palette.muted, fontSize = 11.sp)
        val dropped = report.events?.droppedEvents ?: 0
        if (dropped > 0) Text("Engine capped event display: " + dropped +
            " additional events not listed. Narrow the time range.",
            color = V2Palette.amber, fontSize = 11.sp)
    }
    if (events.isEmpty()) V2Surface {
        Text("No qualifying events in this interval.", color = V2Palette.muted)
    }
    V2PageControls(events.size, safePage) { page = it }
    events.drop(safePage * PAGE_SIZE).take(PAGE_SIZE).forEach { event ->
        V2Surface {
            Text("+" + formatV2Time(event.startMs - fullBounds.startMs) +
                "  /  " + event.type.name.replace('_', ' '),
                color = V2Palette.cyan, fontSize = 12.sp)
            Text(event.detail, color = V2Palette.text, fontSize = 12.sp)
            Text("Duration " + formatV2Time(event.durationMs) +
                if (event.heuristic) "  ·  HEURISTIC" else "  ·  OBSERVED",
                color = V2Palette.muted, fontSize = 10.sp)
            OutlinedButton(
                onClick = {
                    DukoRangeSelection.focus(
                        fullBounds, event.startMs, event.endMs,
                        contextMs = 30_000L
                    )?.let(onWindow)
                }
            ) { Text("FOCUS TIME WINDOW", fontSize = 11.sp) }
        }
    }
    V2PageControls(events.size, safePage) { page = it }
}

@Composable
internal fun V2InsightsExplorer(
    report: DukoFoundationReport,
    onWindow: (DukoWindow) -> Unit,
) {
    val insights = report.insights
    V2Surface {
        Text("DUKO EVIDENCE-QUALIFIED INSIGHTS", color = V2Palette.cyan,
            fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("Descriptive system behavior, not verified gameplay performance, " +
            "driver rankings or thermal-throttle causality.",
            color = V2Palette.muted, fontSize = 11.sp)
    }
    val phases = insights?.workload?.segments.orEmpty()
    if (phases.isNotEmpty()) V2Surface {
        Text("WORKLOAD PHASES", color = V2Palette.amber, fontSize = 12.sp)
        phases.take(24).forEach { phase ->
            Text("+" + formatV2Time(phase.window.startMs - report.window.startMs) +
                "   " + phase.phase.name + "   /   " + phase.evidence.name,
                color = V2Palette.text, fontSize = 11.sp)
        }
        if (phases.size > 24) Text("First 24 displayed; narrow the analysis range.",
            color = V2Palette.muted, fontSize = 10.sp)
    }
    val suggestions = insights?.suggestions?.intervals.orEmpty()
    if (suggestions.isNotEmpty()) V2Surface {
        Text("SUGGESTED ANALYSIS WINDOWS",
            color = V2Palette.cyan, fontSize = 12.sp)
        suggestions.forEachIndexed { index, suggested ->
            Text("Candidate " + (index + 1) +
                "  /  " + formatV2Time(suggested.window.durationMs) +
                "  /  " + suggested.evidence.name,
                color = V2Palette.text, fontSize = 11.sp)
            Text(suggested.rationale, color = V2Palette.muted, fontSize = 11.sp)
            OutlinedButton(onClick = { onWindow(suggested.window) }) {
                Text("ANALYZE CANDIDATE " + (index + 1), fontSize = 11.sp)
            }
        }
    }
    val findings = insights?.findings.orEmpty()
    if (findings.isEmpty()) V2Surface {
        Text("No supported conclusions in this interval.",
            color = V2Palette.muted)
    }
    findings.forEach { finding ->
        V2Surface {
            Text(finding.kind.name + " / " + finding.evidence.name,
                color = V2Palette.cyan, fontSize = 11.sp)
            Text(finding.summary, color = V2Palette.text, fontSize = 12.sp)
            Text("LIMITATION: " + finding.limitation,
                color = V2Palette.muted, fontSize = 11.sp)
            finding.window?.let { w ->
                OutlinedButton(
                    enabled = w.durationMs >= DukoRangeSelection.MIN_WINDOW_MS,
                    onClick = { onWindow(w) }
                ) { Text("INSPECT FINDING", fontSize = 11.sp) }
            }
        }
    }
}
