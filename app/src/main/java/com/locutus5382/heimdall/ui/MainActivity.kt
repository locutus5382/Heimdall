package com.locutus5382.heimdall.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locutus5382.heimdall.HeimdallApplication
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.recording.SessionArchive
import com.locutus5382.heimdall.ui.dashboard.BifrostDashboard
import com.locutus5382.heimdall.ui.dashboard.DashboardViewModel
import com.locutus5382.heimdall.ui.navigation.BifrostBlade
import com.locutus5382.heimdall.ui.navigation.BifrostTab
import com.locutus5382.heimdall.ui.navigation.HeimdallShell
import com.locutus5382.heimdall.ui.theme.BifrostColors
import com.locutus5382.heimdall.ui.theme.HeimdallTheme

/**
 * Android UI/permission boundary. The Application owns the shared adapters,
 * the ViewModel owns UI state, the Service owns the ONLY recording engine.
 * No service/SQLite object is constructed by the Dashboard.
 */
class MainActivity : ComponentActivity() {
    private lateinit var dashboardModel: DashboardViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as HeimdallApplication).graph
        dashboardModel = ViewModelProvider(this, DashboardViewModel.Factory(graph))
            .get(DashboardViewModel::class.java)
        dashboardModel.inspect()
        setContent {
            val state by dashboardModel.uiState.collectAsStateWithLifecycle()
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (!granted) dashboardModel.reportIssue(
                    "Android may hide the notification; in-app STOP remains available."
                )
            }
            val start: () -> Unit = {
                dashboardModel.start()
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val stop: () -> Unit = { dashboardModel.stop() }
            val toggleHud: () -> Unit = {
                if (dashboardModel.toggleHud()) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                    } catch (error: Exception) {
                        dashboardModel.reportIssue(
                            "Overlay permission screen unavailable: " + error.javaClass.simpleName
                        )
                    }
                }
            }
            val refreshKey = (state.status as? RecorderStatus.Finished)?.sessionId ?: 0L
            HeimdallTheme {
                HeimdallShell(
                    dashboard = { openBlade, openTab ->
                        BifrostDashboard(
                            status = state.status,
                            issue = state.issue,
                            hudEnabled = state.hudEnabled,
                            overlayPermission = state.overlayPermission,
                            commandPending = state.commandPending,
                            onStart = start,
                            onStop = stop,
                            onToggleHud = toggleHud,
                            onSetup = { openBlade(BifrostBlade.SETUP) },
                            onHudStudio = { openBlade(BifrostBlade.HUD_STUDIO) },
                            onSessions = { openTab(BifrostTab.SESSIONS) },
                            onDiagnostics = { openBlade(BifrostBlade.DIAGNOSTICS) },
                        )
                    },
                    sessions = { BifrostSessionsScreen(refreshKey) },
                    compare = { BifrostCompareScreen(refreshKey) },
                    bladeContent = { blade, _, openTab ->
                        when (blade) {
                            BifrostBlade.SETUP -> DukoTagEditor(
                                sessionId = (state.status as? RecorderStatus.Recording)?.sessionId
                            )
                            BifrostBlade.HUD_STUDIO -> HudStudioPanel()
                            BifrostBlade.RUN_PICKER -> BifrostRunPicker(
                                refreshKey = refreshKey,
                                openCompare = { openTab(BifrostTab.COMPARE) }
                            )
                            BifrostBlade.DIAGNOSTICS -> DiagnosticsPanel()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::dashboardModel.isInitialized) {
            dashboardModel.onOverlayPermissionChanged(Settings.canDrawOverlays(this))
        }
    }
}

/** One independently scrollable session surface; no second comparison panel. */
@Composable
private fun BifrostSessionsScreen(refreshKey: Long) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("SESSIONS  /  DUKO", color = BifrostColors.Signal)
        Text("Review saved telemetry, evidence quality and session exports.",
            color = BifrostColors.Muted)
        SessionHistoryPanel(refreshKey = refreshKey, showComparison = false)
    }
}

/** Existing DUKO compare adapter is the only A/B UI authority. */
@Composable
private fun BifrostCompareScreen(refreshKey: Long) {
    val app = LocalContext.current.applicationContext
    val archive = remember(app) { SessionArchive(app) }
    var sessions by remember { mutableStateOf<List<SavedSession>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshKey) {
        try {
            sessions = archive.recent()
            problem = null
        } catch (error: Exception) {
            problem = "Saved runs unavailable: " + error.javaClass.simpleName
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("COMPARE  /  DUKO A–B", color = BifrostColors.Signal)
        Text("Select two real recordings. Results describe device telemetry, not FPS.",
            color = BifrostColors.Muted)
        if (problem != null) Text(problem.orEmpty(), color = BifrostColors.Coral)
        if (sessions.size < 2) {
            Text("Record at least two sessions to compare.",
                color = BifrostColors.Muted)
        } else {
            DukoComparisonPanel(sessions)
        }
    }
}

/** Contextual entry, not a duplicate A/B comparison engine in the blade. */
@Composable
private fun BifrostRunPicker(refreshKey: Long, openCompare: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val archive = remember(app) { SessionArchive(app) }
    var sessions by remember { mutableStateOf<List<SavedSession>>(emptyList()) }
    LaunchedEffect(refreshKey) {
        sessions = runCatching { archive.recent(8) }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("RECORDED RUNS", color = BifrostColors.Signal)
        sessions.forEach { session ->
            Text("Session #" + session.id + " · " + session.samples + " samples",
                color = BifrostColors.Ice)
        }
        if (sessions.size < 2) Text("At least two completed runs are required.",
            color = BifrostColors.Muted)
        OutlinedButton(onClick = openCompare, modifier = Modifier.fillMaxWidth()) {
            Text("OPEN A/B COMPARISON")
        }
    }
}
