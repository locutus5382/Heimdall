package com.locutus5382.heimdall.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for Claude A2: a destination's saveable state survives leaving
 * and returning to the single Bifrost content slot.
 */
@RunWith(AndroidJUnit4::class)
class BifrostDestinationStateAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<BifrostTestActivity>()

    @Test fun compareSelectionSurvivesSwitchingTabs() {
        compose.setContent {
            HeimdallShell(
                dashboard = { _, _ -> Text("DASHBOARD CONTENT") },
                sessions = { Text("SESSIONS CONTENT") },
                compare = { TestCompareEditor() },
                bladeContent = { _, _, _ -> Text("BLADE CONTENT") },
            )
        }
        compose.onNodeWithText("Dashboard").assertIsSelected()
        compose.onNodeWithText("Compare").assertIsNotSelected()
        compose.onNodeWithText("Compare").performClick()
        compose.onNodeWithText("Compare").assertIsSelected()
        compose.onNodeWithText("SELECT RUN A").performClick()
        compose.onNodeWithText("A=41").assertExists()
        compose.onNodeWithText("Dashboard").performClick()
        compose.onNodeWithText("Compare").performClick()
        compose.onNodeWithText("A=41").assertExists()
    }

    @Composable
    private fun TestCompareEditor() {
        var selected by rememberSaveable { mutableStateOf("A=NONE") }
        Column {
            Text(selected)
            Button(onClick = { selected = "A=41" }) { Text("SELECT RUN A") }
        }
    }
}
