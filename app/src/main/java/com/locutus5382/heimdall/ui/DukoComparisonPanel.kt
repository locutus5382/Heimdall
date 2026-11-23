package com.locutus5382.heimdall.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.locutus5382.heimdall.engine.duko.DukoMetricDifference
import com.locutus5382.heimdall.engine.duko.DukoComparisonQuality
import com.locutus5382.heimdall.engine.duko.DukoComparisonReport
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoWindow
import java.util.Locale

private val AbInk = Color(0xFFF2F6FC)
private val AbSubtle = Color(0xFF94A7C4)
private val AbAmber = Color(0xFFF0BD62)
private val AbSlate = Color(0xFF111C31)
private fun abNumber(n: Double?) =
    n?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

/**
 * Relative presets use recorded monotonic bounds, not wall-clock timestamps.
 * Integer arithmetic avoids floating-point rounding for long sessions.
 * A one-millisecond or empty result is rejected rather than expanded.
 */
internal enum class AbWindowPreset(val label: String) {
    FULL("Full recording"), MIDDLE_80("Middle 80%"), LAST_50("Last 50%"), CUSTOM("Custom seconds");

    fun select(bounds: DukoWindow): DukoWindow? {
        if (this == CUSTOM) return null
        if (this == FULL) return bounds
        val duration = bounds.durationMs
        if (duration < 2L) return null
        val startOffset = when (this) {
            FULL -> 0L
            MIDDLE_80 -> duration / 10L
            LAST_50 -> duration / 2L
            CUSTOM -> return null
        }
        val endOffset = when (this) {
            FULL -> duration
            MIDDLE_80 -> duration - duration / 10L
            LAST_50 -> duration
            CUSTOM -> return null
        }
        if (endOffset <= startOffset) return null
        return DukoWindow(bounds.startMs + startOffset,
            bounds.startMs + endOffset)
    }
}

/**
 * User-entered offsets are seconds relative to this session's first monotonic
 * sample. Reject invalid, overflowed, reversed and out-of-bounds intervals.
 * Never clamp silently: the selected evidence window must match user intent.
 */
internal fun customAbWindow(bounds: DukoWindow, startSeconds: String, endSeconds: String): DukoWindow? {
    val start = startSeconds.trim().toLongOrNull() ?: return null
    val end = endSeconds.trim().toLongOrNull() ?: return null
    if (start < 0L || end <= start || end > Long.MAX_VALUE / 1000L) return null
    val startMs = start * 1000L
    val endMs = end * 1000L
    if (endMs > bounds.durationMs) return null
    return DukoWindow(bounds.startMs + startMs, bounds.startMs + endMs)
}

/** One A/B screen; lifecycle ViewModel owns selections, work and export snapshots. */
@Composable
internal fun DukoComparisonPanel(model: CompareScreenViewModel) {
    val state by model.ui.collectAsStateWithLifecycle()
    val sessions = state.sessions
    val a = state.a
    val b = state.b
    val presetA = state.presetA
    val presetB = state.presetB
    val startA = state.startA
    val endA = state.endA
    val startB = state.startB
    val endB = state.endB
    val selectedWindows = state.selectedWindows
    val report = state.report
    val busy = state.busy
    val error = state.issue
    val exportStatus = state.exportStatus
    var editRuns by rememberSaveable { mutableStateOf(true) }

    val textPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> model.onExportResult(ComparisonExport.TXT, uri) }
    val csvPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> model.onExportResult(ComparisonExport.CSV, uri) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(AbSlate, RoundedCornerShape(17.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("DUKO / A–B", color = AbAmber, fontWeight = FontWeight.SemiBold)
        Text("A #${a ?: "—"} (baseline)   ·   B #${b ?: "—"} (candidate)",
            color = AbInk, fontSize = 15.sp)
        Text("Compare measured device telemetry—not FPS or game speed.",
            color = AbSubtle)
        OutlinedButton(
            onClick = { editRuns = !editRuns },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (editRuns) "HIDE RUN & WINDOW SETTINGS" else
            "EDIT RUNS & ANALYSIS WINDOWS") }
        if (editRuns) {
        Text("SELECT TWO RECORDED SESSIONS", color = AbAmber)
        Text("User tags describe declared conditions; they do not verify the hardware.",
            color = AbSubtle)
        sessions.forEach { session ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("#${session.id}", color = AbInk, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { model.selectA(session.id) }) {
                    Text(if (a == session.id) "A ✓" else "SET A")
                }
                OutlinedButton(onClick = { model.selectB(session.id) }) {
                    Text(if (b == session.id) "B ✓" else "SET B")
                }
            }
        }
        if (state.hasMoreSessions) {
            OutlinedButton(
                onClick = model::loadOlder,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("LOAD OLDER RUNS") }
        }
        Text("Analysis windows · each recording is bounded independently", color = AbSubtle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.cycleA() }) { Text("A: " + presetA.label) }
            OutlinedButton(onClick = { model.cycleB() }) { Text("B: " + presetB.label) }
        }
        if (presetA == AbWindowPreset.CUSTOM) {
            Text("A: seconds from first recorded sample", color = AbSubtle)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startA, { model.setStartA(it) },
                    label = { Text("Start s") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(endA, { model.setEndA(it) },
                    label = { Text("End s") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
        if (presetB == AbWindowPreset.CUSTOM) {
            Text("B: seconds from first recorded sample", color = AbSubtle)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startB, { model.setStartB(it) },
                    label = { Text("Start s") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(endB, { model.setEndB(it) },
                    label = { Text("End s") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = state.canCompare,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = { model.compare() }
            ) { Text("COMPARE") }
            OutlinedButton(onClick = { model.clear() },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("CLEAR") }
        }
        }
        if (busy) Text("Analyzing recorded evidence…", color = AbSubtle)
        if (error != null) Text(error.orEmpty(), color = AbAmber)
        if (exportStatus != null) Text(exportStatus.orEmpty(), color = AbSubtle)
        report?.let { result ->
            selectedWindows?.let { (wa, wb) ->
                Text("A: ${wa.startMs}–${wa.endMs} ms (${wa.durationMs} ms)", color = AbSubtle)
                Text("B: ${wb.startMs}–${wb.endMs} ms (${wb.durationMs} ms)", color = AbSubtle)
            }
            Text("COMPARISON SUMMARY", color = AbAmber, fontWeight = FontWeight.Bold)
            Text("A #${result.sessionA} → B #${result.sessionB} · ${result.quality.name.replace('_', ' ')}",
                color = AbInk)
            val qualified = result.metrics.values.count {
                it.quality != DukoComparisonQuality.NOT_COMPARABLE
            }
            Text("${qualified} of ${result.metrics.size} reported metrics permit a " +
                "qualified comparison. Interpret each metric's evidence separately.",
                color = AbSubtle)

            selectedWindows?.let { (wa, wb) ->
                Text("DUKO REPORT · comparison-only · saved locally", color = AbAmber)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = state.pendingExport == null && !state.writing,
                        onClick = { model.prepareExport(ComparisonExport.TXT)?.let(textPicker::launch) }
                    ) { Text("SAVE TXT") }
                    OutlinedButton(
                        enabled = state.pendingExport == null && !state.writing,
                        onClick = { model.prepareExport(ComparisonExport.CSV)?.let(csvPicker::launch) }
                    ) { Text("SAVE CSV") }
                }
            }
            result.contextWarnings.forEach {
                Text(it.replace('_', ' '), color = AbSubtle)
            }
            Text("COMPARISON RESULTS  /  MEASURED EVIDENCE",
                color = AbAmber, fontWeight = FontWeight.SemiBold)
            listOf(
                DukoMetric.CPU_LOAD, DukoMetric.GPU_LOAD,
                DukoMetric.CPU_CLOCK, DukoMetric.GPU_CLOCK,
                DukoMetric.BATTERY_TEMPERATURE, DukoMetric.CPU_TEMPERATURE,
                DukoMetric.GPU_TEMPERATURE, DukoMetric.AVAILABLE_RAM
            ).forEach { metric ->
                result.metrics[metric]?.let { item ->
                    ComparisonMetricCard(metric, item)
                }
            }
            Text("Measured telemetry is not FPS, a causal performance claim or a game benchmark.", color = AbSubtle)
        }
    }
}

/** One qualified metric per card. Never invent a delta for blocked evidence. */
@Composable
private fun ComparisonMetricCard(
    metric: DukoMetric,
    item: DukoMetricDifference,
) {
    val comparable = item.quality != DukoComparisonQuality.NOT_COMPARABLE
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF132038), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(metric.name.replace('_', ' '), color = AbInk,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(item.quality.name.replace('_', ' '),
                color = if (comparable) AbAmber else Color(0xFFF16B78),
                fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("A  /  BASELINE", fontSize = 10.sp, color = AbSubtle)
                Text(abNumber(item.aMean) + " " + metric.unit.symbol,
                    color = AbInk, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.weight(1f)) {
                Text("B  /  CANDIDATE", fontSize = 10.sp, color = AbSubtle)
                Text(abNumber(item.bMean) + " " + metric.unit.symbol,
                    color = AbInk, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
        Text(
            if (!comparable) "DELTA NOT SUPPORTED BY AVAILABLE EVIDENCE"
            else "B − A: ${abNumber(item.delta)} ${metric.unit.symbol}  ·  " +
                "${abNumber(item.percentChange)}%",
            color = if (comparable) AbAmber else AbSubtle, fontSize = 12.sp
        )
        if (item.warnings.isNotEmpty()) {
            Text(item.warnings.joinToString(" · ").replace('_', ' '),
                color = AbSubtle, fontSize = 11.sp)
        }
    }
}
