package com.locutus5382.heimdall.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locutus5382.heimdall.engine.duko.CpuPolicyResidencyReport
import com.locutus5382.heimdall.engine.duko.DukoEventTimeline
import com.locutus5382.heimdall.engine.duko.DukoEventType
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoMinuteBlock
import com.locutus5382.heimdall.engine.duko.DukoMinuteReport
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.DukoFoundationReport
import com.locutus5382.heimdall.ui.theme.BifrostColors
import java.util.Locale

private val m18Ink = BifrostColors.Ice
private val m18Subtle = BifrostColors.Muted
private val m18Amber = BifrostColors.Amber
private val m18Cyan = BifrostColors.Signal
private val m18Coral = BifrostColors.Coral

private fun m18Num(value: Double?, decimals: Int = 1): String =
    value?.takeIf { it.isFinite() }?.let {
        String.format(Locale.US, "%.${decimals}f", it)
    } ?: "—"
private fun m18At(window: DukoWindow, t: Long): String {
    val s = (t - window.startMs).coerceAtLeast(0L) / 1000L
    return String.format(Locale.US, "%02d:%02d", s / 60L, s % 60L)
}

@Composable
internal fun DukoM18AnalysisPanel(report: DukoFoundationReport) {
    var selectedTab by rememberSaveable(report.sessionId) { mutableStateOf("MINUTES") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("DUKO / ADVANCED ANALYTICS", color = m18Amber)
        Text("Recorded evidence only. Gaps and unreadable sensors remain unknown.",
            color = m18Subtle, fontSize = 11.sp)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("MINUTES", "CPU POLICIES", "EVENTS").forEach { tab ->
                FilterChip(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(tab) },
                )
            }
        }
        when (selectedTab) {
            "MINUTES" -> MinuteWindowPanel(report)
            "CPU POLICIES" -> CpuPolicyResidencyPanel(report)
            "EVENTS" -> EventTimelinePanel(report)
        }
    }
}

/** Real statistics for one selected minute; the last partial interval is labeled. */
@Composable
private fun MinuteWindowPanel(report: DukoFoundationReport) {
    val minutes: DukoMinuteReport? = report.minuteAnalysis
    if (minutes == null || minutes.blocks.isEmpty()) {
        Text("Minute-by-minute analysis unavailable for this session.", color = m18Subtle)
        return
    }
    var page by rememberSaveable(report.sessionId, "minutePage") { mutableIntStateOf(0) }
    var chosen by rememberSaveable(report.sessionId, "minuteChosen") { mutableIntStateOf(0) }
    val pageSize = 12
    val maxPage = (minutes.blocks.lastIndex / pageSize).coerceAtLeast(0)
    val safePage = page.coerceIn(0, maxPage)
    val shown = minutes.blocks.drop(safePage * pageSize).take(pageSize)
    val active = minutes.blocks[chosen.coerceIn(0, minutes.blocks.lastIndex)]
    Text("60-SECOND INTERVALS · ${minutes.totalBlocks} in selected recording window",
        color = m18Ink, fontSize = 12.sp)
    if (minutes.truncated) Text(
        "Display capped at the first ${minutes.reportedBlocks} minutes " +
            "to bound analysis cost. Select a later window for additional time.",
        color = m18Amber, fontSize = 11.sp
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { page = (safePage - 1).coerceAtLeast(0) },
            enabled = safePage > 0, modifier = Modifier.heightIn(min = 48.dp)
        ) { Text("PREVIOUS") }
        OutlinedButton(onClick = { page = (safePage + 1).coerceAtMost(maxPage) },
            enabled = safePage < maxPage, modifier = Modifier.heightIn(min = 48.dp)
        ) { Text("NEXT 12") }
    }
    Text("Select an interval · page ${safePage + 1} / ${maxPage + 1}",
        color = m18Subtle, fontSize = 11.sp)
    shown.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            row.forEach { block ->
                val cpu = block.metrics[DukoMetric.CPU_LOAD]
                FilterChip(
                    selected = chosen == block.ordinal - 1,
                    onClick = { chosen = block.ordinal - 1 },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    label = {
                        Column {
                            Text("M${block.ordinal}", fontSize = 11.sp)
                            Text(m18Num(cpu?.timeWeightedMean) + "% CPU",
                                fontSize = 10.sp)
                        }
                    },
                )
            }
        }
    }
    MinuteDetail(active, minutes.window)
}

@Composable
private fun MinuteDetail(block: DukoMinuteBlock, base: DukoWindow) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(BifrostColors.Blade, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text("MINUTE ${block.ordinal} · +" +
            m18At(base, block.window.startMs) + "–+" +
            m18At(base, block.window.endMs) +
            if (block.partial) "  · PARTIAL" else "",
            color = m18Amber)
        Text("${block.recordedRows} persisted samples · " +
            "${block.window.durationMs / 1000L}s interval",
            color = m18Subtle, fontSize = 11.sp)
        listOf(
            DukoMetric.CPU_LOAD, DukoMetric.GPU_LOAD,
            DukoMetric.CPU_CLOCK, DukoMetric.GPU_CLOCK,
            DukoMetric.BATTERY_TEMPERATURE,
            DukoMetric.CPU_TEMPERATURE, DukoMetric.GPU_TEMPERATURE,
            DukoMetric.AVAILABLE_RAM,
        ).forEach { metric ->
            val d = block.metrics[metric] ?: return@forEach
            Text(metric.name.replace('_', ' ') + " · median " + m18Num(d.median) +
                " · P95 " + m18Num(d.p95) +
                " · mean " + m18Num(d.timeWeightedMean) +
                " · peak " + m18Num(d.maximum) +
                " " + metric.unit.symbol,
                color = m18Ink, fontSize = 12.sp)
            Text("Coverage " + m18Num(d.coverage * 100) + "% · " +
                d.evidence.name.replace('_', ' ') +
                " · SD ±" + m18Num(d.sampleStandardDeviation),
                color = m18Subtle, fontSize = 10.sp)
        }
        val e = block.energy
        Text("Battery-terminal discharge " + m18Num(e.measuredDischargeMWh) +
            " mWh · mean " + m18Num(e.timeWeightedDischargeWatts) + " W · " +
            e.evidence.name,
            color = m18Ink, fontSize = 11.sp)
        Text("This is not calibrated game or wall power. " +
            "Battery readings may be unavailable.",
            color = m18Subtle, fontSize = 10.sp)
        block.pressure.forEach { (name, psi) ->
            if (psi.samples > 0) Text(name + " · sample mean " +
                m18Num(psi.arithmeticMean) + "% · peak " +
                m18Num(psi.maximum) + "%",
                color = m18Subtle, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CpuPolicyResidencyPanel(report: DukoFoundationReport) {
    val policies = report.cpuPolicyResidency
    if (policies.isEmpty()) {
        Text("CPU policy frequency samples unavailable. Readable CPU core " +
            "utilization alone cannot establish policy clock residency.",
            color = m18Subtle)
        return
    }
    var showAll by rememberSaveable(report.sessionId, "cpuResidencyExpanded") {
        mutableStateOf(false)
    }
    Text("${policies.size} observed policies · sampled MHz-band residency",
        color = m18Ink)
    (if (showAll) policies else policies.take(8)).forEach { policy ->
        CpuPolicyCard(policy)
    }
    if (policies.size > 8) OutlinedButton(
        onClick = { showAll = !showAll },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) { Text(if (showAll) "SHOW FEWER POLICIES" else "SHOW ALL ${policies.size} POLICIES") }
    Text("Clocks are observed at sampling instants. Unmeasured intervals are UNKNOWN; " +
        "frequency distribution is not utilization, FPS, or proof of throttling.",
        color = m18Subtle, fontSize = 11.sp)
}

@Composable
private fun CpuPolicyCard(item: CpuPolicyResidencyReport) {
    val result = item.residency
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(BifrostColors.Blade, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(item.policyId + if (item.relatedCores.isNotEmpty())
            " · cores ${item.relatedCores.joinToString(", ")}" else " · topology unknown",
            color = m18Amber, fontSize = 12.sp)
        Text("${item.observedSamples} readings · observed " +
            m18Num(result.coveredFraction * 100) + "% · unknown " +
            "${result.unknownMs / 1000L}s · ${result.evidence.name}",
            color = m18Subtle, fontSize = 11.sp)
        Text("Clock transitions ${result.transitions} · largest ↑" +
            (result.largestRiseMHz?.toString() ?: "—") + " MHz / ↓" +
            (result.largestDropMHz?.toString() ?: "—") + " MHz",
            color = m18Ink, fontSize = 11.sp)
        val bands = result.bandUpperMHz
        val nonzero = result.bandDurationMs.mapIndexedNotNull { i, ms ->
            if (ms <= 0L) null else {
                val label = if (i < bands.size) "≤${bands[i]} MHz"
                    else ">${bands.last()} MHz"
                label to ms
            }
        }.sortedByDescending { it.second }.take(5)
        if (nonzero.isEmpty()) {
            Text("Insufficient continuous clock evidence", color = m18Subtle)
        } else nonzero.forEach { (label, ms) ->
            Text(label + " · " + m18Num(ms * 100.0 / result.window.durationMs) +
                "% of window (" + ms / 1000L + "s)",
                color = m18Ink, fontSize = 11.sp)
        }
    }
}

@Composable
private fun EventTimelinePanel(report: DukoFoundationReport) {
    val timeline: DukoEventTimeline? = report.events
    if (timeline == null) {
        Text("Historical events unavailable.", color = m18Subtle)
        return
    }
    val all = timeline.events
    var filter by rememberSaveable(report.sessionId, "eventType") { mutableStateOf("ALL") }
    var limit by rememberSaveable(report.sessionId, "eventLimit") { mutableIntStateOf(20) }
    val groups = listOf("ALL", "CAPS", "THERMAL", "LOAD / PSI", "RECORDING")
    Text("${all.size} recorded-evidence events" +
        if (timeline.droppedEvents > 0) " · ${timeline.droppedEvents} omitted by safety cap" else "",
        color = m18Ink)
    Text("Diagnostic thresholds are experimental and can indicate pressure, " +
        "not the cause of game behavior or a confirmed thermal throttle.",
        color = m18Subtle, fontSize = 11.sp)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groups.forEach { name ->
            FilterChip(
                selected = filter == name,
                onClick = { filter = name; limit = 20 },
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text(name) }
            )
        }
    }
    val shown = all.filter { event ->
        when (filter) {
            "CAPS" -> event.type in setOf(
                DukoEventType.POLICY_CAP_OBSERVED,
                DukoEventType.POLICY_CAP_CLEARED)
            "THERMAL" -> event.type in setOf(
                DukoEventType.BATTERY_HOT, DukoEventType.THERMAL_PRESSURE)
            "LOAD / PSI" -> event.type in setOf(
                DukoEventType.CPU_LOAD_HIGH, DukoEventType.GPU_LOAD_HIGH,
                DukoEventType.CPU_PSI_PRESSURE, DukoEventType.MEMORY_PSI_PRESSURE)
            "RECORDING" -> event.type in setOf(
                DukoEventType.RECORDING_BEGIN, DukoEventType.RECORDING_COMPLETE,
                DukoEventType.RECORDING_INTERRUPTED,
                DukoEventType.ANALYSIS_WINDOW_BEGIN, DukoEventType.ANALYSIS_WINDOW_END)
            else -> true
        }
    }
    if (shown.isEmpty()) {
        Text("No qualifying recorded events in this category.", color = m18Subtle)
    } else {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(38.dp)
                .semantics { contentDescription = "Observed event timing distribution" }
        ) {
            val width = size.width
            val y = size.height / 2f
            drawLine(m18Subtle.copy(alpha = 0.35f),
                Offset(4f, y), Offset(width - 4f, y), 2.dp.toPx())
            shown.forEach { event ->
                val duration = timeline.window.durationMs.toDouble()
                val start = ((event.startMs - timeline.window.startMs) / duration)
                    .coerceIn(0.0, 1.0).toFloat() * (width - 8f) + 4f
                val end = ((event.endMs - timeline.window.startMs) / duration)
                    .coerceIn(0.0, 1.0).toFloat() * (width - 8f) + 4f
                val color = if (event.heuristic) m18Coral else m18Cyan
                if (end > start) drawLine(color, Offset(start, y),
                    Offset(end, y), 4.dp.toPx())
                else drawCircle(color, 3.dp.toPx(), Offset(start, y))
            }
        }
        shown.take(limit.coerceAtLeast(20)).forEach { e ->
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(BifrostColors.Blade, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("+${m18At(timeline.window, e.startMs)}" +
                    if (e.durationMs > 0) " · ${e.durationMs / 1000L}s observed" else "",
                    color = if (e.heuristic) m18Coral else m18Cyan,
                    fontSize = 11.sp)
                Text(e.type.name.replace('_', ' ') + " · " + e.detail,
                    color = m18Ink, fontSize = 12.sp)
            }
        }
        if (shown.size > limit) OutlinedButton(
            onClick = { limit = (limit + 20).coerceAtMost(250) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("SHOW MORE EVENTS") }
    }
}
