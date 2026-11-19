package com.locutus5382.heimdall

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.recording.RuntimeDiagnostics
import com.locutus5382.heimdall.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fails if diagnostics crash or try to mutate storage in the UI path. */
@RunWith(AndroidJUnit4::class)
class DeviceDiagnosticsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test fun capabilitySnapshotIsReadableAndUserCanInspectFromDashboard() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = runBlocking { RuntimeDiagnostics(app).capture() }
        val report = snapshot.asText()
        assertTrue(report.contains("HEIMDALL / LOCAL DIAGNOSTICS"))
        assertTrue(report.contains("Android:"))
        assertTrue(report.contains("CPU policies discovered:"))
        assertTrue(report.contains("Overlay permission:"))
        assertTrue(report.contains("No FPS/root/network"))

        compose.onNodeWithText("RUN DIAGNOSTICS")
            .performScrollTo().performClick()
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("CPU policies discovered")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("EXPORT TXT").assertExists()
    }
}
