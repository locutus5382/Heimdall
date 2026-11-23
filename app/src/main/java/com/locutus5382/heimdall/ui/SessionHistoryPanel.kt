package com.locutus5382.heimdall.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.locutus5382.heimdall.engine.duko.ClockResidencyResult
import com.locutus5382.heimdall.engine.duko.ThermalStabilityResult
import com.locutus5382.heimdall.engine.duko.DukoEvidence
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoFrequencyCapAssessment
import com.locutus5382.heimdall.engine.duko.DukoFrequencyCapState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFFF2F6FC)
private val Subtle = Color(0xFF94A7C4)
private val Amber = Color(0xFFF0BD62)
private val Slate = Color(0xFF111C31)

/** Only one section is visible at a time on a handheld touchscreen. */
internal enum class SessionDetailSection(val label: String) {
    SUMMARY("SUMMARY"), ANALYSIS("DUKO ANALYSIS"), CONDITIONS("TEST CONDITIONS"),
    EXPORT("EXPORT")
}

/** A completed asynchronous read may publish only into its originating expansion. */
internal fun mayPublishSessionAnalysis(requestGeneration: Long, currentGeneration: Long,
    requestSessionId: Long, expandedSessionId: Long?): Boolean =
    requestGeneration == currentGeneration && requestSessionId == expandedSessionId

private fun fmt(value: Double?) =
    value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

/**
 * Display-only projection of recorded, evidence-qualified GPU clock residency.
 * Bands are absolute MHz; percentages are of the requested window, NOT GPU load.
 * No extrapolation into unmeasured intervals.
 */
internal fun gpuResidencyLines(result: ClockResidencyResult): List<String> {
    val duration = result.window.durationMs
    if (duration <= 0L) return listOf("GPU clock residency unavailable")
    val covered = 100.0 * result.coveredMs / duration
    val unknown = 100.0 * result.unknownMs / duration
    val lines = mutableListOf(
        "Recorded window coverage ${String.format(Locale.US, "%.0f", covered)}% · " +
            "Unmeasured ${String.format(Locale.US, "%.0f", unknown)}% · ${result.evidence.name}",
        "Qualified sampled clock changes: ${result.transitions} · " +
            "Largest rise ${result.largestRiseMHz?.let { "$it MHz" } ?: "—"} · " +
            "Largest drop ${result.largestDropMHz?.let { "$it MHz" } ?: "—"}"
    )
    result.bandDurationMs.forEachIndexed { index, milliseconds ->
        if (milliseconds > 0L) {
            val label = if (index == result.bandUpperMHz.size)
                "> ${result.bandUpperMHz.last()} MHz"
            else if (index == 0) "≤ ${result.bandUpperMHz[0]} MHz"
            else "${result.bandUpperMHz[index - 1] + 1}–${result.bandUpperMHz[index]} MHz"
            lines += "$label: ${String.format(Locale.US, "%.0f", milliseconds * 100.0 / duration)}% of window"
        }
    }
    return lines
}

/** Display no thermal delta when provenance or phase coverage is insufficient. */
internal fun thermalStabilityLines(result: ThermalStabilityResult): List<String> {
    val name = result.metric.name.replace('_', ' ')
    if (result.evidence == DukoEvidence.INSUFFICIENT || result.lateMinusEarlyC == null)
        return listOf("$name · insufficient verified early/late thermal evidence")
    val delta = String.format(Locale.US, "%+.1f", result.lateMinusEarlyC)
    val slope = result.lateSlopeCPerMinute?.let {
        String.format(Locale.US, "%+.2f °C/min", it)
    } ?: "unavailable"
    return listOf(
        "$name · late minus early $delta °C · ${result.evidence.name}",
        "Early ${result.earlyCoveredMs / 1000L}s · Late ${result.lateCoveredMs / 1000L}s · " +
            "late slope $slope · " +
            (if (result.latePlateauObserved) "approximate late plateau" else "no plateau established")
    )
}

@Composable
internal fun SessionHistoryPanel(
    refreshKey: Long,
    showComparison: Boolean = false,
    model: SessionScreenViewModel,
    onCompare: ((Long) -> Unit)? = null,
) {
    val state by model.ui.collectAsStateWithLifecycle()
    val history = state.history
    val notice = state.notice
    val expandedId = state.expandedId
    val details = state.detail?.overview
    val foundation = state.detail?.duko
    val policyCaps = state.detail?.caps
    val reading = state.reading

    val chooser = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> model.onExportResult(uri) }

    LaunchedEffect(refreshKey) { model.refresh(refreshKey) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SAVED RUNS", color = Amber)
        if (history.isEmpty()) Text(
            "Your completed recordings will appear here.",
            color = Subtle
        )
        // Compare has one dedicated tab; never mount a second A/B panel here.
        history.forEach { session ->
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Slate, RoundedCornerShape(17.dp))
                    .border(1.dp, Color(0xFF192B49), RoundedCornerShape(17.dp))
                    .padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("SESSION #${session.id}", color = Amber)
                if (session.reason == "INTERRUPTED") {
                    Text("INTERRUPTED · Partial data retained",
                        color = Color(0xFFF16B78))
                }
                Text(
                    "${session.samples} samples · ${(session.endedAtMs - session.startedAtMs).coerceAtLeast(0L) / 1000L}s",
                    color = Ink
                )
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(session.startedAtMs)),
                    color = Subtle
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        onClick = { model.toggle(session.id) }
                    ) { Text(if (expandedId == session.id) "HIDE DETAILS" else "ANALYZE") }
                    if (onCompare != null) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            enabled = session.samples >= 2,
                            onClick = { onCompare(session.id) },
                        ) { Text("COMPARE A/B") }
                    }
                }
                if (expandedId == session.id) {
                    var section by rememberSaveable(session.id) {
                        mutableStateOf(SessionDetailSection.SUMMARY.name)
                    }
                    Text("SESSION #${session.id} / DETAILS", color = Amber)
                    SessionDetailSection.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { destination ->
                                FilterChip(
                                    selected = section == destination.name,
                                    onClick = { section = destination.name },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    label = { Text(destination.label) },
                                )
                            }
                        }
                    }
                    if (section == SessionDetailSection.CONDITIONS.name) {
                        Text("Recorded settings are user-declared, not verified hardware state.",
                            color = Subtle)
                        DukoTagEditor(sessionId = session.id)
                    }
                    if (section == SessionDetailSection.EXPORT.name) {
                        Text("Export the original completed run as CSV. No raw samples " +
                            "are changed or uploaded.", color = Subtle)
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            enabled = !state.exporting && state.pendingExportId == null,
                            onClick = {
                                if (model.requestExport(session.id))
                                    chooser.launch("Heimdall_session_${session.id}.csv")
                            },
                        ) { Text("EXPORT SESSION #${session.id} CSV") }
                        if (state.exporting) {
                            Text("Saving the selected session…", color = Amber)
                        }
                    }
                    if (reading) Text("Preparing recorded evidence…", color = Subtle)
                    if (section == SessionDetailSection.ANALYSIS.name) policyCaps?.let { cap ->
                        Text("CPU POLICY LIMITS · START SNAPSHOT", color = Amber)
                        val status = when (cap.state) {
                            DukoFrequencyCapState.OBSERVED_BELOW_HARDWARE_MAX ->
                                "Observed below hardware maximum: " +
                                    cap.restrictedPolicyIds.joinToString()
                            DukoFrequencyCapState.NO_RESTRICTION_OBSERVED ->
                                "No lower policy ceiling observed"
                            DukoFrequencyCapState.INSUFFICIENT_EVIDENCE ->
                                "Insufficient readable policy evidence"
                        }
                        Text(status, color = Ink)
                        Text(
                            "${cap.comparedPolicyCount} compared · ${cap.unknownPolicyCount} unknown. " +
                                "A cap does not prove ClusterTune, thermal throttling or its cause.",
                            color = Subtle
                        )
                    }
                    val overview = details
                    if (overview != null) {
                        if (section == SessionDetailSection.SUMMARY.name) {
                        Text("SESSION SUMMARY / RECORDED VALUES", color = Amber)
                        val start = overview.batteryAtStart
                        val end = overview.batteryAtEnd
                        Text(
                            "CPU median ${fmt(overview.cpuLoad?.median)}%  ·  " +
                                "P95 ${fmt(overview.cpuLoad?.p95)}%  ·  " +
                                "Peak ${fmt(overview.cpuLoad?.peak)}%",
                            color = Ink
                        )
                        Text(
                            "GPU clock median ${fmt(overview.gpuClock?.median)} MHz  ·  " +
                                "P95 ${fmt(overview.gpuClock?.p95)} MHz",
                            color = Ink
                        )
                        Text(
                            "Battery ${start?.let { "$it%" } ?: "—"} → " +
                                "${end?.let { "$it%" } ?: "—"}  ·  " +
                                "Max battery temp ${fmt(overview.highestBatteryTempC)}°C",
                            color = Ink
                        )
                        Text(
                            "CPU coverage: ${overview.cpuLoad?.count ?: 0} / ${overview.totalSamples} samples. " +
                                "Unavailable sensors are not interpreted as zero.",
                            color = Subtle
                        )
                        }
                        if (section == SessionDetailSection.ANALYSIS.name) {
                        val duko = foundation
                        if (duko != null) {
                            Text("DUKO FOUNDATION · DATA INTEGRITY", color = Amber)
                            Text(
                                "Observed monotonic interval: ${duko.window.durationMs / 1000L}s · " +
                                    "Device profile: ${if (duko.capabilitySnapshotAvailable) "saved" else "legacy/unknown"}",
                                color = Subtle
                            )
                            val energy = duko.energy
                            Text("DUKO ENERGY · BATTERY DISCHARGE", color = Amber)
                            Text(
                                "Measured battery-terminal energy: " +
                                    "${fmt(energy.measuredDischargeMWh)} mWh · " +
                                    "Mean discharge: ${fmt(energy.timeWeightedDischargeWatts)} W · " +
                                    "Coverage: ${String.format(Locale.US, "%.0f", energy.statistics.coverage * 100)}% · " +
                                    energy.evidence.name,
                                color = Ink
                            )
                            if (energy.warnings.isNotEmpty()) {
                                Text(
                                    "Battery-only measurement, not total device/game power. " +
                                        energy.warnings.filterNot {
                                            it == "BATTERY_TERMINAL_POWER_NOT_GAME_OR_WALL_POWER"
                                        }.joinToString(", "),
                                    color = Subtle
                                )
                            }
                            duko.gpuClockResidency?.let { residency ->
                                Text("DUKO GPU CLOCK RESIDENCY · SAMPLED", color = Amber)
                                gpuResidencyLines(residency).forEach { line ->
                                    Text(line, color = Ink)
                                }
                                Text(
                                    "Sampled MHz bands are not GPU utilization, FPS, " +
                                        "throttling evidence or a performance ranking.",
                                    color = Subtle
                                )
                            }
                            if (duko.thermalStability.isNotEmpty()) {
                                Text("DUKO THERMAL · EARLY/LATE STABILITY", color = Amber)
                                listOf(DukoMetric.BATTERY_TEMPERATURE,
                                    DukoMetric.CPU_TEMPERATURE,
                                    DukoMetric.GPU_TEMPERATURE).forEach { metric ->
                                    duko.thermalStability[metric]?.let { result ->
                                        thermalStabilityLines(result).forEach { line ->
                                            Text(line, color = Ink)
                                        }
                                    }
                                }
                                Text("Fixed phases are not matched gameplay. Temperature " +
                                    "changes or plateaus cannot prove throttling, fan efficiency " +
                                    "or game performance.", color = Subtle)
                            }
                            Text("DUKO THERMAL · MEASURED TRENDS", color = Amber)
                            listOf(
                                DukoMetric.BATTERY_TEMPERATURE,
                                DukoMetric.CPU_TEMPERATURE,
                                DukoMetric.GPU_TEMPERATURE
                            ).forEach { metric ->
                                val trend = duko.thermal[metric]
                                if (trend != null) {
                                    Text(
                                        "${metric.name.replace('_', ' ')} · " +
                                            "Slope ${fmt(trend.warmingSlopeCPerMinute)} °C/min · " +
                                            "Continuous ${trend.continuousSegmentMs / 1000L}s · " +
                                            trend.evidence.name,
                                        color = Ink
                                    )
                                }
                            }
                            Text(
                                "Short runs and missing readings cannot establish a thermal " +
                                    "trend. A temperature rise does not prove throttling.",
                                color = Subtle
                            )
                            listOf(
                                DukoMetric.CPU_LOAD,
                                DukoMetric.GPU_LOAD,
                                DukoMetric.CPU_CLOCK,
                                DukoMetric.GPU_CLOCK,
                                DukoMetric.BATTERY_TEMPERATURE,
                                DukoMetric.BATTERY_CURRENT
                            ).forEach { metric ->
                                val item = duko.integrity.metrics[metric]
                                if (item != null) {
                                    Text(
                                        "${metric.name.replace('_', ' ')} · " +
                                            "P50 ${fmt(item.median)} ${metric.unit.symbol} · " +
                                            "Time-mean ${fmt(item.timeWeightedMean)} ${metric.unit.symbol} · " +
                                            "Coverage ${String.format(Locale.US, "%.0f", item.coverage * 100)}% · " +
                                            item.evidence.name,
                                        color = Ink
                                    )
                                    if (item.warnings.isNotEmpty())
                                        Text(item.warnings.joinToString(", "),
                                            color = Subtle)
                                }
                            }
                        } else {
                            Text(
                                "Duko time-weighted analysis requires at least two " +
                                    "monotonic samples; older sessions retain their basic summaries.",
                                color = Subtle
                            )
                        }
                        }
                    }
                }
            }
        }
        if (state.loadingHistory) Text("Refreshing saved runs…", color = Subtle)
        if (state.exporting) Text("Exporting session CSV…", color = Subtle)
        if (notice != null) Text(notice, color = Amber)
    }
}
