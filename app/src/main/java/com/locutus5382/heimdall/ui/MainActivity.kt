package com.locutus5382.heimdall.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.locutus5382.heimdall.HeimdallApplication
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.ui.dashboard.DashboardViewModel
import com.locutus5382.heimdall.ui.theme.HeimdallTheme
import com.locutus5382.heimdall.ui.v2.BifrostV2Destination
import com.locutus5382.heimdall.ui.v2.BifrostV2Shell
import com.locutus5382.heimdall.ui.v2.V2LiveScreen
import com.locutus5382.heimdall.ui.v2.V2AnalyticsScreen
import com.locutus5382.heimdall.ui.v2.V2RunsScreen
import com.locutus5382.heimdall.ui.v2.V2CompareScreen
import com.locutus5382.heimdall.ui.v2.V2ToolsScreen

/**
 * Bifrost V2 host: Activity owns only presentation adapters. The recorder
 * Service, original SQLite database and all DUKO calculations are unchanged.
 *
 * No legacy shell, blade, SessionHistoryPanel or Canvas HUD is mounted.
 */
class MainActivity : ComponentActivity() {
    private lateinit var dashboard: DashboardViewModel
    private lateinit var sessions: SessionScreenViewModel
    private lateinit var compare: CompareScreenViewModel
    private lateinit var multi: DukoMultiRunViewModel
    private lateinit var diagnostics: DiagnosticsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as HeimdallApplication).graph
        dashboard = ViewModelProvider(this, DashboardViewModel.Factory(graph))
            .get(DashboardViewModel::class.java)
        sessions = ViewModelProvider(this,
            SessionScreenViewModel.Factory(SessionScreenRepository(this)))
            .get(SessionScreenViewModel::class.java)
        compare = ViewModelProvider(this,
            CompareScreenViewModel.Factory(ComparisonRepository(this)))
            .get(CompareScreenViewModel::class.java)
        multi = ViewModelProvider(this,
            DukoMultiRunViewModel.Factory(MultiRunRepository(this)))
            .get(DukoMultiRunViewModel::class.java)
        diagnostics = ViewModelProvider(this,
            DiagnosticsViewModel.Factory(AndroidDiagnosticsRepository(this)))
            .get(DiagnosticsViewModel::class.java)

        dashboard.inspect()
        setContent {
            val current by dashboard.uiState.collectAsStateWithLifecycle()
            val notifications = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (!granted) dashboard.reportIssue(
                    "Notification permission denied; STOP remains available in the app.")
            }
            val startRecording: () -> Unit = {
                if (dashboard.start() && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED)
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            val stopRecording: () -> Unit = { dashboard.stop() }
            val finishedId = (current.status as? RecorderStatus.Finished)?.sessionId ?: 0L

            HeimdallTheme {
                BifrostV2Shell { page, navigate ->
                    when (page) {
                        BifrostV2Destination.LIVE -> V2LiveScreen(
                            state = current,
                            onStart = startRecording,
                            onStop = stopRecording,
                            onAnalyze = { navigate(BifrostV2Destination.ANALYZE) },
                            onRuns = { navigate(BifrostV2Destination.RUNS) },
                            onTools = { navigate(BifrostV2Destination.TOOLS) },
                        )
                        BifrostV2Destination.ANALYZE -> V2AnalyticsScreen(
                            sessions, finishedId,
                            onCompare = { sessionId ->
                                compare.selectBaselineFromSessions(sessionId)
                                navigate(BifrostV2Destination.COMPARE)
                            },
                        )
                        BifrostV2Destination.RUNS -> V2RunsScreen(
                            sessions, finishedId,
                            onAnalyze = { sessionId ->
                                sessions.focusSession(sessionId)
                                navigate(BifrostV2Destination.ANALYZE)
                            },
                        )
                        BifrostV2Destination.COMPARE -> V2CompareScreen(
                            compare, multi, finishedId)
                        BifrostV2Destination.TOOLS -> V2ToolsScreen(diagnostics)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::dashboard.isInitialized)
            dashboard.onOverlayPermissionChanged(Settings.canDrawOverlays(this))
    }
}
