package com.locutus5382.heimdall.ui

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.recording.HeimdallRecorderService
import com.locutus5382.heimdall.recording.RecorderStatusHub
import com.locutus5382.heimdall.recording.SessionArchive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A remote control, NEVER an independent recorder. A cold process can have
 * an unfinished SQLite row even when its in-memory status begins as Idle.
 * Both the tile label and START behavior account for that state.
 */
class HeimdallTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val archive by lazy { SessionArchive(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { refreshFromDisk() }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { dispatch() } else dispatch()
    }

    private fun dispatch() {
        scope.launch {
            val state = RecorderStatusHub.state.value
            if (state is RecorderStatus.Preparing ||
                state is RecorderStatus.Stopping ||
                state is RecorderStatus.Saving) return@launch

            // If Android recreated only the tile process, an in-memory Idle
            // state cannot authorize replacing a still-unfinished session.
            val pending = try {
                archive.unfinishedSessionId()
            } catch (_: Exception) {
                openDashboard()
                return@launch
            }
            if ((state is RecorderStatus.Idle || state is RecorderStatus.Finished) &&
                pending != null) {
                openDashboard()
                refresh(pending)
                return@launch
            }

            val action = when (state) {
                is RecorderStatus.Recording -> HeimdallRecorderService.STOP
                is RecorderStatus.NeedsAttention -> {
                    if (state.sessionId == null) {
                        openDashboard()
                        return@launch
                    }
                    HeimdallRecorderService.STOP
                }
                RecorderStatus.Idle, is RecorderStatus.Finished ->
                    HeimdallRecorderService.START
                else -> return@launch
            }
            try {
                startForegroundService(
                    Intent(this@HeimdallTileService, HeimdallRecorderService::class.java)
                        .setAction(action)
                )
            } catch (_: Exception) {
                // Vendor/Android FGS restrictions are not a successful START.
                openDashboard()
                return@launch
            }
            refresh(pending)
        }
    }

    private suspend fun refreshFromDisk() {
        val pending = try { archive.unfinishedSessionId() }
        catch (_: Exception) { null }
        refresh(pending)
    }

    private fun refresh(pending: Long?) {
        val tile = qsTile ?: return
        val status = RecorderStatusHub.state.value
        tile.label = "Heimdall"
        // The tile is never shown active merely because a crashed session
        // exists. "Active" means a live engine is sampling now.
        tile.state = if (status is RecorderStatus.Recording)
            Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (status) {
                is RecorderStatus.Recording -> "REC · tap to save"
                is RecorderStatus.NeedsAttention -> "Session needs review"
                RecorderStatus.Preparing -> "Preparing"
                is RecorderStatus.Stopping, is RecorderStatus.Saving -> "Saving"
                else -> if (pending != null) "Review interrupted session"
                        else "Tap to record"
            }
        }
        tile.updateTile()
    }

    private fun openDashboard() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 12, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun notifyStatusChanged(context: android.content.Context) {
            requestListeningState(context,
                ComponentName(context, HeimdallTileService::class.java))
        }
    }
}
