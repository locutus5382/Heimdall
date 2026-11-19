package com.locutus5382.heimdall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.locutus5382.heimdall.engine.DukoSessionContext
import com.locutus5382.heimdall.engine.DukoSetting
import com.locutus5382.heimdall.engine.DukoSettingOrigin
import com.locutus5382.heimdall.engine.DukoUnderclockDeclaration
import com.locutus5382.heimdall.recording.DukoContextStore
import kotlinx.coroutines.launch
import org.json.JSONObject

private val DukoInk = Color(0xFFEAF1FF)
private val DukoMuted = Color(0xFF9EADC2)
private val DukoAccent = Color(0xFF65D5F5)

private fun manual(text: String): DukoSetting? =
    text.trim().takeIf { it.isNotBlank() }
        ?.let { DukoSetting(it, DukoSettingOrigin.USER_DECLARED) }

/**
 * Tags test context before, during or after a session. These annotations
 * never change the recorded samples or apply underclocks/fan settings.
 */
@Composable
internal fun DukoContextEditor(sessionId: Long) {
    val app = LocalContext.current.applicationContext
    val store = remember(app) { DukoContextStore(app) }
    val scope = rememberCoroutineScope()
    var model by remember(sessionId) {
        mutableStateOf(DukoSessionContext(sessionId))
    }
    var detected by remember(sessionId) { mutableStateOf<Boolean?>(null) }
    var deviceDetails by remember(sessionId) { mutableStateOf<String?>(null) }
    var notice by remember(sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        runCatching {
            model = store.read(sessionId) ?: DukoSessionContext(sessionId)
            detected = store.wasClusterTuneInstalled(sessionId)
            val raw = store.readDeviceProfileJson(sessionId)
            if (raw != null) {
                val data = JSONObject(raw)
                val fan = data.optString("fanSetting").takeUnless {
                    it.isBlank() || it == "null"
                }
                deviceDetails = buildString {
                    append(data.optString("manufacturer")).append(" ")
                        .append(data.optString("model"))
                    append(" · ").append(data.optString("soc", "SoC unknown"))
                    append(" · CPU policies ")
                        .append(data.optJSONArray("cpuPolicies")?.length() ?: 0)
                    if (fan != null) append(" · ").append(fan)
                }
            }
        }.onFailure { notice = "Could not read test context: " + it.javaClass.simpleName }
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF101D32), RoundedCornerShape(15.dp))
            .border(1.dp, Color(0xFF26364F), RoundedCornerShape(15.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text("DUKO · TEST CONTEXT", color = DukoAccent)
        Text(
            "Declared settings are labels, not automatically measured changes. " +
                "Heimdall never controls ClusterTune, fan or performance levels.",
            color = DukoMuted
        )
        if (deviceDetails != null) Text(
            "Detected at session start: $deviceDetails", color = DukoMuted
        )
        if (detected != null) Text(
            if (detected == true)
                "ClusterTune installed at session start — active profile NOT detected"
            else "ClusterTune package was not detected at session start",
            color = DukoMuted
        )

        DukoField("Game / workload", model.game.orEmpty()) {
            model = model.copy(game = it)
        }
        DukoField("Emulator / application", model.emulator.orEmpty()) {
            model = model.copy(emulator = it)
        }
        DukoField("Emulator version", model.emulatorVersion.orEmpty()) {
            model = model.copy(emulatorVersion = it)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                DukoField("Graphics driver", model.graphicsDriver.orEmpty()) {
                    model = model.copy(graphicsDriver = it)
                }
            }
            Column(Modifier.weight(1f)) {
                DukoField("Driver version / tag", model.graphicsDriverVersion.orEmpty()) {
                    model = model.copy(graphicsDriverVersion = it)
                }
            }
        }
        DukoField("Performance level (e.g. Standard / Performance)",
            model.performanceMode?.value.orEmpty()) {
            model = model.copy(performanceMode = manual(it))
        }
        DukoField("Fan profile (e.g. Smart / Sport / Max)",
            model.fanProfile?.value.orEmpty()) {
            model = model.copy(fanProfile = manual(it))
        }
        Text("Underclocking used during this test?", color = DukoInk)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DukoUnderclockDeclaration.entries.forEach { choice ->
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = model.underclockUsed == choice,
                    label = {
                        Text(when (choice) {
                            DukoUnderclockDeclaration.UNKNOWN -> "UNKNOWN"
                            DukoUnderclockDeclaration.NOT_USED -> "NO"
                            DukoUnderclockDeclaration.USED -> "YES"
                        })
                    },
                    onClick = { model = model.copy(underclockUsed = choice) }
                )
            }
        }
        DukoField("Tuner app (ClusterTune / other)", model.tunerName.orEmpty()) {
            model = model.copy(tunerName = it)
        }
        DukoField("Tuner profile (e.g. Medium Underclock)",
            model.tunerProfile?.value.orEmpty()) {
            model = model.copy(tunerProfile = manual(it))
        }
        DukoField("Test profile / device settings", model.testProfile.orEmpty()) {
            model = model.copy(testProfile = it)
        }
        DukoField("Matched segment / track", model.workloadMarker.orEmpty()) {
            model = model.copy(workloadMarker = it)
        }
        DukoField("Notes", model.notes.orEmpty()) {
            model = model.copy(notes = it)
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    val ok = runCatching { store.save(model) }.getOrDefault(false)
                    notice = if (ok) "Duko test settings saved locally."
                    else "Could not save Duko test settings."
                }
            }
        ) { Text("SAVE SESSION TEST SETTINGS", color = DukoAccent) }
        if (notice != null) Text(notice!!, color = DukoMuted)
    }
}

@Composable
private fun DukoField(label: String, value: String, update: (String) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = { update(it.take(256)) },
        label = { Text(label) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = DukoInk)
    )
}
