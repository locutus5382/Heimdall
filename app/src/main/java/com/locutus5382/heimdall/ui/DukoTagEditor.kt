package com.locutus5382.heimdall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.locutus5382.heimdall.engine.duko.DukoSessionContext
import com.locutus5382.heimdall.engine.duko.MeasurementOrigin
import com.locutus5382.heimdall.recording.DukoSessionContextStore
import kotlinx.coroutines.launch

private val DukoIce = Color(0xFFF2F6FC)
private val DukoMuted = Color(0xFF94A7C4)
private val DukoGold = Color(0xFFF0BD62)

private enum class TagEditorPage(val label: String) {
    WORKLOAD("GAME / DRIVER"), DEVICE("DEVICE SETTINGS"), NOTES("NOTES / SAVE")
}

/** Curated user-provided Obtainium emulation catalog; these are labels, not detected apps. */
private val emulatorCatalog = listOf(
    "aPS3e",
    "ARMSX1",
    "ARMSX2",
    "ARMSX2 Nightly",
    "ARMSX3",
    "Azahar",
    "Cemu",
    "Cemu (DS)",
    "Citra MMJ",
    "Citron Neo",
    "Dolphin Emulator",
    "Dolphin Emulator (Dev build)",
    "Dolphin-MMJR2-VBI",
    "DuckStation",
    "Eden",
    "Eden (Nightly)",
    "Flycast",
    "Gopher64",
    "Hakux",
    "MelonDS",
    "MelonDS Nightly",
    "NetherSX2-Classic",
    "NetherSX2-Patch",
    "NetherSX2-Turnip",
    "Pico8 Android",
    "Play!",
    "PPSSPP",
    "RetroArch (AArch64)",
    "RetroArch Nightly (AArch64)",
    "RPCSX",
    "ScummVM",
    "Swiff",
    "Vita3K",
    "WatermelonDS",
    "X1 BOX",
    "X360 Mobile",
    "Xendroid"
)

/**
 * A manual test-profile editor, not a device-control screen. When sessionId is
 * absent, save a default for the NEXT recording. When present, edit only that
 * session's metadata. Neither path writes to sysfs or reconfigures a fan.
 */
@Composable
internal fun DukoTagEditor(sessionId: Long? = null) {
    val app = LocalContext.current.applicationContext
    val store = remember(app) { DukoSessionContextStore(app) }
    val scope = rememberCoroutineScope()
    var tags by remember(sessionId) { mutableStateOf(store.readDraft()) }
    var nextOnly by remember(sessionId) {
        mutableStateOf(sessionId == null && store.isNextOnly())
    }
    var message by remember(sessionId) { mutableStateOf<String?>(null) }
    var step by rememberSaveable(sessionId) { mutableStateOf(TagEditorPage.WORKLOAD.name) }
    var edited by remember(sessionId) { mutableStateOf(false) }
    var loading by remember(sessionId) { mutableStateOf(sessionId != null) }
    LaunchedEffect(sessionId) {
        if (sessionId != null) {
            loading = true
            try { tags = store.get(sessionId) }
            catch (e: Exception) {
                message = "Could not read session tags: ${e.javaClass.simpleName}"
            } finally { loading = false }
        }
    }

    fun declared(p: DukoSessionContext) {
        tags = p.copy(origin = MeasurementOrigin.USER_DECLARED)
        edited = true
        message = null
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF111C31), RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFF192B49), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            if (sessionId == null) "NEXT TEST CONDITIONS"
            else "DUKO · SESSION #$sessionId",
            color = DukoGold
        )
        Text(
            if (sessionId == null)
                "Saved settings will be attached to the next recording."
            else "Edit labels for this session without altering telemetry samples.",
            color = DukoMuted
        )
        if (loading) Text("Loading session conditions…", color = DukoMuted)
        if (sessionId == null) {
            Text("APPLIES TO", color = DukoMuted)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !nextOnly, onClick = {
                    nextOnly = false; edited = true; message = null
                }, label = { Text("REUSABLE PRESET") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                FilterChip(selected = nextOnly, onClick = {
                    nextOnly = true; edited = true; message = null
                }, label = { Text("NEXT SESSION ONLY") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
            Text(if (nextOnly)
                "Settings are cleared after a successfully committed new session. "
                    + "Confirm their capture in the session details."
                else "These labels will carry forward; confirm each run before recording.",
                color = DukoMuted)
        }
        Text(if (edited) "UNSAVED CHANGES — save before leaving this screen."
            else "Changes are local until you explicitly save.", color = if (edited)
                DukoGold else DukoMuted)
        Text("TEST SETUP", color = DukoGold)
        TagEditorPage.entries.forEach { page ->
            FilterChip(
                selected = step == page.name,
                onClick = { step = page.name },
                label = { Text(page.label) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            )
        }
        if (step == TagEditorPage.WORKLOAD.name) {
        OutlinedTextField(tags.game, { declared(tags.copy(game = it)) },
            label = { Text("Game / workload") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Text("EMULATOR CATALOG · filter and select a variant", color = DukoMuted)
        var emulatorFilter by remember { mutableStateOf("") }
        OutlinedTextField(
            value = emulatorFilter,
            onValueChange = { emulatorFilter = it },
            label = { Text("Find emulator (e.g. Eden, NetherSX2)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        val matches = emulatorCatalog.filter {
            it.contains(emulatorFilter.trim(), ignoreCase = true)
        }
        Text("${matches.size} matching presets · free text also supported", color = DukoMuted)
        DukoOptions(
            choices = matches.take(12),
            selected = tags.emulator,
            onChoose = { declared(tags.copy(emulator = it)) }
        )
        OutlinedTextField(tags.emulator, { declared(tags.copy(emulator = it)) },
            label = { Text("Emulator and version") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(tags.graphicsDriver, {
            declared(tags.copy(graphicsDriver = it))
        }, label = { Text("Graphics driver and version") },
            singleLine = true, modifier = Modifier.fillMaxWidth())

        }
        if (step == TagEditorPage.DEVICE.name) {
        Text("DEVICE PERFORMANCE MODE", color = DukoMuted)
        DukoOptions(
            choices = listOf("Standard", "Performance", "High Performance", "Custom"),
            selected = tags.performanceMode,
            onChoose = { declared(tags.copy(performanceMode = it)) }
        )
        OutlinedTextField(tags.performanceMode, {
            declared(tags.copy(performanceMode = it))
        }, label = { Text("Performance mode (as shown by device)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())

        Text("FAN PROFILE", color = DukoMuted)
        DukoOptions(
            choices = listOf("Quiet", "Smart", "Sport", "Max", "Custom"),
            selected = tags.fanProfile,
            onChoose = { declared(tags.copy(fanProfile = it)) }
        )
        OutlinedTextField(tags.fanProfile, { declared(tags.copy(fanProfile = it)) },
            label = { Text("Fan profile (device-specific)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())

        Text("CLUSTERTUNE / UNDERCLOCK", color = DukoMuted)
        DukoOptions(
            choices = listOf("Unknown", "Off", "Stock", "Small", "Medium", "Large", "Custom"),
            selected = tags.clusterTuneProfile,
            onChoose = { choice ->
                declared(tags.copy(
                    clusterTuneProfile = choice,
                    clusterTuneEnabled = when (choice) {
                        "Unknown" -> null
                        "Off" -> false
                        else -> true
                    },
                    tuningTool = if (choice != "Unknown" && choice != "Off")
                        "ClusterTune" else ""
                ))
            }
        )
        OutlinedTextField(tags.clusterTuneProfile, { value ->
            declared(tags.copy(clusterTuneProfile = value,
                clusterTuneEnabled = when(value.trim()) {
                    "Unknown", "" -> null
                    "Off" -> false
                    else -> true
                }))
        }, label = { Text("Underclock profile or custom name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(tags.tuningTool, {
            declared(tags.copy(tuningTool = it))
        }, label = { Text("Tuning tool (e.g. ClusterTune)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())

        }
        if (step == TagEditorPage.NOTES.name) {
        OutlinedTextField(tags.notes, { declared(tags.copy(notes = it)) },
            label = { Text("Test notes / cold or warm run / settings") },
            maxLines = 3, modifier = Modifier.fillMaxWidth())

        Text(
            "These are USER-DECLARED labels. Heimdall records actual CPU policy " +
                "ceilings separately; those do not prove a ClusterTune preset, " +
                "fan profile or performance mode is active.",
            color = DukoMuted
        )
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            enabled = !loading,
            onClick = {
                val saved = tags.sanitized().copy(
                    origin = MeasurementOrigin.USER_DECLARED)
                if (sessionId == null) {
                    store.saveDraft(saved, nextOnly)
                    edited = false
                    message = if (nextOnly) "Saved for the next recording only."
                        else "Saved as a reusable test profile."
                } else {
                    scope.launch {
                        try {
                            store.save(sessionId, saved)
                            edited = false
                            message = "Session #$sessionId test conditions updated."
                        } catch (e: Exception) {
                            message = "Save failed: ${e.javaClass.simpleName}"
                        }
                    }
                }
            }
        ) { Text("SAVE TEST CONDITIONS", color = DukoGold) }
        message?.let { Text(it, color = DukoIce) }
    }
}

@Composable
private fun DukoOptions(
    choices: List<String>,
    selected: String,
    onChoose: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        choices.forEach { choice ->
            FilterChip(
                selected = selected.equals(choice, ignoreCase = true),
                onClick = { onChoose(choice) },
                label = { Text(choice, color = DukoIce) }
            )
        }
    }
}
