package com.locutus5382.heimdall.ui.navigation

import android.view.KeyEvent

/** Only handle gamepad controls when a blade/shell owns focus, not text editing. */
enum class BifrostTab { DASHBOARD, SESSIONS, COMPARE }
enum class BifrostBlade { SETUP, HUD_STUDIO, RUN_PICKER, DIAGNOSTICS }
enum class BifrostAction { PREVIOUS_TAB, NEXT_TAB, CONTEXT_BLADE, HUD_BLADE, CLOSE_BLADE }

internal fun controllerAction(keyCode: Int, isTextEditing: Boolean): BifrostAction? {
    if (isTextEditing) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_L1 -> BifrostAction.PREVIOUS_TAB
        KeyEvent.KEYCODE_BUTTON_R1 -> BifrostAction.NEXT_TAB
        KeyEvent.KEYCODE_BUTTON_X -> BifrostAction.CONTEXT_BLADE
        KeyEvent.KEYCODE_BUTTON_Y -> BifrostAction.HUD_BLADE
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> BifrostAction.CLOSE_BLADE
        else -> null
    }
}

/** Pure reducer for navigation; no synthetic controller events or recorder side effects. */
internal data class BifrostNavigation(
    val tab: BifrostTab = BifrostTab.DASHBOARD,
    val blade: BifrostBlade? = null,
) {
    fun apply(action: BifrostAction): BifrostNavigation = when (action) {
        BifrostAction.PREVIOUS_TAB -> copy(
            tab = BifrostTab.entries[(tab.ordinal + BifrostTab.entries.size - 1) %
                BifrostTab.entries.size], blade = null)
        BifrostAction.NEXT_TAB -> copy(
            tab = BifrostTab.entries[(tab.ordinal + 1) % BifrostTab.entries.size], blade = null)
        BifrostAction.CONTEXT_BLADE -> copy(
            blade = if (tab == BifrostTab.COMPARE) BifrostBlade.RUN_PICKER
                else BifrostBlade.SETUP)
        BifrostAction.HUD_BLADE -> copy(blade = BifrostBlade.HUD_STUDIO)
        BifrostAction.CLOSE_BLADE -> copy(blade = null)
    }
}
