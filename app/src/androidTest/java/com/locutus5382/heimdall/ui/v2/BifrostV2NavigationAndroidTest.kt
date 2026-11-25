package com.locutus5382.heimdall.ui.v2

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test the real V2 Activity; never mount the rejected MainActivity shell.
 * The database is read-only and this test does not press START or mutate runs.
 */
@RunWith(AndroidJUnit4::class)
class BifrostV2NavigationAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recorderControlsAndAnalyticsHaveDirectPaths() {
        compose.onNodeWithText("Overview").assertExists()
        compose.onNodeWithText("START").assertExists()
        compose.onNodeWithText("STOP & SAVE").assertExists()
        compose.onNodeWithContentDescription("Navigate to Analyze").performClick()
        compose.onNodeWithText("Analysis").assertExists()
        compose.onNodeWithText("DUKO / RECORDED EVIDENCE").assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Overview").assertExists()
    }

    @Test fun setupGoesToIndependentToolsRatherThanOldHudStudio() {
        compose.onNodeWithContentDescription("Navigate to Tools").performClick()
        compose.onNodeWithText("LOCAL DEVICE UTILITIES").assertExists()
        compose.onNodeWithText("RUN DIAGNOSTICS").assertExists()
        compose.onNodeWithText("HUD 2.0  /  REBUILD IN PROGRESS").assertExists()
    }
}
