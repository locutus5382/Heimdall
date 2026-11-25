package com.locutus5382.heimdall.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.engine.duko.DukoComparisonInput
import com.locutus5382.heimdall.engine.duko.DukoContract
import com.locutus5382.heimdall.engine.duko.DukoEvidence
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoMetricAnalysis
import com.locutus5382.heimdall.engine.duko.DukoMultiRun
import com.locutus5382.heimdall.engine.duko.DukoMultiRunReport
import com.locutus5382.heimdall.engine.duko.DukoSessionContext
import com.locutus5382.heimdall.engine.duko.DukoSessionOutcome
import com.locutus5382.heimdall.engine.duko.DukoVisualDocument
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.DukoVisualRenderer
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.ui.navigation.BifrostTestActivity
import com.locutus5382.heimdall.ui.theme.HeimdallTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DukoM21MultiRunUiAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<BifrostTestActivity>()

    private class Fake : MultiRunDataSource {
        var exportCount = 0
        var captured: DukoVisualDocument? = null
        var lastSelections: List<Pair<Long, DukoWindow>> = emptyList()
        override suspend fun recent(): List<SavedSession> = listOf(
            SavedSession(41, 1000, 130000, 61),
            SavedSession(42, 1000, 130000, 61),
            SavedSession(43, 1000, 130000, 61)
        )
        override suspend fun bounds(id: Long): DukoWindow =
            DukoWindow(id * 1_000_000L, id * 1_000_000L + 120_000L)
        override suspend fun analyze(
            runs: List<Pair<Long, DukoWindow>>
        ): DukoMultiRunReport {
            lastSelections = runs
            return DukoMultiRun.compare(runs.map { (id, window) ->
                val v = (id - 1) * 0.2
                val metric = DukoMetric.CPU_LOAD
                DukoComparisonInput(
                    sessionId = id,
                    window = window,
                    outcome = DukoSessionOutcome.COMPLETED,
                    context = DukoSessionContext(game = "Hades", emulator = "Eden",
                        graphicsDriver = "Turnip", fanProfile = "Sport"),
                    metrics = mapOf(metric to DukoMetricAnalysis(
                        metric = metric,
                        window = window,
                        observedRows = 61,
                        validSamples = 61,
                        invalidOrMissingRows = 0,
                        outOfOrderRows = 0,
                        sourceTransitions = 0,
                        coveredMs = window.durationMs,
                        longestGapMs = DukoContract.DEFAULT_INTERVAL_MS,
                        coverage = 1.0, minimum = v, maximum = v,
                        p5 = v, median = v, p95 = v, p99 = v,
                        sampleMean = v, timeWeightedMean = v,
                        sampleStandardDeviation = 0.0,
                        evidence = DukoEvidence.SUPPORTED,
                        warnings = emptyList()
                    ))
                )
            })
        }
        override suspend fun writeVisual(
            destination: Uri,
            document: DukoVisualDocument,
            format: DukoVisualRenderer.Format,
        ) {
            captured = document
            exportCount++
        }
    }

    @Test fun selectionBaselinePromotionAnalysisAndFrozenExportWorkOnTouch() {
        val repo = Fake()
        val model = DukoMultiRunViewModel(repo)
        compose.setContent {
            HeimdallTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DukoM21MultiRunPanel(model)
                }
            }
        }
        compose.runOnIdle { model.refresh(0L) }
        compose.waitUntil(5_000L) { model.ui.value.sessions.size == 3 }
        compose.onAllNodesWithText("ADD").onFirst().performScrollTo().performClick()
        compose.onAllNodesWithText("ADD").onFirst().performScrollTo().performClick()
        assertEquals(listOf(41L, 42L), model.ui.value.selected)
        compose.onNodeWithText("SET A").performScrollTo().performClick()
        assertEquals(42L, model.ui.value.selected.first())
        compose.onAllNodesWithText("ADD").onFirst().performScrollTo().performClick()
        assertEquals(3, model.ui.value.selected.size)
        compose.onNodeWithText("ANALYZE 3 RUNS").performScrollTo().performClick()
        compose.waitUntil(5_000L) { model.ui.value.analyzed != null }
        assertEquals(3, model.ui.value.analyzed!!.sessionCount)
        assertEquals(42L, repo.lastSelections.first().first)
        compose.onNodeWithText("J / DUKO COMPARISON REPORT")
            .performScrollTo().assertExists()
        compose.onNodeWithText("SAVE PDF").performScrollTo().assertExists()
        compose.onNodeWithText("SAVE PNG").performScrollTo().assertExists()
        compose.runOnIdle {
            assertNotNull(model.prepareVisual(DukoVisualRenderer.Format.PDF))
            model.toggle(42L)
            assertEquals(3, model.ui.value.selected.size)
            model.onVisualResult(DukoVisualRenderer.Format.PDF, null)
        }
        assertEquals(0, repo.exportCount)
        compose.runOnIdle {
            assertNotNull(model.prepareVisual(DukoVisualRenderer.Format.PNG))
            model.onVisualResult(DukoVisualRenderer.Format.PNG,
                Uri.parse("content://heimdall.test/report"))
        }
        compose.waitUntil(5_000L) { !model.ui.value.writing &&
            model.ui.value.exportStatus?.contains("saved locally") == true }
        assertEquals(1, repo.exportCount)
        assertNotNull(repo.captured)
    }
}
