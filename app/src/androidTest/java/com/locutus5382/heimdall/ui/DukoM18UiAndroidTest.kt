package com.locutus5382.heimdall.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.engine.duko.CpuPolicyResidencyAccumulator
import com.locutus5382.heimdall.engine.duko.DukoBatteryPoint
import com.locutus5382.heimdall.engine.duko.DukoConfidence
import com.locutus5382.heimdall.engine.duko.DukoEnergy
import com.locutus5382.heimdall.engine.duko.DukoEventAccumulator
import com.locutus5382.heimdall.engine.duko.DukoEventSample
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoMinuteAccumulator
import com.locutus5382.heimdall.engine.duko.DukoIntelligentAnalysis
import com.locutus5382.heimdall.engine.duko.DukoObservation
import com.locutus5382.heimdall.engine.duko.DukoSessionIntegrity
import com.locutus5382.heimdall.engine.duko.DukoSessionOutcome
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.engine.duko.FrequencyCapEvent
import com.locutus5382.heimdall.engine.duko.FrequencyCapTimeline
import com.locutus5382.heimdall.engine.duko.MeasurementOrigin
import com.locutus5382.heimdall.engine.duko.TimedSignalAccumulator
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.recording.DukoFoundationReport
import com.locutus5382.heimdall.ui.navigation.BifrostTestActivity
import com.locutus5382.heimdall.ui.theme.HeimdallTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M1.8 touch navigation: no game sensors, root, controller or SQLite writes. */
@RunWith(AndroidJUnit4::class)
class DukoM18UiAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<BifrostTestActivity>()

    private fun fixture(): DukoFoundationReport {
        val w = DukoWindow(0L, 120_000L)
        val minutes = DukoMinuteAccumulator(w)
        val loadTrace = TimedSignalAccumulator("CPU LOAD", "%", w)
        val residency = CpuPolicyResidencyAccumulator(w,
            mapOf("policy0" to setOf(0, 1)))
        for (t in 0L..120_000L step 2_000L) {
            minutes.acceptTick(t)
            loadTrace.accept(t, 54.0)
            minutes.acceptObservation(DukoObservation(
                elapsedMs = t, metric = DukoMetric.CPU_LOAD, value = 54.0,
                sourceId = "/proc/stat", origin = MeasurementOrigin.VALIDATED_SYSFS
            ))
            residency.accept(t, mapOf("policy0" to if (t < 60_000L) 1400L else 1800L))
        }
        val cap = FrequencyCapTimeline(w, emptyList(),
            listOf(FrequencyCapEvent("policy0", 2_000L, true, 1800L, 2400L)))
        val minuteReport = minutes.result()
        val events = DukoEventAccumulator(w).result(cap)
        val integrity = DukoSessionIntegrity(DukoSessionOutcome.COMPLETED, emptyMap())
        val energy = DukoEnergy.analyze(w, emptyList<DukoBatteryPoint>())
        return DukoFoundationReport(
            sessionId = 818L, window = w, integrity = integrity,
            energy = energy, thermal = emptyMap(),
            capabilitySnapshotAvailable = false,
            confidence = DukoConfidence.assess(integrity, energy),
            minuteAnalysis = minuteReport,
            timelines = mapOf("CPU LOAD" to loadTrace.result()),
            cpuPolicyResidency = residency.result(),
            events = events,
            insights = DukoIntelligentAnalysis.analyze(minuteReport,
                events, cap, emptyMap(), DukoSessionOutcome.COMPLETED),
        )
    }

    @Test fun minuteSelectionPolicyResidencyAndEventTabsAreTouchAccessible() {
        val report = fixture()
        compose.setContent {
            HeimdallTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DukoM18AnalysisPanel(report)
                }
            }
        }
        compose.onNodeWithText("DUKO / ADVANCED ANALYTICS")
            .performScrollTo().assertExists()
        compose.onNodeWithText("MINUTES").performScrollTo().assertExists()
        compose.onNodeWithText("M2", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("MINUTE 2", substring = true)
            .performScrollTo().assertExists()

        compose.onNodeWithText("CPU POLICIES").performScrollTo().performClick()
        compose.onNodeWithText("policy0", substring = true)
            .performScrollTo().assertExists()

        compose.onNodeWithText("EVENTS").performScrollTo().performClick()
        compose.onNodeWithText("POLICY CAP OBSERVED", substring = true)
            .performScrollTo().assertExists()
        compose.onNodeWithText("CAPS").performScrollTo().performClick()
        compose.onNodeWithText("POLICY CAP OBSERVED", substring = true)
            .performScrollTo().assertExists()
        compose.onNodeWithText("INSIGHTS").performScrollTo().performClick()
        compose.onNodeWithText("A / WORKLOAD PHASES").performScrollTo().assertExists()
        compose.onNodeWithText("E / SUGGESTED COMPARISON WINDOWS")
            .performScrollTo().assertExists()
        compose.onNodeWithText("F / DUKO FINDINGS").performScrollTo().assertExists()
    }
    @Test fun interactiveBatch3RangeAndLayeredSignalNavigationAreTouchAccessible() {
        val report = fixture()
        val repository = object : SessionScreenDataSource {
            override suspend fun recent(): List<SavedSession> = emptyList()
            override suspend fun details(id: Long): SessionDetail = error("unused")
            override suspend fun exportCsv(id: Long, uri: Uri): Long = 0L
        }
        val model = SessionScreenViewModel(repository)
        compose.setContent {
            HeimdallTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DukoRangeWorkbench(
                        sessionId = report.sessionId,
                        original = report,
                        state = SessionsUiState(),
                        model = model,
                        markers = emptyList(),
                        onExportRange = {},
                    )
                }
            }
        }
        compose.onNodeWithText("G / INTERACTIVE ANALYSIS")
            .performScrollTo().assertExists()
        compose.onNodeWithText("APPLY RANGE")
            .performScrollTo().assertExists()
        compose.onNodeWithText("EXPORT SELECTED ORIGINAL CSV")
            .performScrollTo().assertExists()
        compose.onNodeWithText("G / LAYERED TELEMETRY")
            .performScrollTo().assertExists()
        compose.onNodeWithText("● CPU LOAD:", substring = true)
            .performScrollTo().assertExists()
        compose.onNodeWithText("CLOCKS").performScrollTo().performClick()
        compose.onNodeWithText("This sensor group has no recorded evidence.")
            .performScrollTo().assertExists()
    }

}
