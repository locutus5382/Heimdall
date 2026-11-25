package com.locutus5382.heimdall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locutus5382.heimdall.engine.duko.DukoComparisonQuality
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.ui.theme.BifrostColors
import java.util.Locale

private fun multiNumber(value: Double?): String =
    value?.takeIf { it.isFinite() }?.let {
        String.format(Locale.US, "%.2f", it)
    } ?: "N/A"

@Composable
internal fun DukoM21MultiRunPanel(model: DukoMultiRunViewModel) {
    val state by model.ui.collectAsStateWithLifecycle()
    var visible by rememberSaveable { mutableIntStateOf(20) }
    val baseline = state.selected.firstOrNull()
    val enabled = !state.busy && !state.writing && state.pending == null
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(BifrostColors.Editorial, RoundedCornerShape(17.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text("DUKO / MULTI-RUN", color = BifrostColors.Amber,
            fontWeight = FontWeight.Bold)
        Text("Compare 2-6 saved sessions: one baseline against up to five " +
            "independently analyzed candidates. This is device telemetry, not FPS.",
            color = BifrostColors.Muted, fontSize = 12.sp)
        Text("SELECT RUNS · " + state.selected.size + "/6 · baseline first",
            color = BifrostColors.Amber, fontSize = 12.sp)
        if (state.loading) Text("Reading saved session headers...",
            color = BifrostColors.Muted)
        if (!state.loading && state.sessions.size < 2)
            Text("Two completed recordings are required.", color = BifrostColors.Muted)
        val rows = (state.sessions.take(visible) +
            state.sessions.filter { it.id in state.selected })
            .distinctBy { it.id }
        rows.forEach { session ->
            val selected = session.id in state.selected
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(BifrostColors.Blade, RoundedCornerShape(9.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SESSION #" + session.id,
                        color = if (baseline == session.id)
                            BifrostColors.Amber else BifrostColors.Ice,
                        fontSize = 12.sp)
                    Text(session.samples.toString() + " samples" +
                        if (baseline == session.id) " · BASELINE" else
                        if (selected) " · CANDIDATE" else "",
                        color = BifrostColors.Muted, fontSize = 10.sp)
                }
                OutlinedButton(
                    enabled = enabled && (selected || state.selected.size < 6),
                    onClick = { model.toggle(session.id) },
                    modifier = Modifier.heightIn(min = 46.dp),
                ) { Text(if (selected) "REMOVE" else "ADD", fontSize = 11.sp) }
                if (selected && baseline != session.id) {
                    OutlinedButton(
                        enabled = enabled,
                        onClick = { model.baseline(session.id) },
                        modifier = Modifier.heightIn(min = 46.dp),
                    ) { Text("SET A", fontSize = 11.sp) }
                }
            }
        }
        if (visible < state.sessions.size) {
            OutlinedButton(
                onClick = { visible = (visible + 20).coerceAtMost(100) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text("SHOW MORE SAVED RUNS") }
        }
        OutlinedButton(
            enabled = enabled,
            onClick = model::cyclePreset,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("EACH RUN / " + state.preset.label.uppercase()) }
        Text("Preset fractions are applied to each run's own recorded " +
            "monotonic window. A shared fraction does not establish matched gameplay.",
            color = BifrostColors.Muted, fontSize = 11.sp)
        OutlinedButton(
            enabled = state.canCompare && state.pending == null && !state.writing,
            onClick = model::analyze,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("ANALYZE " + state.selected.size + " RUNS") }
        if (state.busy) Text("Analyzing source readings independently...",
            color = BifrostColors.Amber)
        state.issue?.let { Text(it, color = BifrostColors.Coral) }
        state.analyzed?.let { report ->
            Text("I / MULTI-RUN EVIDENCE", color = BifrostColors.Amber,
                fontWeight = FontWeight.Bold)
            Text(report.sessionCount.toString() + " runs · " +
                report.qualifiedPairs + "/" + report.candidates.size +
                " candidate pairs are comparable (some metrics may still be blocked)",
                color = BifrostColors.Ice)
            report.warnings.take(6).forEach {
                Text(it.replace('_', ' '),
                    color = BifrostColors.Muted, fontSize = 10.sp)
            }
            Text("OBSERVED METRIC PATTERNS", color = BifrostColors.Amber)
            val metrics = listOf(
                DukoMetric.CPU_LOAD, DukoMetric.GPU_LOAD,
                DukoMetric.CPU_CLOCK, DukoMetric.GPU_CLOCK,
                DukoMetric.BATTERY_TEMPERATURE,
                DukoMetric.CPU_TEMPERATURE, DukoMetric.GPU_TEMPERATURE,
                DukoMetric.AVAILABLE_RAM,
            )
            metrics.forEach { metric ->
                report.metricPatterns[metric]?.let { pattern ->
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(BifrostColors.Blade, RoundedCornerShape(9.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(metric.name.replace('_', ' ') +
                            " · " + pattern.direction.name,
                            color = BifrostColors.Ice, fontSize = 12.sp)
                        Text("Baseline " + multiNumber(pattern.baselineMean) +
                            " " + metric.unit.symbol + " · " +
                            pattern.comparableCount + "/" +
                            pattern.candidateCount + " qualified deltas",
                            color = BifrostColors.Muted, fontSize = 11.sp)
                        if (pattern.comparableCount >= 2) {
                            Text("Median B-A " + multiNumber(pattern.medianDelta) +
                                " · min " + multiNumber(pattern.minDelta) +
                                " · max " + multiNumber(pattern.maxDelta) +
                                " " + metric.unit.symbol,
                                color = BifrostColors.Amber, fontSize = 11.sp)
                        } else Text("Insufficient comparable candidates for " +
                            "a direction; unavailable deltas excluded.",
                            color = BifrostColors.Muted, fontSize = 11.sp)
                    }
                }
            }
            Text("BASELINE-TO-CANDIDATE PAIRS", color = BifrostColors.Amber)
            report.comparisons.forEachIndexed { index, comparison ->
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(BifrostColors.Blade, RoundedCornerShape(9.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("A #" + report.baseline.sessionId + " to B #" +
                        report.candidates[index].sessionId +
                        " · " + comparison.quality.name,
                        color = if (comparison.quality ==
                            DukoComparisonQuality.NOT_COMPARABLE)
                            BifrostColors.Coral else BifrostColors.Ice)
                    val good = comparison.metrics.values.count {
                        it.quality != DukoComparisonQuality.NOT_COMPARABLE
                    }
                    Text(good.toString() + "/" + comparison.metrics.size +
                        " measurable deltas; checks applied per metric.",
                        color = BifrostColors.Muted, fontSize = 11.sp)
                    comparison.contextWarnings.take(4).forEach { note ->
                        Text(note.replace('_', ' '),
                            color = BifrostColors.Muted, fontSize = 10.sp)
                    }
                }
            }
            Text("J / DUKO COMPARISON REPORT", color = BifrostColors.Amber)
            DukoM21VisualControls(
                allowed = enabled && state.analyzed != null,
                onPrepare = model::prepareVisual,
                onResult = model::onVisualResult,
                status = state.exportStatus,
            )
            Text("Offline PDF/PNG. Reports do not infer game FPS, causal " +
                "performance improvements, thermal-throttle causes or winners.",
                color = BifrostColors.Muted, fontSize = 10.sp)
        }
    }
}
