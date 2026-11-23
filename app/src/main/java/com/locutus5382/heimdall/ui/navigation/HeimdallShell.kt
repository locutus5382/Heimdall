package com.locutus5382.heimdall.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.locutus5382.heimdall.ui.theme.BifrostColors
import kotlinx.coroutines.delay

/** Presentation-only navigation; recorder/DUKO callbacks are owned by MainActivity. */
@Composable
fun HeimdallShell(
    dashboard: @Composable (openBlade: (BifrostBlade) -> Unit,
        openTab: (BifrostTab) -> Unit) -> Unit,
    sessions: @Composable (openTab: (BifrostTab) -> Unit) -> Unit,
    compare: @Composable () -> Unit,
    bladeContent: @Composable (blade: BifrostBlade,
        closeBlade: () -> Unit, openTab: (BifrostTab) -> Unit) -> Unit,
) {
    var tabName by rememberSaveable { mutableStateOf(BifrostTab.DASHBOARD.name) }
    var bladeName by rememberSaveable { mutableStateOf<String?>(null) }
    val screenState = rememberSaveableStateHolder()
    val tab = BifrostTab.entries.firstOrNull { it.name == tabName } ?: BifrostTab.DASHBOARD
    val blade = BifrostBlade.entries.firstOrNull { it.name == bladeName }
    val closeBlade = { bladeName = null }
    val openBlade: (BifrostBlade) -> Unit = { bladeName = it.name }
    val openTab: (BifrostTab) -> Unit = { next ->
        tabName = next.name
        bladeName = null
    }
    val applyAction: (BifrostAction) -> Unit = { action ->
        val next = BifrostNavigation(tab, blade).apply(action)
        tabName = next.tab.name
        bladeName = next.blade?.name
    }
    val navFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    var navFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = blade != null || tab != BifrostTab.DASHBOARD) {
        if (blade != null) closeBlade() else openTab(BifrostTab.DASHBOARD)
    }
    // When a blade opens by touch or controller, place focus on its explicit CLOSE
    // control. No root preview handler is allowed to capture text entry.
    var focusWasInBlade by remember { mutableStateOf(false) }
    LaunchedEffect(blade) {
        if (blade != null) {
            focusWasInBlade = true
            delay(200L)
            closeFocus.requestFocus()
        } else if (focusWasInBlade) {
            focusWasInBlade = false
            navFocus.requestFocus()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()
        .background(BifrostColors.Midnight)
        .safeDrawingPadding().imePadding()) {
        val wide = maxWidth >= 700.dp
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(BifrostColors.Editorial)) {
                Row(Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("HEIMDALL", color = BifrostColors.Signal,
                        fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
                        letterSpacing = 1.6.sp,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    Text("BIFROST  /  LOCAL OBSERVATORY",
                        color = BifrostColors.Muted, fontSize = if(wide) 11.sp else 9.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(navFocus)
                        .onFocusChanged { navFocused = it.isFocused }
                        .border(if (navFocused) 2.dp else 0.dp,
                            if (navFocused) BifrostColors.Signal else Color.Transparent)
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            val action = controllerAction(event.key.keyCode.toInt(), false)
                                ?: return@onKeyEvent false
                            if (action == BifrostAction.CLOSE_BLADE && blade == null)
                                return@onKeyEvent false
                            applyAction(action)
                            true
                        }
                        .focusable()
                        .padding(horizontal = if(wide) 16.dp else 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BifrostTab.entries.forEach { option ->
                        val selected = tab == option
                        Box(
                            Modifier.weight(1f).heightIn(min = 48.dp)
                                .background(if(selected) BifrostColors.Cobalt
                                    else Color.Transparent, RoundedCornerShape(7.dp))
                                .clickable(role = Role.Tab) { openTab(option) }
                                .semantics { this.selected = selected }
                                .padding(horizontal = 8.dp, vertical = 13.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(option.name.lowercase().replaceFirstChar { it.uppercase() },
                                color = if(selected) BifrostColors.Signal else BifrostColors.Muted,
                                fontSize = 13.sp, fontWeight = if(selected) FontWeight.Bold
                                    else FontWeight.Medium)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                // Ordinary rememberSaveable state belongs to each destination,
                // not to whichever composable was last in the single slot.
                screenState.SaveableStateProvider(tab.name) {
                    when (tab) {
                        BifrostTab.DASHBOARD -> dashboard(openBlade, openTab)
                        BifrostTab.SESSIONS -> sessions(openTab)
                        BifrostTab.COMPARE -> compare()
                    }
                }
            }
        }
        if (blade != null) {
            // The modal scrim is touch-only; it must not steal gamepad focus.
            Box(Modifier.fillMaxSize()
                .background(BifrostColors.Midnight.copy(alpha = 0.65f))
                .pointerInput(Unit) { detectTapGestures { closeBlade() } })
        }
        AnimatedVisibility(
            visible = blade != null,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(180)) { it },
            exit = slideOutHorizontally(tween(180)) { it },
        ) {
            Column(
                Modifier.width(if (wide) maxWidth * 0.42f else maxWidth * 0.90f)
                    .fillMaxHeight().background(BifrostColors.Blade)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(blade?.name?.replace('_', ' ') ?: "",
                        color = BifrostColors.Ice, fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    Text("CLOSE", color = BifrostColors.Signal,
                        modifier = Modifier.focusRequester(closeFocus)
                            .heightIn(min = 48.dp).clickable { closeBlade() }
                            .focusable()
                            .padding(horizontal = 12.dp, vertical = 13.dp))
                }
                Column(Modifier.fillMaxWidth().weight(1f)
                    .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val active = blade
                    if (active != null) {
                        bladeContent(active, closeBlade, openTab)
                    }
                }
            }
        }
    }
}
