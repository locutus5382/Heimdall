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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locutus5382.heimdall.ui.theme.BifrostColors
import java.text.DateFormat
import java.util.Date

/**
 * Bifrost Diagnostics blade. All local probing, asynchronous work, state and
 * SAF export ownership stay in the Activity-scoped DiagnosticsViewModel.
 * No network calls, root access, recorder service or background polling.
 */
@Composable
internal fun DiagnosticsPanel(model: DiagnosticsViewModel) {
    val state by model.ui.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> model.onExportResult(uri) }

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(BifrostColors.Editorial, RoundedCornerShape(18.dp))
            .border(1.dp, BifrostColors.Cobalt, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("DEVICE DIAGNOSTICS", color = BifrostColors.Amber)
        Text(
            "Read-only inspection of the sensors, permissions and recording " +
                "database available on this device. Nothing is uploaded or modified.",
            color = BifrostColors.Muted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !state.inspecting && !state.writing &&
                    state.pendingExportText == null,
                onClick = model::inspect,
            ) {
                Text(if (state.inspecting) "INSPECTING…" else
                    if (state.snapshot == null) "RUN DIAGNOSTICS" else "REFRESH DIAGNOSTICS")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = state.snapshot != null && !state.inspecting &&
                    !state.writing && state.pendingExportText == null,
                onClick = { model.requestExport()?.let(picker::launch) },
            ) { Text("EXPORT TXT") }
        }

        state.snapshot?.let { result ->
            Text("CAPTURED " +
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(result.capturedAtMs)),
                color = BifrostColors.Signal)
            result.lines.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(label, modifier = Modifier.weight(1f), color = BifrostColors.Muted)
                    Text(value, modifier = Modifier.weight(1f), color = BifrostColors.Ice)
                }
            }
        }

        if (state.snapshot == null && !state.inspecting) {
            Text("No inspection yet. Run a local capability check to see " +
                "which readings are actually available.",
                color = BifrostColors.Muted)
        }
        state.message?.let { Text(it, color = BifrostColors.Amber) }
        Text("Unavailable readings are not zero. Diagnostics cannot establish " +
            "game FPS, throttling cause or power consumed by a game.",
            color = BifrostColors.Muted)
    }
}
