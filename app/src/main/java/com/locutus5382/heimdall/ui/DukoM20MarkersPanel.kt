package com.locutus5382.heimdall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.DukoSessionMarker
import com.locutus5382.heimdall.recording.DukoSessionMarkerStore
import com.locutus5382.heimdall.ui.theme.BifrostColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

private fun markAt(window: DukoWindow, timestamp: Long): String {
    val seconds = (timestamp - window.startMs).coerceAtLeast(0) / 1000L
    return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
}

@Composable
internal fun DukoM20MarkersPanel(
    sessionId: Long,
    bounds: DukoWindow,
    markers: List<DukoSessionMarker>,
    onMarkersChange: (List<DukoSessionMarker>) -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val store = remember(app) { DukoSessionMarkerStore(app) }
    val scope = rememberCoroutineScope()
    var label by rememberSaveable(sessionId, "marker-label") { mutableStateOf("") }
    var position by rememberSaveable(sessionId, "marker-position") {
        mutableFloatStateOf(.5f)
    }
    var busy by remember(sessionId) { mutableStateOf(false) }
    var notice by remember(sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId) {
        try { onMarkersChange(store.list(sessionId)) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            notice = "Could not load annotations: " + error.javaClass.simpleName
        }
    }
    val time = bounds.startMs +
        (bounds.durationMs.toDouble() * position.toDouble()).toLong()
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(BifrostColors.Blade, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("H / SESSION ANNOTATIONS", color = BifrostColors.Amber)
        Text("Add a note at a recorded monotonic time. Markers are separate " +
            "metadata; raw telemetry timestamps and readings stay unchanged.",
            fontSize = 11.sp, color = BifrostColors.Muted)
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(120) },
            label = { Text("MARKER LABEL") },
            placeholder = { Text("Example: new driver, boss fight, loading screen") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Slider(
            value = position,
            onValueChange = { position = it.coerceIn(0f, 1f) },
            valueRange = 0f..1f
        )
        Text("Marker time +" + markAt(bounds, time) + " · " +
            (markers.size) + " saved / " + DukoSessionMarkerStore.MAX_PER_SESSION,
            color = BifrostColors.Ice, fontSize = 11.sp)
        OutlinedButton(
            enabled = !busy && label.trim().isNotEmpty() &&
                markers.size < DukoSessionMarkerStore.MAX_PER_SESSION,
            onClick = {
                busy = true
                scope.launch {
                    try {
                        store.add(sessionId, time, label)
                        onMarkersChange(store.list(sessionId))
                        label = ""
                        notice = "Annotation saved at original monotonic timestamp."
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) {
                        notice = "Could not save annotation: " +
                            error.javaClass.simpleName
                    } finally { busy = false }
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("ADD MARKER") }
        markers.take(40).forEach { marker ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("+" + markAt(bounds, marker.elapsedMs) +
                        " · " + marker.label, color = BifrostColors.Ice,
                        fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                store.delete(sessionId, marker.id)
                                onMarkersChange(store.list(sessionId))
                                notice = "Marker removed. Telemetry is unchanged."
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) {
                                notice = "Could not delete marker: " +
                                    error.javaClass.simpleName
                            } finally { busy = false }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("DELETE", fontSize = 11.sp) }
            }
        }
        if (markers.size > 40) Text("Showing the first 40 annotations.",
            color = BifrostColors.Muted, fontSize = 10.sp)
        notice?.let { Text(it, color = BifrostColors.Amber, fontSize = 11.sp) }
    }
}
