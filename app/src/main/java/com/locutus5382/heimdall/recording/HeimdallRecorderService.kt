package com.locutus5382.heimdall.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.locutus5382.heimdall.R
import com.locutus5382.heimdall.engine.NativeRecorderEngine
import com.locutus5382.heimdall.engine.RecorderStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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

    override fun onCreate() {
        super.onCreate()
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
        scope.launch { engine.status.collect { RecorderStatusHub.publish(it) } }
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
            val success = when (action) {
                START -> engine.start()
                STOP -> engine.stop()
                else -> false
            }
            val state = engine.status.value
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (state is RecorderStatus.Finished) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            } else {
                manager.notify(NOTIFICATION_ID,
                    notification(if (success) "Recording" else statusMessage(state)))
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
        // If the OS destroys the Service while the process survives, do not
        // leave a writer running without its foreground owner. A killed process
        // naturally preserves the on-disk unfinished row for recovery review.
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(3_000L) { engine.shutdownForServiceDeath() }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
