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
    val last = recording?.last
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        V2PageTitle("LIVE DEVICE", "Observation",
            "What the handheld is doing, not how many frames the game renders")
        V2Surface {
            val status = when (state.status) {
                is RecorderStatus.Recording -> "● RECORDING"
                is RecorderStatus.Finished -> "● SAVED"
                is RecorderStatus.Stopping, is RecorderStatus.Saving -> "● SAVING"
                is RecorderStatus.NeedsAttention -> "● REVIEW NEEDED"
                RecorderStatus.Preparing -> "● STARTING"
                RecorderStatus.Idle -> "● READY"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    color = if (recording == null) V2Palette.cyan else V2Palette.coral)
                Text(
                    when (val s = state.status) {
                        is RecorderStatus.Recording ->
                            "#" + s.sessionId + "  •  " + s.persistedSamples + " samples"
                        is RecorderStatus.Finished ->
                            "#" + s.sessionId + "  •  " + s.samples + " saved"
                        else -> "OFFLINE"
                    },
                    color = V2Palette.muted, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStart, modifier = Modifier.weight(1f)
                    .heightIn(min = 50.dp),
                    enabled = !state.commandPending &&
                        (state.status is RecorderStatus.Idle ||
                         state.status is RecorderStatus.Finished ||
                         (state.status is RecorderStatus.NeedsAttention &&
                          state.status.sessionId == null)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = V2Palette.cyan,
                        contentColor = V2Palette.background)
                ) { Text("START", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)
                    .heightIn(min = 50.dp),
                    enabled = !state.commandPending &&
                        (recording != null ||
                         (state.status is RecorderStatus.NeedsAttention &&
                          state.status.sessionId != null))
                ) { Text("STOP & SAVE", color = V2Palette.amber) }
            }
            state.issue?.let { Text(it, color = V2Palette.coral, fontSize = 12.sp) }
        }
        Text("LIVE TELEMETRY  /  CURRENT SESSION",
            color = V2Palette.muted, fontSize = 11.sp,
            fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.weight(1f)) {
                V2Value("CPU LOAD", last?.cpuLoadPercent?.let {
                    formatV2Number(it, 0) + "%"
                } ?: "—")
            }
            Box(Modifier.weight(1f)) {
                V2Value("GPU LOAD", last?.gpuLoadPercent?.let {
                    formatV2Number(it, 0) + "%"
                } ?: "—")
            }
            Box(Modifier.weight(1f)) {
                V2Value("BATTERY", last?.batteryLevelPercent?.let {
                    it.toString() + "%"
                } ?: "—")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.weight(1f)) {
                V2Value("CPU CLOCK", last?.cpuClockMHz?.let { it.toString() + " MHz" } ?: "—")
            }
            Box(Modifier.weight(1f)) {
                V2Value("GPU CLOCK", last?.gpuClockMHz?.let { it.toString() + " MHz" } ?: "—")
            }
            Box(Modifier.weight(1f)) {
                V2Value("BATTERY TEMP", last?.batteryTemperatureC?.let {
                    formatV2Number(it) + "°C"
                } ?: "—")
            }
        }
        V2Surface {
            Text("NEXT TEST CONDITIONS", color = V2Palette.cyan,
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(state.testProfile.title, color = V2Palette.text, fontSize = 15.sp)
            Text(state.testProfile.details, color = V2Palette.muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(onClick = onAnalyze, modifier = Modifier.weight(1f)) {
                    Text("DUKO ANALYTICS", color = V2Palette.cyan)
                }
                OutlinedButton(onClick = onRuns, modifier = Modifier.weight(1f)) {
                    Text("RUNS", color = V2Palette.text)
                }
                OutlinedButton(onClick = onTools, modifier = Modifier.weight(1f)) {
                    Text("SETUP", color = V2Palette.amber)
                }
            }
        }
        Text("Legacy HUD removed from the active UI. Compact HUD 2.0 is " +
            "being rebuilt separately. Recording and Quick Settings remain available.",
            color = V2Palette.amber, fontSize = 11.sp)
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
