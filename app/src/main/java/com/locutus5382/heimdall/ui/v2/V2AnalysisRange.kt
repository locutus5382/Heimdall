package com.locutus5382.heimdall.ui.v2

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locutus5382.heimdall.engine.duko.DukoRangeSelection
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.DukoFoundationReport
import com.locutus5382.heimdall.ui.SessionScreenViewModel
import com.locutus5382.heimdall.ui.SessionsUiState
import java.util.Locale

private fun within(full: DukoWindow, sub: DukoWindow): Boolean =
    sub.startMs >= full.startMs && sub.endMs <= full.endMs &&
        sub.durationMs >= DukoRangeSelection.MIN_WINDOW_MS

private fun offset(full: DukoWindow, ms: Long): String =
    formatV2Time((ms - full.startMs).coerceAtLeast(0L))

/**
 * The selected time window is a view-model/domain input, not a visual crop.
 * Selecting a preset starts one new indexed SQLite analysis; old statistics
 * are not silently presented under a new window label.
 */
@Composable
internal fun V2AnalysisRange(
    sessionId: Long,
    original: DukoFoundationReport,
    state: SessionsUiState,
    model: SessionScreenViewModel,
) {
    val full = original.window
    var from by rememberSaveable(sessionId, "v2-range-start") {
        mutableFloatStateOf(0f)
    }
    var to by rememberSaveable(sessionId, "v2-range-end") {
        mutableFloatStateOf(1f)
    }
    val proposed = remember(full, from, to) {
        DukoRangeSelection.select(full, from, to)
    }
    val active = state.analysisWindow ?: full
    val analyzing = state.readingSelection || state.reading || state.exporting
    val suggested = original.insights?.suggestions?.intervals.orEmpty()
        .filter { within(full, it.window) }.take(3)
    val csvPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> model.onExportResult(uri) }

    V2Surface {
        Text("ANALYSIS WINDOW  /  ORIGINAL RECORDED TIME",
            color = V2Palette.cyan, fontSize = 11.sp)
        Text("+" + offset(full, active.startMs) + "  –  +" +
            offset(full, active.endMs) +
            if (state.analysisWindow == null) "   ·   FULL RUN"
            else "   ·   SELECTED RANGE",
            color = V2Palette.text, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedButton(enabled = !analyzing, onClick = {
                from = 0f
                to = 1f
                model.selectAnalysisWindow(sessionId, null)
            }) { Text("FULL RUN") }
            OutlinedButton(enabled = !analyzing && full.durationMs >= 2_000L,
                onClick = {
                    DukoRangeSelection.select(full, 0.10f, 0.90f)?.let {
                        from = 0.10f
                        to = 0.90f
                        model.selectAnalysisWindow(sessionId, it)
                    }
                }) { Text("MIDDLE 80%") }
            OutlinedButton(enabled = !analyzing && full.durationMs >= 2_000L,
                onClick = {
                    DukoRangeSelection.select(full, 0.50f, 1.0f)?.let {
                        from = 0.50f
                        to = 1f
                        model.selectAnalysisWindow(sessionId, it)
                    }
                }) { Text("LAST 50%") }
            suggested.forEachIndexed { index, candidate ->
                OutlinedButton(enabled = !analyzing,
                    onClick = {
                        from = ((candidate.window.startMs - full.startMs).toDouble() /
                            full.durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
                        to = ((candidate.window.endMs - full.startMs).toDouble() /
                            full.durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
                        model.selectAnalysisWindow(sessionId, candidate.window)
                    }
                ) { Text("SUGGESTED " + (index + 1)) }
            }
        }
        Text("CUSTOM RANGE  •  drag both handles, then Apply",
            color = V2Palette.muted, fontSize = 11.sp)
        RangeSlider(
            value = from..to,
            onValueChange = {
                from = it.start.coerceIn(0f, 1f)
                to = it.endInclusive.coerceIn(0f, 1f)
            },
            valueRange = 0f..1f,
            enabled = !analyzing && full.durationMs >=
                DukoRangeSelection.MIN_WINDOW_MS,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "DUKO range start and end"
            },
        )
        Text("Proposed: +" + (proposed?.let { offset(full, it.startMs) } ?: "—") +
            " to +" + (proposed?.let { offset(full, it.endMs) } ?: "—"),
            color = V2Palette.muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(enabled = !analyzing && proposed != null &&
                proposed != active, onClick = {
                proposed?.let { model.selectAnalysisWindow(sessionId, it) }
            }) { Text("APPLY RANGE") }
            OutlinedButton(
                enabled = !analyzing && state.pendingExportId == null &&
                    (state.analysisWindow == null ||
                        state.selectedAnalysis?.window == active),
                onClick = {
                    val rangeOnly = state.analysisWindow != null
                    if (model.requestExport(sessionId, selectedOnly = rangeOnly)) {
                        csvPicker.launch("Heimdall_Run_" + sessionId +
                            if (rangeOnly) "_selected.csv" else "_full.csv")
                    }
                }
            ) { Text(if (state.analysisWindow == null) "EXPORT CSV" else "EXPORT RANGE CSV") }
        }
        if (state.readingSelection) Text(
            "Reanalyzing selected ORIGINAL rows; previous results are hidden.",
            color = V2Palette.amber, fontSize = 11.sp)
        if (state.exporting) Text("Writing original CSV evidence…",
            color = V2Palette.muted, fontSize = 11.sp)
        Text("No samples are rewritten. Short or invalid intervals are rejected.",
            color = V2Palette.subtle, fontSize = 10.sp)
    }
}
