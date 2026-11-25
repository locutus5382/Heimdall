package com.locutus5382.heimdall.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locutus5382.heimdall.engine.duko.ClockResidencyResult
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.recording.DukoFoundationReport
import java.util.Locale

@Composable
private fun V2PercentBar(value: Double?, color: Color) {
    Box(Modifier.fillMaxWidth().height(6.dp)
        .background(V2Palette.border, RoundedCornerShape(5.dp))) {
        if (value != null && value.isFinite()) {
            Box(Modifier.fillMaxWidth((value / 100.0).toFloat().coerceIn(0f, 1f))
                .height(6.dp).background(color, RoundedCornerShape(5.dp)))
        }
    }
}

@Composable
internal fun V2CoreDashboard(report: DukoFoundationReport) {
    val activity = report.coreActivity
    val cores = activity?.cores.orEmpty().sortedBy { it.coreId }
    if (cores.isEmpty()) {
        V2Surface {
            Text("Individual core utilization is unavailable on this device.",
                color = V2Palette.muted)
        }
        return
    }
    val leadingMs = cores.sumOf { it.ledObservedMs }
    var selectedCore by remember(report.sessionId, report.window) {
        mutableIntStateOf(activity?.busiestCore ?: cores.first().coreId)
    }
    V2Surface {
        Text("CORE ACTIVITY / MEASURED DISTRIBUTION",
            color = V2Palette.cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("Average load is system-wide. Busiest share counts qualified intervals " +
            "where a core led visible cores; it is not a game-thread measurement.",
            color = V2Palette.muted, fontSize = 11.sp)
        Text("Core data coverage " + formatV2Number(
            activity?.coverageFraction?.times(100), 0) + "%",
            color = V2Palette.text, fontSize = 11.sp)
        cores.forEach { core ->
            val selected = selectedCore == core.coreId
            Column(Modifier.fillMaxWidth().background(
                if (selected) V2Palette.raised else V2Palette.panel,
                RoundedCornerShape(9.dp))
                .clickable { selectedCore = core.coreId }
                .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CORE " + core.coreId, color = if (selected)
                        V2Palette.cyan else V2Palette.text,
                        fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    Text("Mean " + formatV2Number(core.averageLoadPercent) +
                        "%  ·  Peak " + formatV2Number(core.maximumLoadPercent) + "%",
                        color = V2Palette.muted, fontSize = 11.sp)
                }
                V2PercentBar(core.averageLoadPercent, V2Palette.cyan)
                Text("Busiest " +
                    if (leadingMs <= 0L) "—"
                    else formatV2Number(
                        100.0 * core.ledObservedMs / leadingMs, 0) + "%" +
                        " of measured leading time" +
                        "  ·  Peak at +" + (core.maximumAtMs?.let {
                            formatV2Time(it - report.window.startMs)
                        } ?: "—"),
                    color = V2Palette.subtle, fontSize = 10.sp)
            }
        }
        if (activity?.clusters?.isNotEmpty() == true) {
            Text("RECORDED CLUSTERS", color = V2Palette.amber, fontSize = 11.sp)
            activity.clusters.forEach { cluster ->
                Text(cluster.policyId + "  /  cores " + cluster.cores.joinToString() +
                    "   mean " + formatV2Number(cluster.averageLoadPercent) + "%",
                    color = V2Palette.text, fontSize = 11.sp)
            }
        }
    }
    val selected = report.perCoreTimelines[selectedCore]
    V2RecordedTrace(
        title = "CORE " + selectedCore + "  /  RECORDED LOAD TRACE",
        traces = listOfNotNull(selected?.let { V2Trace(it, V2Palette.cyan) }),
        fixedRange = 0.0..100.0
    )
}

@Composable
private fun V2ResidencySummary(name: String, signal: ClockResidencyResult) {
    V2Surface {
        Text(name + "  /  " + signal.evidence.name,
            color = V2Palette.cyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text("Covered " + formatV2Time(signal.coveredMs) +
            "  ·  Unknown " + formatV2Time(signal.unknownMs) +
            "  ·  Transitions " + signal.transitions,
            color = V2Palette.text, fontSize = 11.sp)
        val top = signal.bandDurationMs.withIndex()
            .filter { it.value > 0L }
            .sortedByDescending { it.value }.take(3)
        if (top.isEmpty()) Text("Insufficient clock residency evidence.",
            color = V2Palette.muted, fontSize = 11.sp)
        top.forEach { indexed ->
            val end = signal.bandUpperMHz.getOrNull(indexed.index)
            Text((if (end == null) "> " + signal.bandUpperMHz.lastOrNull()
                else "≤ " + end) + " MHz  /  " + formatV2Time(indexed.value),
                color = V2Palette.muted, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun V2ClocksDashboard(report: DukoFoundationReport) {
    V2RecordedTrace("CPU CLOCK  /  MEASURED MHz",
        listOfNotNull(report.timelines["CPU CLOCK"]?.let {
            V2Trace(it, V2Palette.cyan)
        }))
    V2RecordedTrace("GPU CLOCK  /  MEASURED MHz",
        listOfNotNull(report.timelines["GPU CLOCK"]?.let {
            V2Trace(it, V2Palette.amber)
        }))
    if (report.cpuPolicyResidency.isEmpty()) V2Surface {
        Text("CPU policy residency unavailable.", color = V2Palette.muted)
    }
    report.cpuPolicyResidency.forEach { policy ->
        V2ResidencySummary(policy.policyId + "  /  CPU " +
            policy.relatedCores.joinToString().ifEmpty { "topology unknown" },
            policy.residency)
    }
    report.gpuClockResidency?.let {
        V2ResidencySummary("GPU FREQUENCY RESIDENCY", it)
    }
    report.frequencyCaps?.let { caps ->
        V2Surface {
            Text("OBSERVED FREQUENCY CEILINGS", color = V2Palette.amber,
                fontWeight = FontWeight.Bold, fontSize = 12.sp)
            if (caps.policies.isEmpty()) Text("No qualified policy ceiling readings.",
                color = V2Palette.muted)
            caps.policies.forEach { policy ->
                Text(policy.policyId + "  •  restricted " +
                    formatV2Time(policy.restrictedMs) + " / observed " +
                    formatV2Time(policy.observedMs) + "  •  transitions " +
                    policy.transitions +
                    if (policy.presetLike) "  ·  preset-like pattern" else "",
                    color = V2Palette.text, fontSize = 11.sp)
            }
            Text("An observed cap does NOT prove ClusterTune, thermal throttling " +
                "or the cause of that restriction.",
                color = V2Palette.subtle, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun V2PressureDashboard(report: DukoFoundationReport) {
    V2RecordedTrace("CPU PRESSURE  /  avg10 (%)",
        listOfNotNull(report.timelines["CPU PSI SOME AVG10"]?.let {
            V2Trace(it, V2Palette.cyan)
        }), fixedRange = 0.0..100.0)
    V2RecordedTrace("MEMORY PRESSURE  /  avg10 (%)",
        listOfNotNull(
            report.timelines["MEMORY PSI SOME AVG10"]?.let {
                V2Trace(it, V2Palette.amber)
            },
            report.timelines["MEMORY PSI FULL AVG10"]?.let {
                V2Trace(it, V2Palette.coral)
            }
        ), fixedRange = 0.0..100.0)
    val mem = report.integrity.metrics[DukoMetric.AVAILABLE_RAM]
    V2Surface {
        Text("AVAILABLE RAM  /  " + (mem?.evidence?.name ?: "UNAVAILABLE"),
            color = V2Palette.cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("Median " + formatV2Number(mem?.median, 0) +
            " MiB  •  P95 " + formatV2Number(mem?.p95, 0) +
            " MiB  •  Minimum " + formatV2Number(mem?.minimum, 0) + " MiB",
            color = V2Palette.text, fontSize = 12.sp)
        Text("Coverage " + formatV2Number(mem?.coverage?.times(100), 0) +
            "%  ·  PSI signals are system-wide; not emulator frame-time metrics.",
            color = V2Palette.muted, fontSize = 11.sp)
    }
}

@Composable
internal fun V2ThermalExtras(report: DukoFoundationReport) {
    report.thermal.forEach { (metric, item) ->
        V2Surface {
            Text(metric.name.replace('_', ' ') + "  /  TEMPERATURE TREND",
                color = V2Palette.cyan, fontSize = 12.sp)
            Text("Slope " + formatV2Number(item.warmingSlopeCPerMinute) +
                " °C/min  ·  Change " + formatV2Number(item.temperatureChangeC) + " °C",
                color = V2Palette.text, fontSize = 12.sp)
            Text("Evidence " + item.evidence.name +
                "  ·  Qualified continuous time " +
                formatV2Time(item.continuousSegmentMs),
                color = V2Palette.muted, fontSize = 11.sp)
        }
    }
    V2Surface {
        Text("BATTERY ELECTRICAL EVIDENCE  /  " + report.energy.evidence.name,
            color = V2Palette.amber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("Discharged " + formatV2Number(report.energy.measuredDischargeMWh) +
            " mWh  ·  Time-mean " +
            formatV2Number(report.energy.timeWeightedDischargeWatts) + " W",
            color = V2Palette.text, fontSize = 13.sp)
        Text("Battery-terminal electrical estimate over valid observed intervals; " +
            "not the power attributable to the game.",
            color = V2Palette.muted, fontSize = 11.sp)
        report.energy.warnings.take(3).forEach {
            Text(it.replace('_', ' '), color = V2Palette.subtle, fontSize = 10.sp)
        }
    }
}
