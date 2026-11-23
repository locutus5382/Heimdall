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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locutus5382.heimdall.HeimdallApplication
import com.locutus5382.heimdall.engine.RecorderStatus
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
    private lateinit var sessionsModel: SessionScreenViewModel
    private lateinit var compareModel: CompareScreenViewModel
    private lateinit var diagnosticsModel: DiagnosticsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as HeimdallApplication).graph
        dashboardModel = ViewModelProvider(this, DashboardViewModel.Factory(graph))
            .get(DashboardViewModel::class.java)
        sessionsModel = ViewModelProvider(this,
            SessionScreenViewModel.Factory(SessionScreenRepository(this)))
            .get(SessionScreenViewModel::class.java)
        compareModel = ViewModelProvider(this,
            CompareScreenViewModel.Factory(ComparisonRepository(this)))
            .get(CompareScreenViewModel::class.java)
        diagnosticsModel = ViewModelProvider(this,
            DiagnosticsViewModel.Factory(AndroidDiagnosticsRepository(this)))
            .get(DiagnosticsViewModel::class.java)
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
                if (dashboardModel.start() && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val stop: () -> Unit = { dashboardModel.stop() }
            val requestOverlayPermission: () -> Unit = {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } catch (error: Exception) {
                    dashboardModel.reportIssue(
                        "Overlay permission screen unavailable: " + error.javaClass.simpleName
                    )
                }
            }
            val toggleHud: () -> Unit = {
                if (dashboardModel.toggleHud()) requestOverlayPermission()
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
                            testProfile = state.testProfile,
                            onStart = start,
                            onStop = stop,
                            onToggleHud = toggleHud,
                            onSetup = { openBlade(BifrostBlade.SETUP) },
                            onHudStudio = { openBlade(BifrostBlade.HUD_STUDIO) },
                            onSessions = { openTab(BifrostTab.SESSIONS) },
                            onDiagnostics = { openBlade(BifrostBlade.DIAGNOSTICS) },
                        )
                    },
                    sessions = { openTab ->
                        BifrostSessionsScreen(refreshKey, sessionsModel) { id ->
                            compareModel.selectA(id)
                            openTab(BifrostTab.COMPARE)
                        }
                    },
                    compare = { BifrostCompareScreen(refreshKey, compareModel) },
                    bladeContent = { blade, _, openTab ->
                        when (blade) {
                            // Dashboard SETUP always configures the NEXT session.
                            // Editing an existing session's tags belongs to Sessions.
                            // Never silently switch targets while recording is active.
                            BifrostBlade.SETUP -> DukoTagEditor()
                            BifrostBlade.HUD_STUDIO -> HudStudioPanel(
                                hudEnabled = state.hudEnabled,
                                overlayPermission = state.overlayPermission,
                                recorderStatus = state.status,
                                onToggleHud = toggleHud,
                                onRequestOverlayPermission = requestOverlayPermission,
                            )
                            BifrostBlade.RUN_PICKER -> BifrostRunPicker(
                                refreshKey = refreshKey,
                                model = compareModel,
                                openCompare = { openTab(BifrostTab.COMPARE) }
                            )
                            BifrostBlade.DIAGNOSTICS ->
                                DiagnosticsPanel(diagnosticsModel)
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
private fun BifrostSessionsScreen(
    refreshKey: Long,
    model: SessionScreenViewModel,
    onCompare: (Long) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("SESSIONS  /  DUKO", color = BifrostColors.Signal)
        Text("Review saved telemetry, evidence quality and session exports.",
            color = BifrostColors.Muted)
        SessionHistoryPanel(refreshKey = refreshKey, model = model,
            onCompare = onCompare)
    }
}

/** A/B state and historical list are owned by the Activity's Compare ViewModel. */
@Composable
private fun BifrostCompareScreen(refreshKey: Long, model: CompareScreenViewModel) {
    val state by model.ui.collectAsStateWithLifecycle()
    LaunchedEffect(refreshKey) { model.refresh(refreshKey) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("COMPARE  /  DUKO A–B", color = BifrostColors.Signal)
        Text("Select two real recordings. Results describe device telemetry, not FPS.",
            color = BifrostColors.Muted)
        if (state.loading) Text("Loading saved runs…", color = BifrostColors.Muted)
        if (state.issue != null) Text(state.issue.orEmpty(), color = BifrostColors.Coral)
        if (state.sessions.size < 2) {
            Text("Record at least two sessions to compare.", color = BifrostColors.Muted)
        } else {
            DukoComparisonPanel(model)
        }
    }
}

/** Contextual entry only; it uses the same Compare list, not a second DB owner. */
@Composable
private fun BifrostRunPicker(
    refreshKey: Long,
    model: CompareScreenViewModel,
    openCompare: () -> Unit,
) {
    val state by model.ui.collectAsStateWithLifecycle()
    LaunchedEffect(refreshKey) { model.refresh(refreshKey) }
    val sessions = state.sessions.take(10)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("SELECT COMPARISON RUNS", color = BifrostColors.Signal)
        Text("Choose A as the baseline and B as the candidate. Both use saved recordings.",
            color = BifrostColors.Muted)
        if (state.loading) Text("Loading saved runs…", color = BifrostColors.Muted)
        sessions.forEach { session ->
            Row(Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("SESSION #${session.id}", color = BifrostColors.Ice)
                    Text("${session.samples} samples · ${session.reason}",
                        color = BifrostColors.Muted)
                }
                OutlinedButton(
                    onClick = { model.selectA(session.id) },
                    enabled = state.a != session.id,
                ) { Text(if (state.a == session.id) "A ✓" else "SET A") }
                OutlinedButton(
                    onClick = { model.selectB(session.id) },
                    enabled = state.b != session.id,
                ) { Text(if (state.b == session.id) "B ✓" else "SET B") }
            }
        }
        if (sessions.size < 2) Text("At least two completed runs are required.",
            color = BifrostColors.Muted)
        Text(
            "Baseline: ${state.a?.let { "#$it" } ?: "none"} · " +
                "Candidate: ${state.b?.let { "#$it" } ?: "none"}",
            color = BifrostColors.Amber
        )
        if (state.a != null && state.a == state.b) {
            Text("Choose two different sessions.", color = BifrostColors.Coral)
        }
        OutlinedButton(onClick = openCompare, modifier = Modifier.fillMaxWidth()) {
            Text("OPEN FULL A/B COMPARISON")
        }
    }
}
