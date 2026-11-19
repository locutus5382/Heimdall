package com.locutus5382.heimdall.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.recording.SessionArchive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** F0/F1 read-only session view; the recorder remains the only DB writer. */
@Composable
fun SessionHistoryPanel(refreshKey: Long) {
    val context = LocalContext.current
    val archive = remember(context) { SessionArchive(context) }
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<SavedSession>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var exportTarget by remember { mutableStateOf<Long?>(null) }

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
                    message = "Session #$id exported: $count samples."
                } catch (e: Exception) {
                    message = "CSV export failed: " + e.javaClass.simpleName
                }
            }
        }
    }

    LaunchedEffect(refreshKey) {
        try {
            history = archive.recent()
        } catch (e: Exception) {
            message = "Session history unavailable: " + e.javaClass.simpleName
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("SAVED SESSIONS", color = androidx.compose.ui.graphics.Color(0xFFF2A900))
        if (history.isEmpty()) {
            Text("Completed recordings will appear here.")
        }
        history.forEach { s ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Session #${s.id}  ·  ${s.samples} samples")
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(s.startedAtMs)),
                        color = androidx.compose.ui.graphics.Color(0xFF9EADC2)
                    )
                }
                OutlinedButton(onClick = {
                    exportTarget = s.id
                    chooser.launch("Heimdall_session_${s.id}.csv")
                }) { Text("CSV") }
            }
        }
        if (message != null) Text(message ?: "")
    }
}
