package com.locutus5382.heimdall.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Stable Bifrost tokens. All presentation only; no service or telemetry ownership. */
object BifrostColors {
    val Midnight = Color(0xFF0B1425)
    val Editorial = Color(0xFF111C31)
    val Blade = Color(0xFF132038)
    val Cobalt = Color(0xFF192B49)
    val Signal = Color(0xFF65D5F5)
    val Amber = Color(0xFFF0BD62)
    val Coral = Color(0xFFF16B78)
    val Ice = Color(0xFFF2F6FC)
    val Muted = Color(0xFF94A7C4)
}

private val bifrostScheme = darkColorScheme(
    primary = BifrostColors.Signal,
    onPrimary = BifrostColors.Midnight,
    secondary = BifrostColors.Amber,
    background = BifrostColors.Midnight,
    onBackground = BifrostColors.Ice,
    surface = BifrostColors.Editorial,
    onSurface = BifrostColors.Ice,
    surfaceVariant = BifrostColors.Blade,
    onSurfaceVariant = BifrostColors.Muted,
    error = BifrostColors.Coral,
)

@Composable
fun HeimdallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = bifrostScheme, content = content)
}
