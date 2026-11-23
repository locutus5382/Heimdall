package com.locutus5382.heimdall.ui.dashboard

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locutus5382.heimdall.TestProfilePreview
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.ui.theme.BifrostColors
import kotlinx.coroutines.delay
import java.util.Locale

private enum class TelemetryPage(val label: String) {
    ALL("ALL READINGS"), ESSENTIAL("ESSENTIALS"), CLOCKS("CLOCKS / MEMORY"),
    THERMALS("THERMALS")
}

/** The only Dashboard: real recorder controls and live evidence, never history panels. */
@Composable
fun BifrostDashboard(
    status: RecorderStatus,
    issue: String?,
    hudEnabled: Boolean,
    overlayPermission: Boolean,
    commandPending: Boolean = false,
    testProfile: TestProfilePreview = TestProfilePreview(),
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleHud: () -> Unit,
    onSetup: () -> Unit,
    onHudStudio: () -> Unit,
    onSessions: () -> Unit,
    onDiagnostics: () -> Unit,
    onReviewSession: ((Long) -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 680.dp
        val recording = status as? RecorderStatus.Recording
        val last = recording?.last
        var telemetryPage by rememberSaveable { mutableStateOf(TelemetryPage.ALL.name) }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 24.dp else 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("YOUR HANDHELD · LIVE", color = BifrostColors.Signal,
                        fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                    Text("Overview", color = BifrostColors.Ice,
                        fontSize = if (wide) 30.sp else 24.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.background(BifrostColors.Editorial, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)) {
                    val phase = when (status) {
                        is RecorderStatus.Recording -> "● RECORDING"
                        RecorderStatus.Preparing -> "● PREPARING"
                        is RecorderStatus.Stopping, is RecorderStatus.Saving -> "● FINALIZING"
                        is RecorderStatus.NeedsAttention -> "● ATTENTION"
                        else -> "● LOCAL / OFFLINE"
                    }
                    Text(phase,
                        color = when (status) {
                            is RecorderStatus.Recording -> BifrostColors.Signal
                            RecorderStatus.Preparing, is RecorderStatus.Stopping,
                            is RecorderStatus.Saving, is RecorderStatus.NeedsAttention -> BifrostColors.Amber
                            else -> BifrostColors.Muted
                        },
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }

            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RecorderCard(status, issue, commandPending, onStart, onStop, Modifier.weight(1.1f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("LIVE TELEMETRY", color = BifrostColors.Muted,
                            fontSize = 11.sp, letterSpacing = 1.3.sp)
                        TelemetryPagePicker(telemetryPage) { telemetryPage = it }
                        MetricsGrid(last, true, telemetryPage)
                    }
                }
            } else {
                RecorderCard(status, issue, commandPending, onStart, onStop, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LIVE TELEMETRY", color = BifrostColors.Muted,
                        fontSize = 11.sp, letterSpacing = 1.3.sp,
                        modifier = Modifier.weight(1f))
                    val age = last?.observedElapsedMs?.let {
                        ((SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) / 1000L)
                    }
                    Text(when {
                        last == null -> "WAITING FOR SAMPLES"
                        age == null -> "AGE UNKNOWN"
                        age > 8L -> "LAST UPDATE $age s AGO"
                        else -> "LATEST SAMPLE"
                    }, color = if (age != null && age > 8L)
                        BifrostColors.Amber else BifrostColors.Muted, fontSize = 10.sp)
                }
                TelemetryPagePicker(telemetryPage) { telemetryPage = it }
                MetricsGrid(last, false, telemetryPage)
            }

            if (status is RecorderStatus.Finished) {
                OutlinedButton(
                    onClick = {
                        if (onReviewSession != null) onReviewSession(status.sessionId)
                        else onSessions()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text("REVIEW SAVED SESSION #${status.sessionId}")
                }
            }

            Column(
                Modifier.fillMaxWidth().background(BifrostColors.Editorial, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("IN-GAME HUD", color = BifrostColors.Ice,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        val detail = when {
                            hudEnabled && overlayPermission && recording != null ->
                                "Enabled for recording · overlay controlled by recorder"
                            hudEnabled && overlayPermission ->
                                "Ready for next recording · overlay appears during capture"
                            hudEnabled -> "Permission required · not yet active"
                            else -> "Off · opt in to the in-game overlay"
                        }
                        Text(detail, color = if (hudEnabled && !overlayPermission)
                            BifrostColors.Amber else BifrostColors.Muted, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onToggleHud) {
                        Text(if (hudEnabled) "TURN OFF" else "ENABLE", fontSize = 12.sp)
                    }
                }
                OutlinedButton(onClick = onHudStudio, modifier = Modifier.fillMaxWidth()) {
                    Text("HUD Studio · layout and placement")
                }
            }

            Column(
                Modifier.fillMaxWidth()
                    .background(BifrostColors.Editorial, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NEXT TEST CONDITIONS", color = BifrostColors.Muted,
                        fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onSetup) { Text("SETUP") }
                }
                Text(testProfile.title, color = BifrostColors.Ice,
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(testProfile.details, color = BifrostColors.Muted, fontSize = 12.sp)
                Text(if (!testProfile.userDeclared)
                    "Unknown settings are never inferred from the device"
                    else if (testProfile.nextOnly)
                    "User-declared · next recording only · verify after START"
                    else "User-declared, reusable conditions · confirm before each run",
                    color = BifrostColors.Amber, fontSize = 11.sp)
            }

            Column(
                Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("QUICK ACCESS", color = BifrostColors.Muted, fontSize = 11.sp,
                    letterSpacing = 1.3.sp)
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                         QuickAccess("SESSIONS", "Saved runs & DUKO", onSessions, Modifier.weight(1f))
                        QuickAccess("DIAGNOSTICS", "Device capabilities", onDiagnostics, Modifier.weight(1f))
                    }
                } else {
                     QuickAccess("SESSIONS", "Saved runs & DUKO", onSessions, Modifier.fillMaxWidth())
                    QuickAccess("DIAGNOSTICS", "Device capabilities", onDiagnostics, Modifier.fillMaxWidth())
                }
            }
            Text("Unavailable measurements are shown as —, not zero. " +
                "Telemetry describes the device, not rendered FPS or game performance.",
                color = BifrostColors.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RecorderCard(
    status: RecorderStatus,
    issue: String?,
    commandPending: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier,
) {
    val recording = status as? RecorderStatus.Recording
    val attention = status as? RecorderStatus.NeedsAttention
    val sessionId = when (status) {
        is RecorderStatus.Recording -> status.sessionId
        is RecorderStatus.Stopping -> status.sessionId
        is RecorderStatus.Saving -> status.sessionId
        is RecorderStatus.Finished -> status.sessionId
        is RecorderStatus.NeedsAttention -> status.sessionId
        else -> null
    }
    val title = when (status) {
        RecorderStatus.Idle -> "Ready to observe"
        RecorderStatus.Preparing -> "Preparing"
        is RecorderStatus.Recording -> "Recording telemetry"
        is RecorderStatus.Stopping -> "Stopping safely"
        is RecorderStatus.Saving -> "Saving session"
        is RecorderStatus.Finished -> if (status.reason == "INTERRUPTED")
            "Recovered partial session" else "Session saved"
        is RecorderStatus.NeedsAttention -> "Needs attention"
    }
    val subtitle = when (status) {
        is RecorderStatus.Recording -> String.format(Locale.US, "%d saved samples",
            status.persistedSamples)
        is RecorderStatus.Finished -> String.format(Locale.US, "%d saved samples",
            status.samples)
        is RecorderStatus.NeedsAttention ->
            "Recorder needs attention · original session requires review"
        RecorderStatus.Preparing -> "Waiting for the recorder service"
        is RecorderStatus.Stopping, is RecorderStatus.Saving ->
            "The original session is being finalized"
        else -> "Local recording · no root · no network"
    }
    val canStart = !commandPending && (status is RecorderStatus.Idle ||
        status is RecorderStatus.Finished ||
        (attention != null && attention.sessionId == null))
    val canStop = !commandPending && (status is RecorderStatus.Recording ||
        (attention?.sessionId ?: 0L) > 0L)
    val elapsed = recording?.let { RecordingDuration(it.startedElapsedMs) }
    Column(modifier.background(BifrostColors.Editorial, RoundedCornerShape(18.dp))
        .border(1.dp, BifrostColors.Cobalt, RoundedCornerShape(18.dp))
        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("SESSION CONTROL", color = BifrostColors.Muted, fontSize = 11.sp,
            letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
        Text(title, color = BifrostColors.Ice, fontSize = 26.sp,
            fontWeight = FontWeight.Bold, maxLines = 2)
        Text(subtitle, color = BifrostColors.Muted, fontSize = 13.sp)
        if (recording != null) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(elapsed ?: "TIME UNAVAILABLE", color = BifrostColors.Signal,
                    fontSize = 34.sp, fontWeight = FontWeight.Medium)
                Text("ELAPSED · MONOTONIC", color = BifrostColors.Muted, fontSize = 10.sp)
            }
        }
        if (sessionId != null) Text("SESSION #" + sessionId,
            color = BifrostColors.Amber, fontSize = 11.sp)
        if (issue != null) Text(issue, color = BifrostColors.Coral, fontSize = 13.sp)
        if (commandPending) Text("Request dispatched · awaiting recorder state",
            color = BifrostColors.Amber, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onStart, enabled = canStart,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BifrostColors.Signal,
                    contentColor = BifrostColors.Midnight),
                shape = RoundedCornerShape(10.dp)
            ) { Text("START", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = onStop, enabled = canStop,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(10.dp)) {
                Text(if (attention?.sessionId != null) "RETRY STOP" else "STOP & SAVE",
                    color = if (canStop) BifrostColors.Amber else BifrostColors.Muted,
                    fontSize = 12.sp, maxLines = 1)
            }
        }
        Text("Quick Settings can control this same session. " +
            "A stopping/saving session cannot be started twice.",
            color = BifrostColors.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun RecordingDuration(startElapsedMs: Long?): String? {
    if (startElapsedMs == null) return null
    var now by remember(startElapsedMs) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startElapsedMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000L)
        }
    }
    val seconds = (now - startElapsedMs).coerceAtLeast(0L) / 1000L
    return String.format(Locale.US, "%02d:%02d:%02d",
        seconds / 3600L, seconds / 60L % 60L, seconds % 60L)
}

@Composable
private fun TelemetryPagePicker(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        TelemetryPage.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { group ->
                    FilterChip(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        selected = selected == group.name,
                        onClick = { onSelect(group.name) },
                        label = { Text(group.label, fontSize = 11.sp, maxLines = 1) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricsGrid(sample: DeviceSample?, wide: Boolean, page: String) {
    // This grid has the SAME source as the foreground recorder/HUD. Never
    // turn a missing sensor into zero or imply that these are frame rates.
    val cells = listOf(
        "CPU LOAD" to sample?.cpuLoadPercent?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.0f%%", it) },
        "GPU LOAD" to sample?.gpuLoadPercent?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.0f%%", it) },
        "CPU CLOCK" to sample?.cpuClockMHz?.let { "$it MHz" },
        "GPU CLOCK" to sample?.gpuClockMHz?.let { "$it MHz" },
        "BATTERY" to sample?.batteryLevelPercent?.let { "$it%" },
        "BATTERY TEMP" to sample?.batteryTemperatureC?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.1f°C", it) },
        "CPU TEMP" to sample?.cpuTemperatureC?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.1f°C", it) },
        "GPU TEMP" to sample?.gpuTemperatureC?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.1f°C", it) },
        "AVAILABLE RAM" to sample?.availableMemoryMiB?.let { "$it MiB" },
    )
    val visible = when (page) {
        TelemetryPage.ESSENTIAL.name ->
            cells.filter { it.first in setOf("CPU LOAD", "GPU LOAD", "BATTERY", "BATTERY TEMP") }
        TelemetryPage.CLOCKS.name ->
            cells.filter { it.first in setOf("CPU CLOCK", "GPU CLOCK", "AVAILABLE RAM") }
        TelemetryPage.THERMALS.name ->
            cells.filter { it.first in setOf("BATTERY TEMP", "CPU TEMP", "GPU TEMP") }
        else -> cells
    }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        // Two columns on a narrow portrait screen; three on a handheld in
        // landscape. The previous 3-narrow/2-wide arrangement clipped values.
        visible.chunked(if (wide) 3 else 2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)
                        .background(BifrostColors.Blade, RoundedCornerShape(11.dp))
                        .padding(horizontal = 10.dp, vertical = 13.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, color = BifrostColors.Muted, fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(value ?: "—", color = BifrostColors.Ice,
                            fontSize = if (wide) 19.sp else 15.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAccess(
    title: String, description: String, onClick: () -> Unit, modifier: Modifier
) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(70.dp),
        shape = RoundedCornerShape(12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = BifrostColors.Signal, fontSize = 12.sp,
                fontWeight = FontWeight.Bold)
            Text(description, color = BifrostColors.Muted, fontSize = 10.sp,
                maxLines = 1)
        }
    }
}
