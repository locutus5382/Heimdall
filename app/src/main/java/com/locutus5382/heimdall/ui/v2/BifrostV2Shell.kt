package com.locutus5382.heimdall.ui.v2

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
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
    // Charcoal instrument-panel design; Heimdall cyan and amber remain the
    // only prominent accent colors. The references inform composition, not
    // a change to yellow/green branding or excessive glowing panels.
    val background = Color(0xFF0B0C10)
    val rail = Color(0xFF101319)
    val panel = Color(0xFF171B22)
    val raised = Color(0xFF222833)
    val border = Color(0xFF343B47)
    val divider = Color(0xFF282E37)
    val text = Color(0xFFF5F7FA)
    val muted = Color(0xFFA2ADBB)
    val subtle = Color(0xFF788797)
    val cyan = Color(0xFF66D5ED)
    val amber = Color(0xFFF1B262)
    val coral = Color(0xFFFF7382)
    val teal = Color(0xFF55CDB8)

    val surfaceGlow: Brush = Brush.linearGradient(
        listOf(Color(0xFF26313A), Color(0xFF1B2029), panel)
    )
    val quietGlow: Brush = Brush.linearGradient(
        listOf(Color(0xFF26272E), Color(0xFF181D24), Color(0xFF14181D))
    )
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
                    Modifier.width(94.dp).fillMaxHeight()
                        .background(V2Palette.rail)
                        .padding(horizontal = 9.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.width(44.dp).height(44.dp)
                            .background(V2Palette.surfaceGlow, RoundedCornerShape(14.dp))
                            .border(1.dp, V2Palette.border, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("H", color = V2Palette.cyan, fontSize = 24.sp,
                            fontWeight = FontWeight.Black)
                    }
                    Text("HEIMDALL", color = V2Palette.muted, fontSize = 9.sp,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(bottom = 18.dp))
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
            .border(1.dp, if (selected) V2Palette.border else Color.Transparent,
                RoundedCornerShape(11.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = if (compact) 7.dp else 3.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(if (compact) tab.label else tab.short,
            color = if (selected) V2Palette.cyan else V2Palette.muted,
            fontSize = if (compact) 13.sp else 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1)
    }
}
