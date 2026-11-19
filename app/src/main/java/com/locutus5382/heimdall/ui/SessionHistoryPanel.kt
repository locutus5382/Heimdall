package com.locutus5382.heimdall.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.recording.SessionArchive
import com.locutus5382.heimdall.recording.SessionOverview
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFFEAF1FF)
private val Subtle = Color(0xFF9EADC2)
private val Amber = Color(0xFFF2A900)
private val Slate = Color(0xFF121E32)

private fun fmt(value: Double?) =
    value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

@Composable
fun SessionHistoryPanel(refreshKey: Long) {
    val context = LocalContext.current
    val archive = remember(context) { SessionArchive(context) }
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<SavedSession>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var exportTarget by remember { mutableStateOf<Long?>(null) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var details by remember { mutableStateOf<SessionOverview?>(null) }
    var reading by remember { mutableStateOf(false) }

    val chooser = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { destination ->
        val id = exportTarget
        exportTarget = null
        if (destination != null && id != null) {
            scope.launch {
                try {
                    val stream = context.contentResolver.openOutputStream(destination)
                        ?: error("DESTINATION_UNAVAILABLE")
                    val count = archive.exportCsv(id, stream)
                    notice = "Session #$id exported: $count samples."
                } catch (e: Exception) {
                    notice = "CSV export failed: " + e.javaClass.simpleName
                }
            }
        }
    }

    LaunchedEffect(refreshKey) {
        try {
            history = archive.recent()
            notice = null
        } catch (e: Exception) {
            notice = "Session history unavailable: " + e.javaClass.simpleName
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("RECORDED SESSIONS", color = Amber)
        if (history.isEmpty()) Text(
            "Your completed recordings will appear here.",
            color = Subtle
        )
        history.forEach { session ->
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Slate, RoundedCornerShape(17.dp))
                    .border(1.dp, Color(0xFF26364F), RoundedCornerShape(17.dp))
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
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (expandedId == session.id) {
                                expandedId = null
                                details = null
                            } else {
                                expandedId = session.id
                                details = null
                                reading = true
                                scope.launch {
                                    try {
                                        details = archive.overview(session.id)
                                    } catch (e: Exception) {
                                        notice = "Summary unavailable: " + e.javaClass.simpleName
                                    } finally {
                                        reading = false
                                    }
                                }
                            }
                        }
                    ) { Text(if (expandedId == session.id) "HIDE" else "ANALYZE") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            exportTarget = session.id
                            chooser.launch("Heimdall_session_${session.id}.csv")
                        }
                    ) { Text("EXPORT CSV") }
                }
                if (expandedId == session.id) {
                    if (reading) Text("Analyzing samples…", color = Subtle)
                    val overview = details
                    if (overview != null) {
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
                }
            }
        }
        if (notice != null) Text(notice ?: "", color = Amber)
    }
}
