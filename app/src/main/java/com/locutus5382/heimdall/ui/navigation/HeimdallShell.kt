package com.locutus5382.heimdall.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.locutus5382.heimdall.ui.theme.BifrostColors

/**
 * Bifrost's presentation-only navigation frame. Existing recorder and DUKO
 * composables are supplied as slots, never duplicated or reimplemented.
 * Do not attach to MainActivity until real callbacks and permission flow are wired.
 */
@Composable
fun HeimdallShell(
    dashboard: @Composable () -> Unit,
    sessions: @Composable () -> Unit,
    compare: @Composable () -> Unit,
    bladeContent: @Composable (BifrostBlade) -> Unit,
) {
    var nav by remember { mutableStateOf(BifrostNavigation()) }
    BackHandler(enabled = nav.blade != null) {
        nav = nav.apply(BifrostAction.CLOSE_BLADE)
    }
    // Controller shortcuts are deliberately scoped to the focusable top navigation bar.
    // A focused TextField inside a supplied dashboard/session/blade slot is NOT its
    // descendant, so its X/Y/L1/R1/B events never bubble into this handler.
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(BifrostColors.Midnight)
    ) {
        val wide = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(BifrostColors.Editorial)
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val action = controllerAction(event.key.keyCode.toInt(), false)
                            ?: return@onKeyEvent false
                        if (action == BifrostAction.CLOSE_BLADE && nav.blade == null)
                            return@onKeyEvent false
                        nav = nav.apply(action)
                        true
                    }
                    .focusable()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("HEIMDALL", color = BifrostColors.Signal,
                    fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    modifier = Modifier.padding(end = 18.dp))
                BifrostTab.entries.forEach { tab ->
                    val selected = nav.tab == tab
                    Text(
                        tab.name.lowercase().replaceFirstChar { it.uppercase() },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .background(if (selected) BifrostColors.Cobalt else Color.Transparent,
                                RoundedCornerShape(6.dp))
                            .clickable { nav = nav.copy(tab = tab, blade = null) }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        color = if (selected) BifrostColors.Signal else BifrostColors.Muted,
                        fontSize = 13.sp,
                    )
                }
            }
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                when (nav.tab) {
                    BifrostTab.DASHBOARD -> dashboard()
                    BifrostTab.SESSIONS -> sessions()
                    BifrostTab.COMPARE -> compare()
                }
            }
        }
        if (nav.blade != null) {
            Box(Modifier.fillMaxSize()
                .background(BifrostColors.Midnight.copy(alpha = 0.55f))
                .clickable { nav = nav.copy(blade = null) })
        }
        AnimatedVisibility(
            visible = nav.blade != null,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(180)) { it },
            exit = slideOutHorizontally(tween(180)) { it },
        ) {
            Column(
                Modifier.width(if (wide) maxWidth * 0.42f else maxWidth * 0.88f)
                    .fillMaxHeight().background(BifrostColors.Blade)
                    .padding(18.dp)
            ) {
                val active = nav.blade
                if (active != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(active.name.replace('_', ' '), color = BifrostColors.Ice,
                            fontWeight = FontWeight.Bold)
                        Text("CLOSE", color = BifrostColors.Signal,
                            modifier = Modifier.clickable {
                                nav = nav.copy(blade = null)
                            }.padding(12.dp))
                    }
                    bladeContent(active)
                }
            }
        }
    }
}
