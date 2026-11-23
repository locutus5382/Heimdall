package com.locutus5382.heimdall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.locutus5382.heimdall.ui.theme.BifrostColors
import androidx.compose.ui.viewinterop.AndroidView
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.recording.HeimdallCanvasView
import com.locutus5382.heimdall.engine.HudDock
import com.locutus5382.heimdall.engine.HudLayout
import com.locutus5382.heimdall.engine.HudMetric
import com.locutus5382.heimdall.engine.HudProfile
import com.locutus5382.heimdall.recording.HudProfileStore
import java.util.Locale

/**
 * Saved, local-only HUD Studio. It changes *presentation*, never opens a
 * second sampler or recording service. The sole HUD controller observes these
 * preferences when the overlay is already visible.
 */
@Composable
fun HudStudioPanel(
    hudEnabled: Boolean = false,
    overlayPermission: Boolean = false,
    recorderStatus: RecorderStatus = RecorderStatus.Idle,
    onToggleHud: () -> Unit = {},
    onRequestOverlayPermission: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { HudProfileStore(context) }
    var config by remember { mutableStateOf(store.read()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var restoreArmed by remember { mutableStateOf(false) }
    // Cycles initiated in the game HUD must also update Studio when the
    // dashboard returns. This listener never starts a service or a sampler.
    DisposableEffect(store) {
        val ui = Handler(Looper.getMainLooper())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("hud_") == true) {
                ui.post { config = store.read() }
            }
        }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            store.prefs.unregisterOnSharedPreferenceChangeListener(listener)
            ui.removeCallbacksAndMessages(null)
        }
    }
    fun save(new: HudProfile) {
        config = new.sanitized()
        store.save(config)
        notice = null
        restoreArmed = false
    }

    val gold = BifrostColors.Amber
    val ink = BifrostColors.Ice
    val muted = BifrostColors.Muted

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(BifrostColors.Editorial, RoundedCornerShape(18.dp))
            .border(1.dp, BifrostColors.Cobalt, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("HUD APPEARANCE  /  CANVAS", color = gold)
        Text(
            "Choose how the in-game observatory looks. Settings update the " +
                "same recording HUD; they never start a second collector.",
            color = muted
        )
        // The preview uses the SAME Canvas renderer as the in-game overlay;
        // it does not create a window or a second recorder. With no artificial
        // DeviceSample, unmeasured readings correctly display as unavailable.
        Text("LIVE LAYOUT PREVIEW", color = gold)
        Text("Renderer preview · no fabricated sensor values or active recording.",
            color = muted)
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth()
                .height(when (config.layout) {
                    HudLayout.SPINE -> 270.dp
                    HudLayout.CARD -> 205.dp
                    HudLayout.RIBBON -> 130.dp
                    HudLayout.PILL -> 125.dp
                })
                .background(BifrostColors.Midnight, RoundedCornerShape(14.dp))
                .padding(6.dp)
        ) {
            AndroidView(
                factory = { target ->
                    HeimdallCanvasView(target, object : HeimdallCanvasView.Actions {
                        override fun stop() = Unit
                        override fun cycle() = Unit
                        override fun lock() = Unit
                        override fun drag(dxPixels: Int, dyPixels: Int) = Unit
                        override fun settle() = Unit
                        override fun drawError() = Unit
                    }).apply { isClickable = false }
                },
                update = { canvas ->
                    canvas.update(profile = config, id = 0L,
                        sample = null, history = emptyList())
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text("OVERLAY / RECORDER LINK", color = muted)
        Text(
            when {
                !hudEnabled -> "HUD disabled. Recording works without an overlay."
                !overlayPermission -> "HUD enabled, but Android overlay access is missing."
                recorderStatus is RecorderStatus.Recording ->
                    "Enabled for this recording; the service owns the actual HUD window."
                else -> "Ready. The service shows the HUD during a recording."
            },
            color = if (hudEnabled && !overlayPermission) gold else ink
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onToggleHud,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(if (hudEnabled) "DISABLE HUD" else "ENABLE HUD")
            }
            if (!overlayPermission) {
                OutlinedButton(onClick = onRequestOverlayPermission,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("GRANT OVERLAY")
                }
            }
        }
        Text("LAYOUT", color = muted)
        HudLayout.entries.chunked(2).forEach { layouts ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                layouts.forEach { layout ->
                FilterChip(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    selected = config.layout == layout,
                    onClick = { save(config.copy(layout = layout)) },
                    label = {
                        Text(
                            when (layout) {
                                HudLayout.PILL -> "PILL"
                                HudLayout.CARD -> "CARD"
                                HudLayout.RIBBON -> "RIBBON"
                                HudLayout.SPINE -> "SPINE"
                            },
                            color = ink
                        )
                    }
                )
                }
            }
        }

        Text(
            "DOCK POSITION",
            color = muted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val choices = if (config.layout == HudLayout.RIBBON)
                listOf(HudDock.TOP, HudDock.BOTTOM)
            else if (config.layout == HudLayout.SPINE)
                listOf(HudDock.LEFT, HudDock.RIGHT)
            else listOf(HudDock.AUTO)
            choices.forEach { choice ->
                FilterChip(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    selected = config.dock == choice ||
                        (config.dock == HudDock.AUTO && choice == choices.first()),
                    onClick = { save(config.copy(dock = choice)) },
                    label = { Text(choice.name, color = ink) }
                )
            }
        }
        Text(
            "Visible metrics: max " + config.layout.metricLimit() +
                " in this layout. Unsupported readings display —.",
            color = muted
        )
        Text(
            String.format(Locale.US, "HUD SCALE   %.2f×", config.scale),
            color = ink
        )
        Slider(
            modifier = Modifier.semantics { contentDescription = "HUD scale" },
            value = config.scale, onValueChange = {
                save(config.copy(scale = it))
            }, valueRange = .85f..1.25f
        )
        Text(
            String.format(Locale.US, "GLASS OPACITY   %d%%",
                (config.opacity * 100).toInt()),
            color = ink
        )
        Slider(
            modifier = Modifier.semantics { contentDescription = "HUD opacity" },
            value = config.opacity, onValueChange = {
                save(config.copy(opacity = it))
            }, valueRange = 0.45f..0.90f
        )
        Text("VISIBLE METRICS", color = muted)
        HudMetric.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { metric ->
                    FilterChip(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        selected = metric in config.metrics,
                        onClick = {
                            val next = config.metrics.toMutableSet()
                            if (metric in next) {
                                next.remove(metric)
                                save(config.copy(metrics = next))
                            } else if (next.size >= config.layout.metricLimit()) {
                                notice = "This layout supports at most ${config.layout.metricLimit()} " +
                                    "metrics. Deselect one before adding another."
                            } else {
                                next.add(metric)
                                save(config.copy(metrics = next))
                            }
                        },
                        label = { Text(metric.title, color = ink) }
                    )
                }
            }
        }
        notice?.let { Text(it, color = gold) }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            onClick = {
                if (restoreArmed) save(HudProfile())
                else {
                    restoreArmed = true
                    notice = "Tap Restore Defaults again to replace the saved HUD configuration."
                }
            }
        ) {
            Text(if (restoreArmed) "CONFIRM RESTORE DEFAULTS"
                else "RESTORE HUD DEFAULTS", color = muted)
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            onClick = { save(config.copy(locked = !config.locked)) }
        ) {
            Text(
                if (config.locked) "UNLOCK HUD TOUCH CONTROLS"
                else "LOCK HUD · ENABLE GAME TOUCH PASS-THROUGH",
                color = gold
            )
        }
        Text(
            "When locked, a separate 48 dp UNLOCK control remains available. " +
                "STOP is also available from Quick Settings and the notification.",
            color = muted
        )
    }
}
