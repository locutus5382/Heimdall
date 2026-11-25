package com.locutus5382.heimdall.ui.v2

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locutus5382.heimdall.ui.CompareScreenViewModel
import com.locutus5382.heimdall.ui.DukoMultiRunViewModel
import com.locutus5382.heimdall.ui.DiagnosticsViewModel

/**
 * New layouts; old DUKO comparisons and device probes remain the sole
 * calculation/data source. Visual-report editing is an R2 presentation task.
 */
@Composable
internal fun V2CompareScreen(
    model: CompareScreenViewModel,
    multi: DukoMultiRunViewModel,
    refreshKey: Long,
) {
    val state by model.ui.collectAsStateWithLifecycle()
    val many by multi.ui.collectAsStateWithLifecycle()
    var multiMode by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(refreshKey) {
        model.refresh(refreshKey)
        multi.refresh(refreshKey)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        V2PageTitle("DUKO / MULTI-SESSION EVIDENCE", "Compare",
            "Descriptive analysis. Different workloads cannot produce a winner.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { multiMode = false }) {
                Text("A/B", color = if (!multiMode) V2Palette.cyan else V2Palette.muted)
            }
            OutlinedButton(onClick = { multiMode = true }) {
                Text("MULTI RUN", color = if (multiMode) V2Palette.cyan else V2Palette.muted)
            }
        }
        if (!multiMode) {
            V2Surface {
                Text("BASELINE A", color = V2Palette.cyan, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.sessions.take(7).forEach { run ->
                        V2RunSelectButton("#" + run.id, selected = state.a == run.id) {
                            model.selectA(run.id)
                        }
                    }
                }
                Text("CANDIDATE B", color = V2Palette.cyan, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.sessions.take(7).forEach { run ->
                        V2RunSelectButton("#" + run.id, selected = state.b == run.id) {
                            model.selectB(run.id)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(onClick = model::cycleA) {
                        Text("A: " + state.presetA.name, color = V2Palette.muted)
                    }
                    OutlinedButton(onClick = model::cycleB) {
                        Text("B: " + state.presetB.name, color = V2Palette.muted)
                    }
                }
                Button(onClick = model::compare, enabled = state.canCompare) {
                    Text(if (state.busy) "ANALYZING…" else "COMPARE SELECTED RUNS")
                }
                state.issue?.let { Text(it, color = V2Palette.coral) }
            }
            state.report?.let { result ->
                V2Surface {
                    Text("A #" + result.sessionA + "  →  B #" + result.sessionB +
                        "   /   " + result.quality,
                        color = V2Palette.cyan, fontWeight = FontWeight.Bold)
                    result.contextWarnings.forEach { warning ->
                        Text(warning.replace('_', ' '), color = V2Palette.amber,
                            fontSize = 11.sp)
                    }
                }
                result.metrics.values.forEach { item ->
                    V2Surface {
                        Text(item.metric.name.replace('_', ' ') +
                            "  /  " + item.quality,
                            color = V2Palette.cyan, fontSize = 12.sp)
                        Text("A " + formatV2Number(item.aMean) +
                            "  •  B " + formatV2Number(item.bMean) +
                            "  •  Δ " + formatV2Number(item.delta),
                            color = V2Palette.text)
                        if (item.delta == null) Text("Delta blocked by evidence conditions",
                            color = V2Palette.muted, fontSize = 11.sp)
                    }
                }
            }
        } else {
            V2Surface {
                Text("SELECT 2–6 SAVED RUNS", color = V2Palette.cyan,
                    fontWeight = FontWeight.Bold)
                Text("First selected run is baseline. Use SET BASELINE to promote another.",
                    color = V2Palette.muted, fontSize = 12.sp)
                many.sessions.take(12).forEach { run ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { multi.toggle(run.id) }) {
                            Text((if (run.id in many.selected) "✓ " else "+ ") +
                                "#" + run.id, color = V2Palette.cyan)
                        }
                        if (run.id in many.selected) OutlinedButton(
                            onClick = { multi.baseline(run.id) }
                        ) { Text(if (many.selected.firstOrNull() == run.id)
                            "BASELINE A" else "SET BASELINE") }
                    }
                }
                OutlinedButton(onClick = multi::cyclePreset) {
                    Text("WINDOW " + many.preset.name)
                }
                Button(onClick = multi::analyze, enabled = many.canCompare) {
                    Text(if (many.busy) "ANALYZING…" else "ANALYZE SELECTED RUNS")
                }
                many.issue?.let { Text(it, color = V2Palette.coral) }
            }
            many.analyzed?.let { analyzed ->
                V2Surface {
                    Text(analyzed.sessionCount.toString() + " runs  •  " +
                        analyzed.qualifiedPairs + " eligible comparisons",
                        color = V2Palette.cyan, fontWeight = FontWeight.Bold)
                    Text("Baseline #" + analyzed.baseline.sessionId,
                        color = V2Palette.text)
                }
                analyzed.metricPatterns.values.forEach { item ->
                    V2Surface {
                        Text(item.metric.name + "  /  " + item.direction,
                            color = V2Palette.cyan)
                        Text("Median qualified Δ " + formatV2Number(item.medianDelta) +
                            "    " + item.comparableCount + " usable candidates",
                            color = V2Palette.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun V2RunSelectButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.background(if (selected) V2Palette.raised else V2Palette.background,
        RoundedCornerShape(7.dp)).clickable(onClick = onClick)
        .padding(horizontal = 13.dp, vertical = 12.dp)) {
        Text(label, color = if (selected) V2Palette.cyan else V2Palette.muted,
            fontSize = 12.sp)
    }
}

@Composable
internal fun V2ToolsScreen(model: DiagnosticsViewModel) {
    val state by model.ui.collectAsStateWithLifecycle()
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> model.onExportResult(uri) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        V2PageTitle("LOCAL DEVICE UTILITIES", "Tools",
            "Diagnostics are read-only; nothing is uploaded or modified")
        V2Surface {
            Text("DEVICE INSPECTION", color = V2Palette.cyan,
                fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = model::inspect, enabled = !state.inspecting) {
                    Text(if (state.inspecting) "INSPECTING…" else "RUN DIAGNOSTICS")
                }
                OutlinedButton(
                    onClick = { model.requestExport()?.let(exportPicker::launch) },
                    enabled = state.snapshot != null && !state.inspecting &&
                        !state.writing && state.pendingExportText == null
                ) { Text("EXPORT TXT") }
            }
            state.message?.let { Text(it, color = V2Palette.amber) }
        }
        state.snapshot?.let { snapshot ->
            V2Surface {
                Text("DEVICE CAPABILITY SNAPSHOT", color = V2Palette.cyan)
                snapshot.lines.forEach { (key, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(key, modifier = Modifier.weight(1f),
                            color = V2Palette.muted, fontSize = 11.sp)
                        Text(value, modifier = Modifier.weight(1f),
                            color = V2Palette.text, fontSize = 11.sp)
                    }
                }
            }
        }
        V2Surface {
            Text("HUD 2.0  /  REBUILD IN PROGRESS",
                color = V2Palette.amber, fontWeight = FontWeight.Bold)
            Text("The rejected overlay is disabled in this branch. " +
                "It will not start with the recorder. " +
                "A new micro HUD and Studio will be implemented independently.",
                color = V2Palette.muted, fontSize = 12.sp)
        }
    }
}
