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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.locutus5382.heimdall.recording.DiagnosticSnapshot
import com.locutus5382.heimdall.recording.RuntimeDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/** Explicit, one-shot hardware capability inspection; no idle polling. */
@Composable
fun DiagnosticsPanel() {
    val app = LocalContext.current.applicationContext
    val probe = remember(app) { RuntimeDiagnostics(app) }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<DiagnosticSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { destination ->
        val report = snapshot?.asText()
        if (destination != null && report != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val stream = app.contentResolver.openOutputStream(destination)
                            ?: error("DESTINATION_UNAVAILABLE")
                        stream.use { it.write(report.toByteArray(StandardCharsets.UTF_8)) }
                    }
                    message = "Device diagnostics saved locally."
                } catch (error: Exception) {
                    message = "Diagnostic export failed: ${error.javaClass.simpleName}"
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF111C31), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("LOCAL DEVICE CAPABILITIES", color = Color(0xFFF0BD62))
        Text(
            "Inspect what this Android device actually exposes. " +
                "Nothing is uploaded or changed.",
            color = Color(0xFF94A7C4)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            snapshot = probe.capture()
                        } catch (e: Exception) {
                            message = "Inspection failed: ${e.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) {
                Text(if (busy) "INSPECTING…" else "RUN DIAGNOSTICS")
            }
            if (snapshot != null) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { export.launch("Heimdall_device_diagnostics.txt") }
                ) {
                    Text("EXPORT TXT")
                }
            }
        }
        snapshot?.let { result ->
            result.lines.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        label,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        color = Color(0xFF94A7C4)
                    )
                    Text(
                        value,
                        modifier = Modifier.weight(1f),
                        color = Color(0xFFF2F6FC)
                    )
                }
            }
        }
        message?.let { Text(it, color = Color(0xFFF0BD62)) }
    }
}
