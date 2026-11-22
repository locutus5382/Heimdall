package com.locutus5382.heimdall.ui.navigation

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for PR #53 P1: the gamepad handler must not be an ancestor of
 * editable content. The editor receives gamepad input without changing tabs
 * or opening Setup/HUD blades. A focused navigation header still owns shortcuts.
 * Launch an isolated, registered, empty test Activity. ComposeTestRule owns its
 * only composition, avoiding MainActivity's existing composition and ABI mismatch.
 */
@RunWith(AndroidJUnit4::class)
class BifrostControllerFocusAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<BifrostTestActivity>()

    @Test fun editorFocusDoesNotTriggerGlobalGamepadShortcuts() {
        var edited by mutableStateOf("Turnip")
        compose.setContent {
            HeimdallShell(
                dashboard = {
                    Column {
                        Text("Dashboard content", Modifier.testTag("dashboard-content"))
                        BasicTextField(
                            value = edited,
                            onValueChange = { edited = it },
                            modifier = Modifier.testTag("driver-editor"),
                        )
                    }
                },
                sessions = { Text("Sessions content", Modifier.testTag("sessions-content")) },
                compare = { Text("Compare content", Modifier.testTag("compare-content")) },
                bladeContent = { Text("Blade content", Modifier.testTag("blade-content")) },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("driver-editor").performClick()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf(
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_B,
        ).forEach { code ->
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code))
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, code))
            compose.waitForIdle()
            compose.onNodeWithTag("dashboard-content").assertExists()
            compose.onNodeWithTag("blade-content").assertDoesNotExist()
        }
        assertEquals("Turnip", edited)
    }

    @Test fun reducerRespectsTextEditingAndNavigationCycles() {
        val keys = listOf(
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK,
        )
        keys.forEach { assertNull(controllerAction(it, isTextEditing = true)) }
        var state = BifrostNavigation()
        state = state.apply(controllerAction(KeyEvent.KEYCODE_BUTTON_R1, false)!!)
        assertEquals(BifrostTab.SESSIONS, state.tab)
        state = state.apply(controllerAction(KeyEvent.KEYCODE_BUTTON_R1, false)!!)
        assertEquals(BifrostTab.COMPARE, state.tab)
        state = state.apply(controllerAction(KeyEvent.KEYCODE_BUTTON_X, false)!!)
        assertEquals(BifrostBlade.RUN_PICKER, state.blade)
        state = state.apply(controllerAction(KeyEvent.KEYCODE_BUTTON_B, false)!!)
        assertNull(state.blade)
        state = state.apply(controllerAction(KeyEvent.KEYCODE_BUTTON_L1, false)!!)
        assertEquals(BifrostTab.SESSIONS, state.tab)
        state = state.apply(controllerAction(KeyEvent.KEYCODE_BUTTON_Y, false)!!)
        assertEquals(BifrostBlade.HUD_STUDIO, state.blade)
        state = state.apply(controllerAction(KeyEvent.KEYCODE_BACK, false)!!)
        assertNull(state.blade)
        assertTrue(state.tab == BifrostTab.SESSIONS)
    }
}
