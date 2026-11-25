package com.locutus5382.heimdall.ui.v2

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** New product-layer navigation: no blades, no nested session accordion. */
internal enum class BifrostV2Destination(val label: String, val short: String) {
    LIVE("Live", "LIVE"),
    ANALYZE("Analyze", "DUKO"),
    RUNS("Runs", "RUNS"),
    COMPARE("Compare", "A/B"),
    TOOLS("Tools", "TOOLS")
}

internal object V2Palette {
    val background = Color(0xFF080D16)
    val panel = Color(0xFF111A28)
    val raised = Color(0xFF192539)
    val border = Color(0xFF26364A)
    val text = Color(0xFFF2F5FA)
    val muted = Color(0xFF9DAFC4)
    val cyan = Color(0xFF78D5EB)
    val amber = Color(0xFFF5B85A)
    val coral = Color(0xFFFC7482)
}

@Composable
internal fun BifrostV2Shell(
    content: @Composable (BifrostV2Destination, (BifrostV2Destination) -> Unit) -> Unit,
) {
    var selectedName by rememberSaveable { mutableStateOf(BifrostV2Destination.LIVE.name) }
    val destination = BifrostV2Destination.entries.firstOrNull { it.name == selectedName }
        ?: BifrostV2Destination.LIVE
    val navigate: (BifrostV2Destination) -> Unit = { selectedName = it.name }
    BackHandler(enabled = destination != BifrostV2Destination.LIVE) {
        navigate(BifrostV2Destination.LIVE)
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(V2Palette.background).safeDrawingPadding()
    ) {
        val landscape = maxWidth >= 720.dp
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(104.dp).fillMaxHeight()
                        .background(V2Palette.panel)
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("H", color = V2Palette.cyan, fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(start = 13.dp, bottom = 17.dp))
                    BifrostV2Destination.entries.forEach { item ->
                        V2DestinationChip(item, selected = destination == item,
                            compact = true, onClick = { navigate(item) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    content(destination, navigate)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().background(V2Palette.panel)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("HEIMDALL", color = V2Palette.cyan, fontSize = 17.sp,
                        fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                    Text("  /  OBSERVE", color = V2Palette.muted, fontSize = 10.sp)
                }
                Box(Modifier.weight(1f)) { content(destination, navigate) }
                Row(Modifier.fillMaxWidth().background(V2Palette.panel)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    BifrostV2Destination.entries.forEach { item ->
                        Box(Modifier.weight(1f)) {
                            V2DestinationChip(item, selected = destination == item,
                                compact = false, onClick = { navigate(item) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun V2DestinationChip(
    tab: BifrostV2Destination,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(if (selected) V2Palette.raised else Color.Transparent,
                RoundedCornerShape(11.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = if (compact) 9.dp else 3.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(if (compact) tab.label else tab.short,
            color = if (selected) V2Palette.cyan else V2Palette.muted,
            fontSize = if (compact) 13.sp else 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1)
    }
}
