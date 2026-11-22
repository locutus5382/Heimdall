package com.locutus5382.heimdall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
fun HudStudioPanel() {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { HudProfileStore(context) }
    var config by remember { mutableStateOf(store.read()) }
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
    }

    val gold = Color(0xFFF0BD62)
    val ink = Color(0xFFF2F6FC)
    val muted = Color(0xFF94A7C4)

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF111C31), RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFF192B49), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("HUD APPEARANCE  /  CANVAS", color = gold)
        Text(
            "Choose how the in-game observatory looks. Settings update the " +
                "same recording HUD; they never start a second collector.",
            color = muted
        )
        Text("LAYOUT", color = muted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudLayout.entries.forEach { layout ->
                FilterChip(
                    modifier = Modifier.weight(1f),
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
                    modifier = Modifier.weight(1f),
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
            value = config.opacity, onValueChange = {
                save(config.copy(opacity = it))
            }, valueRange = 0.45f..0.90f
        )
        Text("VISIBLE METRICS", color = muted)
        HudMetric.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { metric ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = metric in config.metrics,
                        onClick = {
                            val next = config.metrics.toMutableSet()
                            if (metric in next) next.remove(metric) else next.add(metric)
                            save(config.copy(metrics = next))
                        },
                        label = { Text(metric.title, color = ink) }
                    )
                }
            }
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
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
