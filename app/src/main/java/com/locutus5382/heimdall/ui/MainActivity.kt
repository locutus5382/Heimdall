package com.locutus5382.heimdall.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.recording.HeimdallRecorderService
import com.locutus5382.heimdall.recording.RecorderStatusHub
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

private val Night = Color(0xFF090F1E)
private val SurfaceSlate = Color(0xFF121E32)
private val Line = Color(0xFF26364F)
private val Cobalt = Color(0xFF2855C5)
private val Marigold = Color(0xFFF2A900)
private val Ice = Color(0xFFEAF1FF)
private val Muted = Color(0xFF9EADC2)

class MainActivity : ComponentActivity() {
    private val overlayGranted = MutableStateFlow(false)

    override fun onResume() {
        super.onResume()
        overlayGranted.value = Settings.canDrawOverlays(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inspect the original durable session as soon as the dashboard opens.
        // A normal Service start is valid while this Activity is visible; this
        // does not start recording or require a foreground notification.
        val restoreProblem: String? = try {
            startService(Intent(this, HeimdallRecorderService::class.java)
                .setAction(HeimdallRecorderService.INSPECT))
            null
        } catch (e: Exception) {
            "Could not inspect recorder status: " + e.javaClass.simpleName
        }
        setContent {
            val state by RecorderStatusHub.state.collectAsState()
            var permissionProblem by remember { mutableStateOf(restoreProblem) }
            val hudPrefs = remember {
                getSharedPreferences("heimdall_ui_v1", MODE_PRIVATE)
            }
            var hudEnabled by remember {
                mutableStateOf(hudPrefs.getBoolean("hud_enabled", false))
            }
            val overlayPermission by overlayGranted.collectAsState()
            val toggleHud = {
                val next = !hudEnabled
                // User opted in. If Android still needs overlay permission,
                // the preference is saved and the dashboard shows it as
                // pending instead of incorrectly claiming an active window.
                hudPrefs.edit().putBoolean("hud_enabled", next).apply()
                hudEnabled = next
                if (next && !Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                }
            }
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) requestService(HeimdallRecorderService.START) {
                    permissionProblem = it
                } else permissionProblem =
                    "Notification permission is required for a visible recording service."
            }
            val start = {
                permissionProblem = null
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else requestService(HeimdallRecorderService.START) {
                    permissionProblem = it
                }
            }
            val stop = {
                permissionProblem = null
                requestService(HeimdallRecorderService.STOP) {
                    permissionProblem = it
                }
            }
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Cobalt, secondary = Marigold,
                background = Night, surface = SurfaceSlate,
                onPrimary = Ice, onSurface = Ice)) {
                HeimdallDashboard(
                    state = state, issue = permissionProblem,
                    onStart = start, onStop = stop,
                    hudEnabled = hudEnabled,
                    hudPermissionGranted = overlayPermission,
                    onToggleHud = toggleHud)
            }
        }
    }

    private fun requestService(action: String, failed: (String) -> Unit) {
        try {
            startForegroundService(Intent(this, HeimdallRecorderService::class.java)
                .setAction(action))
        } catch (e: Exception) {
            failed("Recorder request refused by Android: " + e.javaClass.simpleName)
        }
    }
}

@Composable
private fun HeimdallDashboard(
    state: RecorderStatus,
    issue: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    hudEnabled: Boolean,
    hudPermissionGranted: Boolean,
    onToggleHud: () -> Unit,
) {
    val recording = state as? RecorderStatus.Recording
    val attention = state as? RecorderStatus.NeedsAttention
    val session = when (state) {
        is RecorderStatus.Recording -> state.sessionId
        is RecorderStatus.Stopping -> state.sessionId
        is RecorderStatus.Saving -> state.sessionId
        is RecorderStatus.Finished -> state.sessionId
        is RecorderStatus.NeedsAttention -> state.sessionId
        else -> null
    }
    val title = when (state) {
        RecorderStatus.Idle -> "Ready to observe"
        RecorderStatus.Preparing -> "Preparing recorder"
        is RecorderStatus.Recording -> "Recording telemetry"
        is RecorderStatus.Stopping -> "Securing STOP"
        is RecorderStatus.Saving -> "Finalizing session"
        is RecorderStatus.Finished -> "Session saved"
        is RecorderStatus.NeedsAttention -> "Review required"
    }
    val subtitle = when (state) {
        is RecorderStatus.NeedsAttention -> state.code
        is RecorderStatus.Finished -> "${state.samples} samples saved locally"
        is RecorderStatus.Recording -> "${state.persistedSamples} samples on disk"
        else -> "Offline · Read-only · No root"
    }
    val canStart = state is RecorderStatus.Idle ||
        state is RecorderStatus.Finished
    val canStop = state is RecorderStatus.Recording ||
        state is RecorderStatus.Stopping ||
        state is RecorderStatus.Saving ||
        (attention?.sessionId ?: 0L) > 0L

    Surface(color = Night, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 25.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(12.dp).background(Marigold, CircleShape))
                Text("HEIMDALL", fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp, color = Ice)
            }
            Text("THE HANDHELD OBSERVATORY", fontSize = 12.sp,
                color = Marigold, letterSpacing = 2.sp,
                fontWeight = FontWeight.SemiBold)
            Text(
                "We analyze how the handheld behaves, not how many frames the game renders.",
                color = Muted, fontSize = 14.sp)
            StatusPanel(title, subtitle, recording != null,
                session, issue)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onStart, enabled = canStart,
                    colors = ButtonDefaults.buttonColors(containerColor = Cobalt),
                    modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(14.dp)) {
                    Text("START", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onStop, enabled = canStop,
                    modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(14.dp)) {
                    Text(if (attention != null) "RETRY STOP" else "STOP & SAVE",
                        color = if (canStop) Marigold else Muted,
                        fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(
                onClick = onToggleHud,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    when {
                        hudEnabled && hudPermissionGranted ->
                            "IN-GAME HUD: ON · TAP TO DISABLE"
                        hudEnabled -> "HUD ENABLE PENDING ANDROID PERMISSION · TAP TO CANCEL"
                        else -> "ENABLE IN-GAME HUD"
                    },
                    color = Marigold
                )
            }
            Text("Quick Settings: add Heimdall Record in Android's tile editor.",
                color = Muted, fontSize = 12.sp)
            Text("LIVE TELEMETRY", fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = Muted,
                letterSpacing = 1.5.sp)
            val latest = recording?.last
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("CPU LOAD", latest?.cpuLoadPercent?.let {
                    String.format(Locale.US, "%.0f%%", it)
                } ?: "—", Modifier.weight(1f))
                MetricCard("BATTERY", latest?.batteryLevelPercent?.let {
                    "$it%"
                } ?: "—", Modifier.weight(1f))
                MetricCard("GPU CLOCK", latest?.gpuClockMHz?.let {
                    "$it MHz"
                } ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("BAT. TEMP", latest?.batteryTemperatureC?.let {
                    String.format(Locale.US, "%.1f°C", it)
                } ?: "—", Modifier.weight(1f))
                MetricCard("FREE RAM", latest?.availableMemoryMiB?.let {
                    "$it MiB"
                } ?: "—", Modifier.weight(1f))
                MetricCard("CPU CLOCK", latest?.cpuClockMHz?.let {
                    "$it MHz"
                } ?: "—", Modifier.weight(1f))
            }
            SessionHistoryPanel(
                refreshKey = (state as? RecorderStatus.Finished)?.sessionId ?: 0L
            )
            Text("HEIMDALL · STABILITY CANDIDATE 0.3", color = Marigold, fontSize = 12.sp,
                fontWeight = FontWeight.Bold)
            Text("One native recorder. Transactional session storage, " +
                "percentile summaries, local CSV export, Quick Settings and " +
                "a draggable in-game HUD. Advanced DUKO analytics remain upcoming.",
                color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StatusPanel(
    title: String, subtitle: String, active: Boolean,
    session: Long?, issue: String?,
) {
    Column(Modifier.fillMaxWidth()
        .background(SurfaceSlate, RoundedCornerShape(22.dp))
        .border(1.dp, Line, RoundedCornerShape(22.dp))
        .padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(9.dp).background(
                if (active) Marigold else Muted, CircleShape))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = Ice)
        }
        Text(subtitle, color = if (active) Marigold else Muted)
        if (session != null) {
            Text("ORIGINAL SESSION  #$session",
                color = Muted, fontSize = 12.sp)
        }
        if (issue != null) Text(issue, color = Marigold, fontSize = 13.sp)
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier
        .background(SurfaceSlate, RoundedCornerShape(15.dp))
        .border(1.dp, Line, RoundedCornerShape(15.dp))
        .padding(horizontal = 12.dp, vertical = 17.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, color = Muted, fontSize = 10.sp,
            fontWeight = FontWeight.Bold)
        Text(value, color = Ice, fontSize = 17.sp,
            fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}
