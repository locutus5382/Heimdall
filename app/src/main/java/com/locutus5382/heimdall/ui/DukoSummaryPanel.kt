package com.locutus5382.heimdall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.locutus5382.heimdall.engine.DukoEvidenceLevel
import com.locutus5382.heimdall.recording.DukoSessionAnalyzer
import com.locutus5382.heimdall.recording.DukoSessionSummary
import java.util.Locale

/**
 * First integrated D2 calculation consumer. The production Comparison Studio
 * and DUKO PDF are deliberately separate later milestones.
 */
@Composable
internal fun DukoSummaryPanel(sessionId: Long) {
    val context = LocalContext.current.applicationContext
    val analyzer = remember(context) { DukoSessionAnalyzer(context) }
    var summary by remember(sessionId) { mutableStateOf<DukoSessionSummary?>(null) }
    var warning by remember(sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        runCatching { analyzer.analyze(sessionId) }
            .onSuccess { summary = it }
            .onFailure { warning = "Duko analysis unavailable: " +
                it.javaClass.simpleName }
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF101D32), RoundedCornerShape(15.dp))
            .border(1.dp, Color(0xFF26364F), RoundedCornerShape(15.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text("DUKO · SESSION STATISTICS", color = Color(0xFF65D5F5))
        Text("Time-weighted means exclude missing intervals. P50/P95 use " +
            "nearest-rank observed values; no FPS or causal throttling claims.",
            color = Color(0xFF94A7C4))
        if (warning != null) Text(warning!!, color = Color(0xFFF0BD62))
        val data = summary
        if (data == null && warning == null) {
            Text("Calculating…", color = Color(0xFF94A7C4))
        } else if (data != null) {
            if (data.warning != null)
                Text(data.warning!!, color = Color(0xFFF0BD62))
            val window = data.timeWindow
            if (window != null) {
                Text(
                    "Measured window: ${window.durationMs / 1000}s · " +
                        "intended cadence ${data.sampleIntervalMs}ms",
                    color = Color(0xFFEAF1FF)
                )
            }
            data.metricStatistics.forEach { metric ->
                if (metric.validSampleCount == 0) return@forEach
                val unit = metric.metric.unit.symbol
                fun fmt(value: Double?): String = value?.let {
                    String.format(Locale.US, "%.1f", it)
                } ?: "—"
                val grade = when (metric.evidence) {
                    DukoEvidenceLevel.SUPPORTED -> "SUPPORTED"
                    DukoEvidenceLevel.QUALIFIED -> "QUALIFIED"
                    DukoEvidenceLevel.INSUFFICIENT -> "LOW COVERAGE"
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(metric.metric.title.uppercase(Locale.ROOT),
                        color = Color(0xFFEAF1FF))
                    Text(
                        "Mean (time) ${fmt(metric.timeWeightedMean)} $unit · " +
                        "P50 ${fmt(metric.median)} · P95 ${fmt(metric.p95NearestRank)}",
                        color = Color(0xFF94A7C4)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            String.format(Locale.US, "Coverage %.1f%%", metric.coveragePercent),
                            color = Color(0xFF94A7C4)
                        )
                        Text(grade, color = if (metric.evidence ==
                                DukoEvidenceLevel.SUPPORTED) Color(0xFF65D5F5)
                            else Color(0xFFF0BD62))
                    }
                }
            }
            if (data.metricStatistics.none { it.validSampleCount > 0 })
                Text("No valid time-based telemetry available for this session.",
                    color = Color(0xFF94A7C4))
        }
    }
}
