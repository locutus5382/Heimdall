package com.locutus5382.heimdall.ui

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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locutus5382.heimdall.engine.duko.DukoEvidence
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.engine.duko.DukoWorkloadPhase
import com.locutus5382.heimdall.recording.DukoFoundationReport
import com.locutus5382.heimdall.ui.theme.BifrostColors
import java.util.Locale

private fun phaseColor(phase: DukoWorkloadPhase): Color = when (phase) {
    DukoWorkloadPhase.IDLE -> BifrostColors.Muted
    DukoWorkloadPhase.ACTIVE -> BifrostColors.Signal
    DukoWorkloadPhase.SUSTAINED_LOAD -> BifrostColors.Coral
    DukoWorkloadPhase.RECOVERY -> BifrostColors.Amber
    DukoWorkloadPhase.UNKNOWN -> Color.DarkGray
}

private fun offset(base: DukoWindow, elapsedMs: Long): String {
    val totalSeconds = ((elapsedMs - base.startMs).coerceAtLeast(0L)) / 1000L
    return String.format(Locale.US, "%02d:%02d",
        totalSeconds / 60L, totalSeconds % 60L)
}

@Composable
internal fun DukoM19InsightsPanel(report: DukoFoundationReport) {
    val insights = report.insights
    if (insights == null) {
        Text("Intelligent analysis unavailable for this recording.",
            color = BifrostColors.Muted)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("A / WORKLOAD PHASES", color = BifrostColors.Amber)
        Text("60-second heuristic classification. UNKNOWN means the data does not " +
            "justify an idle or active label; recovery means reduced observed load, " +
            "not verified cooling.", fontSize = 11.sp, color = BifrostColors.Muted)
        Text("PHASE TIMELINE · idle / active / sustained / recovery / unknown",
            color = BifrostColors.Muted, fontSize = 10.sp)
        Canvas(
            modifier = Modifier.fillMaxWidth().height(18.dp)
                .semantics { contentDescription = "Observed workload phase timeline" }
        ) {
            val duration = report.window.durationMs.toDouble()
            insights.workload.segments.forEach { segment ->
                val start = ((segment.window.startMs - report.window.startMs) /
                    duration).coerceIn(0.0, 1.0).toFloat()
                val end = ((segment.window.endMs - report.window.startMs) /
                    duration).coerceIn(0.0, 1.0).toFloat()
                val left = start * size.width
                val right = end * size.width
                if (right > left) {
                    drawRect(
                        color = phaseColor(segment.phase).copy(
                            alpha = if (segment.evidence == DukoEvidence.INSUFFICIENT)
                                0.65f else 1.0f),
                        topLeft = Offset(left, 0f),
                        size = Size(right - left, size.height),
                    )
                }
            }
        }
        if (insights.workload.truncated) {
            Text("Only the first 360 minutes were classified; select a later " +
                "analysis window to inspect the rest.", fontSize = 11.sp,
                color = BifrostColors.Amber)
        }
        if (insights.workload.segments.isEmpty()) {
            Text("No analyzable intervals.", color = BifrostColors.Muted)
        }
        insights.workload.segments.take(24).forEach { segment ->
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(BifrostColors.Blade, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(segment.phase.name.replace('_', ' '),
                        color = if (segment.phase.name == "UNKNOWN")
                            BifrostColors.Muted else BifrostColors.Signal,
                        fontSize = 12.sp)
                    Text(segment.evidence.name, color = BifrostColors.Muted,
                        fontSize = 10.sp)
                }
                Text("+" + offset(report.window, segment.window.startMs) +
                    " – +" + offset(report.window, segment.window.endMs) +
                    " · " + segment.minuteCount + " minute block(s)",
                    color = BifrostColors.Ice, fontSize = 11.sp)
                Text("Minimum qualified load coverage: " +
                    String.format(Locale.US, "%.0f%%",
                        segment.minimumLoadCoverage * 100.0),
                    color = BifrostColors.Muted, fontSize = 11.sp)
                if (segment.warnings.any { it.contains("GPU_LOAD_UNAVAILABLE") }) {
                    Text("GPU load unavailable: CPU-based classification only.",
                        color = BifrostColors.Amber, fontSize = 10.sp)
                }
            }
        }
        if (insights.workload.segments.size > 24) Text(
            "Additional phases omitted from this preview (" +
                (insights.workload.segments.size - 24) + ").",
            color = BifrostColors.Muted, fontSize = 11.sp)

        Text("E / SUGGESTED COMPARISON WINDOWS", color = BifrostColors.Amber)
        Text("These are candidates, not proof that two runs have equivalent " +
            "workloads. Match game scene, driver, performance/fan mode and " +
            "recording conditions before A/B comparison.",
            color = BifrostColors.Muted, fontSize = 11.sp)
        if (insights.suggestions.intervals.isEmpty()) {
            Text("No suitable stable 120-second interval in the analyzed minutes.",
                color = BifrostColors.Muted)
        }
        insights.suggestions.intervals.forEachIndexed { index, suggestion ->
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(BifrostColors.Blade, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("CANDIDATE " + (index + 1) + " · " +
                    suggestion.evidence.name, color = BifrostColors.Signal)
                Text("+" + offset(report.window, suggestion.window.startMs) +
                    " – +" + offset(report.window, suggestion.window.endMs) +
                    " · " + suggestion.minuteCount + " complete minutes",
                    color = BifrostColors.Ice, fontSize = 12.sp)
                Text(suggestion.phase.name.replace('_', ' ') +
                    " · " + String.format(Locale.US, "%.0f%%",
                        suggestion.minimumLoadCoverage * 100.0) +
                    " minimum coverage", color = BifrostColors.Muted,
                    fontSize = 11.sp)
                Text(suggestion.rationale, color = BifrostColors.Muted,
                    fontSize = 10.sp)
            }
        }

        Text("F / DUKO FINDINGS", color = BifrostColors.Amber)
        insights.findings.forEach { finding ->
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(BifrostColors.Blade, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(finding.kind.name.replace('_', ' ') + " · " +
                    finding.evidence.name, color = if (
                    finding.evidence == DukoEvidence.INSUFFICIENT
                ) BifrostColors.Muted else BifrostColors.Signal, fontSize = 11.sp)
                Text(finding.summary, color = BifrostColors.Ice, fontSize = 12.sp)
                finding.window?.let {
                    Text("+" + offset(report.window, it.startMs) +
                        " – +" + offset(report.window, it.endMs),
                        color = BifrostColors.Muted, fontSize = 11.sp)
                }
                Text(finding.limitation, color = BifrostColors.Muted,
                    fontSize = 11.sp)
            }
        }
        Text("DUKO conclusions are evidence-qualified. No FPS data, causal " +
            "thermal-throttling determination or automatic ClusterTune " +
            "detection is asserted.", color = BifrostColors.Muted,
            fontSize = 11.sp)
    }
}
