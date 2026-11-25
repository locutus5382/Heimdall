package com.locutus5382.heimdall.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.ui.SessionScreenViewModel
import com.locutus5382.heimdall.ui.dashboard.DashboardUiState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal fun formatV2Number(x: Double?, digits: Int = 1): String =
    x?.takeIf { it.isFinite() }?.let {
        String.format(Locale.US, "%." + digits + "f", it)
    } ?: "—"

internal fun formatV2Time(ms: Long): String {
    val sec = ms.coerceAtLeast(0L) / 1000L
    return if (sec < 3600L) String.format(Locale.US, "%02d:%02d", sec / 60L, sec % 60L)
    else String.format(Locale.US, "%02d:%02d:%02d",
        sec / 3600L, (sec / 60L) % 60L, sec % 60L)
}

@Composable
internal fun V2PageTitle(kicker: String, heading: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(kicker, color = V2Palette.cyan, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.35.sp, fontSize = 10.sp)
        Text(heading, color = V2Palette.text, fontWeight = FontWeight.SemiBold,
            fontSize = 25.sp)
        Text(detail, color = V2Palette.muted, fontSize = 11.sp)
    }
}

@Composable
internal fun V2Surface(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(V2Palette.quietGlow, RoundedCornerShape(16.dp))
            .border(1.dp, V2Palette.divider, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) { content() }
}

@Composable
internal fun V2Value(
    label: String, reading: String, caption: String = "",
    accent: Color = V2Palette.cyan,
) {
    Column(Modifier.fillMaxWidth()
        .background(V2Palette.panel, RoundedCornerShape(13.dp))
        .border(1.dp, V2Palette.divider, RoundedCornerShape(13.dp))
        .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.width(4.dp).height(4.dp)
                .background(accent, RoundedCornerShape(3.dp)))
            Text(label.uppercase(), color = V2Palette.muted,
                fontSize = 10.sp, maxLines = 1)
        }
        Text(reading, color = V2Palette.text, fontSize = 23.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        if (caption.isNotEmpty()) Text(caption, color = V2Palette.subtle,
            fontSize = 10.sp, maxLines = 1)
    }
}

/**
 * Landscape-first observatory. A narrow recording/control pane anchors the
 * left; all live sensor evidence is readable without a long scroll. The same
 * components stack naturally on narrower Android screens.
 */
@Composable
internal fun V2LiveScreen(
    state: DashboardUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAnalyze: () -> Unit,
    onRuns: () -> Unit,
    onTools: () -> Unit,
) {
    val recording = state.status as? RecorderStatus.Recording
    val latest = recording?.last
    BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val wide = maxWidth >= 690.dp
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    V2PageTitle("HEIMDALL  /  LOCAL OBSERVATORY", "Overview",
                        "Live device measurements · no FPS capture or root")
                }
                Box(Modifier.background(V2Palette.panel, RoundedCornerShape(25.dp))
                    .border(1.dp, V2Palette.border, RoundedCornerShape(25.dp))
                    .padding(horizontal = 11.dp, vertical = 7.dp)) {
                    Text("●  OFFLINE", color = V2Palette.teal, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.width(315.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        V2RecorderCard(state, recording, onStart, onStop)
                        V2TestContextCard(state, onAnalyze, onRuns, onTools)
                    }
                    Column(Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        V2LiveMetrics(latest)
                        V2LoadSignals(latest)
                    }
                }
            } else {
                V2RecorderCard(state, recording, onStart, onStop)
                V2LiveMetrics(latest)
                V2TestContextCard(state, onAnalyze, onRuns, onTools)
                V2LoadSignals(latest)
            }
        }
    }
}

@Composable
private fun V2RecorderCard(
    state: DashboardUiState,
    recording: RecorderStatus.Recording?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    V2Surface {
        val status = when (state.status) {
            is RecorderStatus.Recording -> "RECORDING"
            is RecorderStatus.Finished -> "SAVED"
            is RecorderStatus.Stopping, is RecorderStatus.Saving -> "SAVING"
            is RecorderStatus.NeedsAttention -> "REVIEW NEEDED"
            RecorderStatus.Preparing -> "STARTING"
            RecorderStatus.Idle -> "READY"
        }
        val accent = when (state.status) {
            is RecorderStatus.Recording, is RecorderStatus.NeedsAttention -> V2Palette.coral
            else -> V2Palette.teal
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(7.dp).height(7.dp)
                .background(accent, RoundedCornerShape(5.dp)))
            Text("  " + status, color = accent, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                when (val s = state.status) {
                    is RecorderStatus.Recording -> "#" + s.sessionId
                    is RecorderStatus.Finished -> "#" + s.sessionId
                    else -> "LOCAL"
                },
                color = V2Palette.muted, fontSize = 11.sp
            )
        }
        Text(
            when (val s = state.status) {
                is RecorderStatus.Recording ->
                    s.persistedSamples.toString() + " samples saved"
                is RecorderStatus.Finished ->
                    s.samples.toString() + " samples completed"
                is RecorderStatus.NeedsAttention -> "Recording requires review"
                is RecorderStatus.Stopping, is RecorderStatus.Saving ->
                    "Finishing the current recording"
                else -> "Ready to observe a test session"
            },
            color = V2Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(
                onClick = onStart,
                enabled = !state.commandPending &&
                    (state.status is RecorderStatus.Idle ||
                     state.status is RecorderStatus.Finished ||
                     (state.status is RecorderStatus.NeedsAttention &&
                      state.status.sessionId == null)),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = V2Palette.cyan,
                    contentColor = V2Palette.background),
            ) { Text("START", fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = onStop,
                enabled = !state.commandPending &&
                    (recording != null ||
                     (state.status is RecorderStatus.NeedsAttention &&
                      state.status.sessionId != null)),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("STOP & SAVE", color = V2Palette.amber,
                fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
        state.issue?.let { Text(it, color = V2Palette.coral, fontSize = 11.sp) }
        Text("One foreground recorder · Quick Settings and notification share the state",
            color = V2Palette.subtle, fontSize = 10.sp)
    }
}

@Composable
private fun V2TestContextCard(
    state: DashboardUiState,
    onAnalyze: () -> Unit,
    onRuns: () -> Unit,
    onTools: () -> Unit,
) {
    V2Surface {
        Text("TEST CONTEXT", color = V2Palette.amber, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp, fontSize = 11.sp)
        Text(state.testProfile.title, color = V2Palette.text,
            fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(state.testProfile.details, color = V2Palette.muted,
            fontSize = 11.sp, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedButton(onClick = onAnalyze, modifier = Modifier.weight(1f)) {
                Text("DUKO", color = V2Palette.cyan, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onRuns, modifier = Modifier.weight(1f)) {
                Text("RUNS", color = V2Palette.text, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onTools, modifier = Modifier.weight(1f)) {
                Text("TOOLS", color = V2Palette.amber, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun V2LiveMetrics(sample: DeviceSample?) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.weight(1f)) {
                V2Value("CPU LOAD", sample?.cpuLoadPercent?.let {
                    formatV2Number(it, 0) + "%"
                } ?: "—")
            }
            Box(Modifier.weight(1f)) {
                V2Value("GPU LOAD", sample?.gpuLoadPercent?.let {
                    formatV2Number(it, 0) + "%"
                } ?: "—", accent = V2Palette.amber)
            }
            Box(Modifier.weight(1f)) {
                V2Value("BATTERY", sample?.batteryLevelPercent?.let {
                    it.toString() + "%"
                } ?: "—", accent = V2Palette.teal)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.weight(1f)) {
                V2Value("CPU CLOCK", sample?.cpuClockMHz?.let {
                    it.toString() + " MHz"
                } ?: "—")
            }
            Box(Modifier.weight(1f)) {
                V2Value("GPU CLOCK", sample?.gpuClockMHz?.let {
                    it.toString() + " MHz"
                } ?: "—", accent = V2Palette.amber)
            }
            Box(Modifier.weight(1f)) {
                V2Value("BATTERY TEMP", sample?.batteryTemperatureC?.let {
                    formatV2Number(it) + "°C"
                } ?: "—", accent = V2Palette.teal)
            }
        }
    }
}

@Composable
private fun V2LoadSignals(sample: DeviceSample?) {
    V2Surface {
        Text("UTILIZATION  /  INSTANTANEOUS",
            color = V2Palette.muted, fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp, letterSpacing = 0.6.sp)
        V2LoadBar("CPU LOAD", sample?.cpuLoadPercent, V2Palette.cyan)
        V2LoadBar("GPU LOAD", sample?.gpuLoadPercent, V2Palette.amber)
        Text("Current measured sample, not a gameplay benchmark. " +
            "See DUKO for history, coverage and percentiles.",
            color = V2Palette.subtle, fontSize = 10.sp)
    }
}

@Composable
private fun V2LoadBar(label: String, value: Double?, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = V2Palette.muted, fontSize = 10.sp,
                modifier = Modifier.weight(1f))
            Text(value?.let { formatV2Number(it, 0) + "%" } ?: "—",
                color = if (value == null) V2Palette.subtle else accent,
                fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        }
        Box(Modifier.fillMaxWidth().height(6.dp)
            .background(V2Palette.border, RoundedCornerShape(5.dp))) {
            if (value != null && value.isFinite()) {
                Box(Modifier.fillMaxWidth((value / 100.0).toFloat()
                    .coerceIn(0f, 1f)).height(6.dp)
                    .background(accent, RoundedCornerShape(5.dp)))
            }
        }
    }
}

@Composable
internal fun V2RunsScreen(
    model: SessionScreenViewModel,
    refreshKey: Long,
    onAnalyze: (Long) -> Unit,
) {
    val state by model.ui.collectAsStateWithLifecycle()
    LaunchedEffect(refreshKey) { model.refresh(refreshKey) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        V2PageTitle("LOCAL ARCHIVE", "Runs", "Select a run to open its real DUKO evidence")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.history.size.toString() + " loaded",
                color = V2Palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = model::reload) { Text("REFRESH") }
        }
        if (state.loadingHistory) Text("Loading saved runs…", color = V2Palette.muted)
        state.notice?.let { Text(it, color = V2Palette.coral) }
        state.history.forEach { session ->
            V2RunRow(session) { onAnalyze(session.id) }
        }
        if (state.history.isEmpty() && !state.loadingHistory)
            V2Surface { Text("No completed recordings. Record from LIVE.",
                color = V2Palette.muted) }
        if (state.hasMoreHistory) OutlinedButton(
            modifier = Modifier.fillMaxWidth(), onClick = model::loadOlder
        ) { Text("LOAD OLDER") }
    }
}

@Composable
private fun V2RunRow(s: SavedSession, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth()
        .background(V2Palette.panel, RoundedCornerShape(11.dp))
        .clickable(onClick = onSelect).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("#" + s.id, color = V2Palette.amber, fontWeight = FontWeight.Bold,
            fontSize = 16.sp)
        Column(Modifier.weight(1f)) {
            Text(s.samples.toString() + " samples  •  " +
                formatV2Time(s.endedAtMs - s.startedAtMs),
                color = V2Palette.text, fontSize = 13.sp)
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(s.startedAtMs)) +
                if (s.reason == "INTERRUPTED") "  •  interrupted" else "",
                color = V2Palette.muted, fontSize = 11.sp)
        }
        Text("ANALYZE  ›", color = V2Palette.cyan, fontSize = 12.sp)
    }
}
