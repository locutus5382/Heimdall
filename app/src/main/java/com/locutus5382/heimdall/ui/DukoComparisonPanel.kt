package com.locutus5382.heimdall.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.locutus5382.heimdall.engine.duko.DukoAbReportExport
import com.locutus5382.heimdall.engine.duko.DukoComparisonQuality
import com.locutus5382.heimdall.engine.duko.DukoComparisonReport
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.recording.SessionArchive
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

private val AbInk = Color(0xFFEAF1FF)
private val AbSubtle = Color(0xFF9EADC2)
private val AbAmber = Color(0xFFF2A900)
private val AbSlate = Color(0xFF121E32)
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

/** Read-only A/B selection. Never starts, stops or modifies recordings. */
@Composable
internal fun DukoComparisonPanel(sessions: List<SavedSession>) {
    val context = LocalContext.current
    val archive = remember(context) { SessionArchive(context) }
    var a by remember { mutableStateOf<Long?>(null) }
    var b by remember { mutableStateOf<Long?>(null) }
    var request by remember { mutableStateOf(0) }
    var presetA by remember { mutableStateOf(AbWindowPreset.FULL) }
    var presetB by remember { mutableStateOf(AbWindowPreset.FULL) }
    var startA by remember { mutableStateOf("0") }
    var endA by remember { mutableStateOf("") }
    var startB by remember { mutableStateOf("0") }
    var endB by remember { mutableStateOf("") }
    var selectedWindows by remember { mutableStateOf<Pair<DukoWindow, DukoWindow>?>(null) }
    var report by remember { mutableStateOf<DukoComparisonReport?>(null) }
    var busy by remember { mutableStateOf(false) }

    var error by remember { mutableStateOf<String?>(null) }
    val exportScope = rememberCoroutineScope()
    // Snapshot when the user requests a document. Never recompute after
    // the picker returns from possibly changed session selections.
    var pendingText by remember { mutableStateOf<String?>(null) }
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val textPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val payload = pendingText
        pendingText = null
        if (uri != null && payload != null) {
            exportScope.launch {
                exportStatus = "Saving TXT…"
                exportStatus = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(payload.toByteArray(Charsets.UTF_8))
                            it.flush()
                        } ?: throw java.io.IOException("DOCUMENT_PROVIDER_REFUSED_WRITE")
                    }
                    "TXT report saved."
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    "TXT export failed: " + e.javaClass.simpleName
                }
            }
        } else exportStatus = "TXT export cancelled."
    }
    val csvPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val payload = pendingCsv
        pendingCsv = null
        if (uri != null && payload != null) {
            exportScope.launch {
                exportStatus = "Saving CSV…"
                exportStatus = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(payload.toByteArray(Charsets.UTF_8))
                            it.flush()
                        } ?: throw java.io.IOException("DOCUMENT_PROVIDER_REFUSED_WRITE")
                    }
                    "CSV report saved."
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    "CSV export failed: " + e.javaClass.simpleName
                }
            }
        } else exportStatus = "CSV export cancelled."
    }

    LaunchedEffect(a, b, request) {
        report = null
        selectedWindows = null
        error = null
        exportStatus = null
        val left = a
        val right = b
        if (request == 0 || left == null || right == null || left == right) {
            busy = false
            return@LaunchedEffect
        }
        busy = true
        try {
            val boundsA = archive.dukoMonotonicBounds(left)
            val boundsB = archive.dukoMonotonicBounds(right)
            val windowA = boundsA?.let { if (presetA == AbWindowPreset.CUSTOM) customAbWindow(it, startA, endA) else presetA.select(it) }
            val windowB = boundsB?.let { if (presetB == AbWindowPreset.CUSTOM) customAbWindow(it, startB, endB) else presetB.select(it) }
            if (windowA == null || windowB == null) {
                error = "Invalid window: enter start/end seconds within each recorded session (end must exceed start)."
            } else {
                report = archive.dukoCompare(left, right, windowA, windowB)
                if (report == null) error = "Selected windows have insufficient monotonic evidence."
                else selectedWindows = windowA to windowB
            }
        } catch (e: Exception) {
            error = "Comparison unavailable: " + e.javaClass.simpleName
        } finally {
            busy = false
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(AbSlate, RoundedCornerShape(17.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("DUKO A/B COMPARISON", color = AbAmber)
        Text("Select two distinct completed sessions. User tags do not verify hardware settings.", color = AbSubtle)
        sessions.forEach { session ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("#${session.id}", color = AbInk, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { a = session.id; request = 0 }) {
                    Text(if (a == session.id) "A ✓" else "SET A")
                }
                OutlinedButton(onClick = { b = session.id; request = 0 }) {
                    Text(if (b == session.id) "B ✓" else "SET B")
                }
            }
        }
        Text("Analysis windows · each recording is bounded independently", color = AbSubtle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                presetA = AbWindowPreset.entries[
                    (presetA.ordinal + 1) % AbWindowPreset.entries.size
                ]
                request = 0
            }) { Text("A: " + presetA.label) }
            OutlinedButton(onClick = {
                presetB = AbWindowPreset.entries[
                    (presetB.ordinal + 1) % AbWindowPreset.entries.size
                ]
                request = 0
            }) { Text("B: " + presetB.label) }
        }
        if (presetA == AbWindowPreset.CUSTOM) {
            Text("A: seconds from first recorded sample", color = AbSubtle)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startA, { startA = it; request = 0 },
                    label = { Text("Start s") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(endA, { endA = it; request = 0 },
                    label = { Text("End s") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
        if (presetB == AbWindowPreset.CUSTOM) {
            Text("B: seconds from first recorded sample", color = AbSubtle)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startB, { startB = it; request = 0 },
                    label = { Text("Start s") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(endB, { endB = it; request = 0 },
                    label = { Text("End s") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = a != null && b != null && a != b && !busy,
                onClick = { request++ }
            ) { Text("COMPARE") }
            OutlinedButton(onClick = {
                a = null; b = null; request = 0; report = null; error = null
                selectedWindows = null
                presetA = AbWindowPreset.FULL; presetB = AbWindowPreset.FULL
                startA = "0"; endA = ""; startB = "0"; endB = ""
            }) { Text("CLEAR") }
        }
        if (busy) Text("Analyzing recorded evidence…", color = AbSubtle)
        if (error != null) Text(error.orEmpty(), color = AbAmber)
        if (exportStatus != null) Text(exportStatus.orEmpty(), color = AbSubtle)
        report?.let { result ->
            selectedWindows?.let { (wa, wb) ->
                Text("A: ${wa.startMs}–${wa.endMs} ms (${wa.durationMs} ms)", color = AbSubtle)
                Text("B: ${wb.startMs}–${wb.endMs} ms (${wb.durationMs} ms)", color = AbSubtle)
            }
            Text("A #${result.sessionA} → B #${result.sessionB} · ${result.quality.name}", color = AbInk)

            selectedWindows?.let { (wa, wb) ->
                Text("DUKO REPORT · comparison-only · saved locally", color = AbAmber)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        pendingText = DukoAbReportExport.text(result, wa, wb)
                        exportStatus = null
                        textPicker.launch("DUKO_AB_${result.sessionA}_${result.sessionB}.txt")
                    }) { Text("SAVE TXT") }
                    OutlinedButton(onClick = {
                        pendingCsv = DukoAbReportExport.csv(result, wa, wb)
                        exportStatus = null
                        csvPicker.launch("DUKO_AB_${result.sessionA}_${result.sessionB}.csv")
                    }) { Text("SAVE CSV") }
                }
            }
            result.contextWarnings.forEach {
                Text(it.replace('_', ' '), color = AbSubtle)
            }
            listOf(
                DukoMetric.CPU_LOAD, DukoMetric.GPU_LOAD,
                DukoMetric.CPU_CLOCK, DukoMetric.GPU_CLOCK,
                DukoMetric.BATTERY_TEMPERATURE, DukoMetric.CPU_TEMPERATURE,
                DukoMetric.GPU_TEMPERATURE, DukoMetric.AVAILABLE_RAM
            ).forEach { metric ->
                result.metrics[metric]?.let { item ->
                    val delta = if (item.quality == DukoComparisonQuality.NOT_COMPARABLE)
                        "not comparable"
                    else "Δ ${abNumber(item.delta)} ${metric.unit.symbol} · ${abNumber(item.percentChange)}%"
                    Text(
                        "${metric.name.replace('_', ' ')}: A ${abNumber(item.aMean)} / B ${abNumber(item.bMean)} ${metric.unit.symbol} · $delta · ${item.quality.name}",
                        color = AbInk
                    )
                    if (item.warnings.isNotEmpty())
                        Text(item.warnings.joinToString(", ").replace('_', ' '), color = AbSubtle)
                }
            }
            Text("Measured telemetry is not FPS, a causal performance claim or a game benchmark.", color = AbSubtle)
        }
    }
}
