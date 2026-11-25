package com.locutus5382.heimdall.ui.v2

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** New theme, independent of the rejected Bifrost V1 palette. */
private val scheme = darkColorScheme(
    primary = V2Palette.cyan,
    onPrimary = V2Palette.background,
    secondary = V2Palette.amber,
    onSecondary = V2Palette.background,
    background = V2Palette.background,
    onBackground = V2Palette.text,
    surface = V2Palette.panel,
    onSurface = V2Palette.text,
    surfaceVariant = V2Palette.raised,
    onSurfaceVariant = V2Palette.muted,
    outline = V2Palette.border,
    error = V2Palette.coral,
    onError = V2Palette.background,
)

@Composable
internal fun BifrostV2Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
