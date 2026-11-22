package com.locutus5382.heimdall.ui.dashboard

import android.os.SystemClock
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.ui.navigation.BifrostTestActivity
import com.locutus5382.heimdall.ui.theme.HeimdallTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Acceptance: no duplicated recorder UI; every visible control calls its real slot. */
@RunWith(AndroidJUnit4::class)
class BifrostDashboardAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<BifrostTestActivity>()

    @Test fun idleDashboardShowsRecorderAndReachableSettings() {
        var started = 0
        var setup = 0
        var studio = 0
        var sessions = 0
        var diagnostics = 0
        compose.setContent {
            HeimdallTheme {
                BifrostDashboard(
                    status = RecorderStatus.Idle,
                    issue = null,
                    hudEnabled = false,
                    overlayPermission = false,
                    onStart = { started++ },
                    onStop = {},
                    onToggleHud = {},
                    onSetup = { setup++ },
                    onHudStudio = { studio++ },
                    onSessions = { sessions++ },
                    onDiagnostics = { diagnostics++ },
                )
            }
        }
        compose.onNodeWithText("STOP & SAVE").assertIsNotEnabled()
        compose.onNodeWithText("START").performClick()
        assertEquals(1, started)
        compose.onNodeWithText("HUD Studio · layout and placement")
            .performScrollTo().performClick()
        assertEquals(1, studio)
        compose.onNodeWithText("SETUP").performScrollTo().performClick()
        compose.onNodeWithText("SESSIONS").performScrollTo().performClick()
        compose.onNodeWithText("DIAGNOSTICS").performScrollTo().performClick()
        assertEquals(1, setup)
        assertEquals(1, sessions)
        assertEquals(1, diagnostics)
    }

    @Test fun recordingShowsOnlyAvailableRealSensorReadings() {
        val sample = DeviceSample(
            observedAtMs = 1730000000000L,
            observedElapsedMs = SystemClock.elapsedRealtime(),
            cpuLoadPercent = 63.0,
            batteryLevelPercent = 72,
            batteryTemperatureC = null,
            availableMemoryMiB = 2048,
            cpuClockMHz = null,
            gpuClockMHz = 675,
        )
        compose.setContent {
            HeimdallTheme {
                BifrostDashboard(
                    status = RecorderStatus.Recording(
                        sessionId = 41L,
                        persistedSamples = 7L,
                        last = sample,
                        startedElapsedMs = SystemClock.elapsedRealtime() - 5000L
                    ),
                    issue = null,
                    hudEnabled = true,
                    overlayPermission = false,
                    onStart = {},
                    onStop = {},
                    onToggleHud = {},
                    onSetup = {},
                    onHudStudio = {},
                    onSessions = {},
                    onDiagnostics = {}
                )
            }
        }
        compose.onNodeWithText("Recording session").assertExists()
        compose.onNodeWithText("63%").assertExists()
        compose.onNodeWithText("675 MHz").assertExists()
        compose.onNodeWithText("72%").assertExists()
        compose.onNodeWithText("Permission required · not yet active")
            .performScrollTo().assertExists()
        compose.onNodeWithText("STOP & SAVE").performClick()
        compose.onNodeWithText("START").assertIsNotEnabled()
    }
}
