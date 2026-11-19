package com.locutus5382.heimdall.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.locutus5382.heimdall.R
import com.locutus5382.heimdall.ui.HeimdallTileService
import com.locutus5382.heimdall.engine.NativeRecorderEngine
import com.locutus5382.heimdall.engine.RecorderStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * One foreground Service, one native recorder engine, one database writer.
 * No legacy service, hidden second sampler or :monitor IPC status provider.
 */
class HeimdallRecorderService : Service() {
    companion object {
        const val START = "com.locutus5382.heimdall.action.START"
        const val STOP = "com.locutus5382.heimdall.action.STOP"
        const val INSPECT = "com.locutus5382.heimdall.action.INSPECT"
        private const val NOTIFICATION_ID = 1701
        private const val CHANNEL_ID = "heimdall_recorder"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: NativeRecorderEngine
    private lateinit var hud: HudOverlayController

    override fun onCreate() {
        super.onCreate()
        hud = HudOverlayController(applicationContext)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(
                CHANNEL_ID, getString(R.string.recorder_channel),
                NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.recorder_channel_description)
                    setShowBadge(false)
                })
        engine = NativeRecorderEngine(
            AndroidTelemetrySource(applicationContext),
            LocalRecordingStore(applicationContext), scope)
        scope.launch {
            var previousPhase: String? = null
            engine.status.collect { state ->
                RecorderStatusHub.publish(state)
                hud.render(state)
                val phase = state.javaClass.simpleName
                if (phase != previousPhase) {
                    previousPhase = phase
                    runCatching {
                        HeimdallTileService.notifyStatusChanged(applicationContext)
                    }
                }
            }
        }
        scope.launch { engine.inspectExisting() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == INSPECT) {
            // Launched by a visible Activity as a regular (non-FGS) service.
            // Never create a recording or show a fake recording notification.
            scope.launch {
                val restored = engine.inspectExisting()
                if (restored !is RecorderStatus.Recording &&
                    restored !is RecorderStatus.Preparing &&
                    restored !is RecorderStatus.Stopping &&
                    restored !is RecorderStatus.Saving) {
                    // Never stop a newer START command queued after this inspect.
                    stopSelfResult(startId)
                }
            }
            return START_NOT_STICKY
        }
        if (action != START && action != STOP) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        try {
            // Foreground BEFORE any potentially slow SQLite or sensor work.
            startForeground(NOTIFICATION_ID, notification("Preparing recorder"))
        } catch (_: Exception) {
            RecorderStatusHub.publish(
                RecorderStatus.NeedsAttention(null, "FOREGROUND_NOT_ALLOWED"))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            // Commands are serialized by the engine. The Service must not
            // remain foreground when no sampler is active (failed START,
            // finished STOP or unresolved crashed session).
            val success = try {
                when (action) {
                    START -> engine.start()
                    STOP -> engine.stop()
                    else -> false
                }
            } catch (_: Exception) {
                RecorderStatusHub.publish(
                    RecorderStatus.NeedsAttention(null, "COMMAND_FAILED"))
                false
            }
            val state = engine.status.value
            when (state) {
                is RecorderStatus.Recording -> {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIFICATION_ID,
                            notification(statusMessage(state)))
                }
                is RecorderStatus.Finished, RecorderStatus.Idle,
                is RecorderStatus.NeedsAttention -> {
                    // A later command may already be enqueued. Only the last
                    // startId may stop this Service and remove its notification.
                    if (stopSelfResult(startId)) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else if (!success) {
                        // A newer command will set its own status; do not
                        // report a rejected START as a successful recording.
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                            .notify(NOTIFICATION_ID, notification(statusMessage(state)))
                    }
                }
                else -> {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIFICATION_ID, notification(statusMessage(state)))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun statusMessage(status: RecorderStatus): String = when (status) {
        is RecorderStatus.Recording -> "Recording session #${status.sessionId}"
        is RecorderStatus.NeedsAttention -> "Attention: ${status.code}"
        is RecorderStatus.Stopping -> "Stopping session #${status.sessionId}"
        is RecorderStatus.Saving -> "Saving session #${status.sessionId}"
        else -> "Preparing recorder"
    }

    private fun notification(message: String): Notification {
        val stop = Intent(this, HeimdallRecorderService::class.java).setAction(STOP)
        val stopIntent = PendingIntent.getService(this, 2, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("HEIMDALL · Device telemetry")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "STOP / Retry", stopIntent).build())
            .build()
    }

    override fun onDestroy() {
        // Android calls onDestroy on its main thread. NEVER runBlocking here:
        // the sampler has a Main dispatcher and cancelAndJoin could wait for
        // that same blocked thread (creating a 3s timeout / stuck teardown).
        //
        // Cancelling the owning scope immediately prevents further sampling,
        // while the SQLite unfinished row remains the durable recovery owner.
        val state = engine.status.value
        hud.dismiss()
        scope.cancel()
        val interrupted = when (state) {
            is RecorderStatus.Recording -> state.sessionId
            is RecorderStatus.Stopping -> state.sessionId
            is RecorderStatus.Saving -> state.sessionId
            else -> null
        }
        if (interrupted != null) {
            RecorderStatusHub.publish(
                RecorderStatus.NeedsAttention(interrupted, "SERVICE_INTERRUPTED")
            )
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
