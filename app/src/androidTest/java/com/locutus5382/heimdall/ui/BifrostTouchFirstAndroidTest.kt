package com.locutus5382.heimdall.ui

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
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.recording.SessionOverview
import com.locutus5382.heimdall.ui.navigation.BifrostTestActivity
import com.locutus5382.heimdall.ui.theme.HeimdallTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M1.5: touch-first workflows. No controller is required; test values stay
 * in memory, not in the real recorder/database.
 */
@RunWith(AndroidJUnit4::class)
class BifrostTouchFirstAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<BifrostTestActivity>()

    @Test fun sessionDetailsExposeOneReadableSectionAtATime() {
        val source = object : SessionScreenDataSource {
            override suspend fun recent() = listOf(
                SavedSession(41L, 1000L, 11000L, 6L)
            )
            override suspend fun details(id: Long) = SessionDetail(
                SessionOverview(null, null, null, 72, 70, 6L), null, null
            )
            override suspend fun exportCsv(id: Long, uri: android.net.Uri) = 6L
        }
        val model = SessionScreenViewModel(source)
        compose.setContent {
            HeimdallTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SessionHistoryPanel(refreshKey = 0L, model = model)
                }
            }
        }
        compose.waitUntil(5000L) { model.ui.value.history.size == 1 }
        compose.onNodeWithText("ANALYZE").performScrollTo().performClick()
        compose.waitUntil(5000L) { model.ui.value.detail != null }
        compose.onNodeWithText("SUMMARY").performScrollTo().assertExists()
        compose.onNodeWithText("SESSION SUMMARY / RECORDED VALUES")
            .performScrollTo().assertExists()
        compose.onNodeWithText("DUKO ANALYSIS").performScrollTo().performClick()
        compose.onNodeWithText(
            "Duko time-weighted analysis requires at least two " +
                "monotonic samples; older sessions retain their basic summaries."
        ).performScrollTo().assertExists()
        compose.onNodeWithText("EXPORT").performScrollTo().performClick()
        compose.onNodeWithText("EXPORT SESSION #41 CSV").performScrollTo().assertExists()
    }

    @Test fun testSetupUsesTouchPagesInsteadOfLongSingleForm() {
        compose.setContent {
            HeimdallTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DukoTagEditor()
                }
            }
        }
        compose.onNodeWithText("GAME / DRIVER").performScrollTo().assertExists()
        compose.onNodeWithText("DEVICE SETTINGS").performScrollTo().performClick()
        compose.onNodeWithText("DEVICE PERFORMANCE MODE").performScrollTo().assertExists()
        compose.onNodeWithText("NOTES / SAVE").performScrollTo().performClick()
        compose.onNodeWithText("Test notes / cold or warm run / settings")
            .performScrollTo().assertExists()
        compose.onNodeWithText("SAVE TEST CONDITIONS").performScrollTo().assertExists()
    }
}
