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
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.ui.navigation.BifrostTestActivity
import com.locutus5382.heimdall.ui.theme.HeimdallTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verify Studio uses the real Canvas renderer without starting recording. */
@RunWith(AndroidJUnit4::class)
class BifrostHudStudioAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<BifrostTestActivity>()

    @Test fun rendererPreviewAndPermissionControlsAreReachable() {
        var enabled = 0
        var requested = 0
        compose.setContent {
            HeimdallTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    HudStudioPanel(
                        hudEnabled = false,
                        overlayPermission = false,
                        recorderStatus = RecorderStatus.Idle,
                        onToggleHud = { enabled++ },
                        onRequestOverlayPermission = { requested++ },
                    )
                }
            }
        }
        compose.onNodeWithText("LIVE LAYOUT PREVIEW").assertExists()
        compose.onNodeWithText("Renderer preview · no fabricated sensor values or active recording.")
            .assertExists()
        compose.onNodeWithText("ENABLE HUD").performScrollTo().performClick()
        compose.onNodeWithText("GRANT OVERLAY").performScrollTo().performClick()
        assertEquals(1, enabled)
        assertEquals(1, requested)
        // No Service START or HUD window was dispatched by either callback.
    }
}
