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

/** Quick Settings is only a remote control for the one native Service.
 *  It never owns an engine, sampler, overlay or SQLite writer.
 */
class HeimdallTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { dispatch() } else dispatch()
    }

    private fun dispatch() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            openDashboard()
            return
        }
        val status = RecorderStatusHub.state.value
        val action = when (status) {
            is RecorderStatus.Recording -> HeimdallRecorderService.STOP
            is RecorderStatus.NeedsAttention -> {
                if (status.sessionId == null) {
                    openDashboard()
                    return
                }
                HeimdallRecorderService.STOP
            }
            is RecorderStatus.Idle, is RecorderStatus.Finished ->
                HeimdallRecorderService.START
            else -> return // Never toggle while START / STOP is in flight.
        }
        try {
            startForegroundService(Intent(this, HeimdallRecorderService::class.java)
                .setAction(action))
        } catch (_: Exception) {
            // Android background launch restrictions can differ by ROM.
            // Open the UI rather than claiming that recording started.
            openDashboard()
        }
        refresh()
    }

    private fun openDashboard() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 12, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val status = RecorderStatusHub.state.value
        tile.label = "Heimdall"
        tile.state = when (status) {
            is RecorderStatus.Recording, is RecorderStatus.NeedsAttention ->
                Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (status) {
                is RecorderStatus.Recording -> "Recording · tap STOP"
                is RecorderStatus.NeedsAttention -> "Needs review · tap STOP"
                RecorderStatus.Preparing -> "Starting"
                is RecorderStatus.Stopping, is RecorderStatus.Saving -> "Saving"
                else -> "Tap to record"
            }
        }
        tile.updateTile()
    }

    companion object {
        fun notifyStatusChanged(context: android.content.Context) {
            requestListeningState(context, ComponentName(context, HeimdallTileService::class.java))
        }
    }
}
