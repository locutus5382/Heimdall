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
import com.locutus5382.heimdall.ui.MainActivity
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
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var foregroundLive = false
    // These guards live on the Service main thread. Register a command BEFORE
    // launch(): another command may arrive while the engine mutex is suspended.
    private val commandTracker = ServiceCommandTracker()
    private val notificationGate = RecorderNotificationGate()
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
            LocalRecordingStore(applicationContext), engineScope,
            overhead = RecorderOverheadHub.monitor,
            processCpuNowMs = { android.os.Process.getElapsedCpuTime() },
        )
        scope.launch {
            var previousPhase: String? = null
            engine.status.collect { state ->
                RecorderStatusHub.publish(state)
                // Bifrost V2 clean-slate: the rejected Canvas HUD is retired.
                // No overlay is attached until the separately reviewed HUD 2.0
                // implementation is ready. Recording and notification still run.
                when (state) {
                    is RecorderStatus.Recording, is RecorderStatus.Stopping,
                    is RecorderStatus.Saving, RecorderStatus.Preparing ->
                        postProgressNotification(state)
                    is RecorderStatus.NeedsAttention -> {
                        detachForegroundForAttention(state)
                        // Never stopSelf() unconditionally: a START or STOP may
                        // already be queued with a later Android startId.
                        stopIfNoCommandsAndTerminal()
                    }
                    else -> Unit // Terminal command owns final cleanup.
                }
                val phase = state.javaClass.simpleName
                if (phase != previousPhase) {
                    previousPhase = phase
                    runCatching {
                        HeimdallTileService.notifyStatusChanged(applicationContext)
                    }
                }
            }
        }
        // The visible Activity and cold Quick Settings tile send a tracked
        // INSPECT command. A second, untracked onCreate inspection could race
        // START and conceal an interrupted session.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // Register synchronously on Main, including INSPECT. An earlier command
        // completing must never stop the Service while a newer one is waiting.
        commandTracker.begin(startId)
        if (action == INSPECT) {
            // Visible Activity may inspect an interrupted session without FGS.
            scope.launch {
                try {
                    engine.inspectExisting()
                } finally {
                    commandTracker.finish()
                    stopIfNoCommandsAndTerminal()
                }
            }
            return START_NOT_STICKY
        }
        if (action != START && action != STOP) {
            // Unknown/OS intent must not stop an ongoing recorder.
            scope.launch {
                commandTracker.finish()
                stopIfNoCommandsAndTerminal()
            }
            return START_NOT_STICKY
        }

        try {
            // Foreground BEFORE any potentially slow SQLite or sensor work.
            startForeground(NOTIFICATION_ID, notification("Preparing recorder"))
            foregroundLive = true
            notificationGate.reset()
        } catch (_: Exception) {
            // The engine failure transition is mutex-serialized; retain the
            // command lease until that transition finishes.
            scope.launch {
                try {
                    engine.reportStartFailure("FOREGROUND_NOT_ALLOWED")
                } finally {
                    commandTracker.finish()
                    stopIfNoCommandsAndTerminal()
                }
            }
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                // Commands serialize inside the engine; the tracker separately
                // protects Service lifetime against overlapping Android intents.
                try {
                    when (action) {
                        START -> engine.start()
                        STOP -> engine.stop()
                        else -> Unit
                    }
                } catch (_: Exception) {
                    engine.reportStartFailure("COMMAND_FAILED")
                }
                when (val state = engine.status.value) {
                    is RecorderStatus.Recording, is RecorderStatus.Stopping,
                    is RecorderStatus.Saving, RecorderStatus.Preparing ->
                        postProgressNotification(state)
                    else -> Unit
                }
            } finally {
                commandTracker.finish()
                stopIfNoCommandsAndTerminal()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * One notification for a meaningful phase or action change, never one
     * per 2-second telemetry tick. Kept on the Service's main thread.
     */
    private fun postProgressNotification(state: RecorderStatus) {
        if (!foregroundLive) return
        val action = if (state is RecorderStatus.Recording) "STOP" else "STOP / Retry"
        val message = statusMessage(state)
        if (notificationGate.shouldPost(message, action)) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification(message, action))
        }
    }

    private fun detachForegroundForAttention(state: RecorderStatus.NeedsAttention) {
        if (!foregroundLive) return
        // DETACH leaves an actionable, non-ongoing attention notification.
        // REMOVE + notify immediately afterward can race and hide the alert.
        foregroundLive = false
        stopForeground(STOP_FOREGROUND_DETACH)
        notificationGate.reset()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(
                "Recording interrupted: ${state.code}",
                actionName = if (state.sessionId == null) null else "RETRY STOP",
                ongoing = false
            ))
    }

    private fun stopIfNoCommandsAndTerminal() {
        val startId = commandTracker.idleStartId() ?: return
        val state = engine.status.value
        if (state is RecorderStatus.Recording ||
            state is RecorderStatus.Preparing ||
            state is RecorderStatus.Stopping ||
            state is RecorderStatus.Saving) return
        // stopSelfResult rejects a stale startId if Android has delivered a
        // newer command. A process remains alive for an ongoing sampler.
        if (stopSelfResult(startId) && foregroundLive) {
            if (state is RecorderStatus.NeedsAttention) {
                detachForegroundForAttention(state)
            } else {
                foregroundLive = false
                notificationGate.reset()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun statusMessage(status: RecorderStatus): String = when (status) {
        is RecorderStatus.Recording -> "Recording session #${status.sessionId}"
        is RecorderStatus.NeedsAttention -> "Attention: ${status.code}"
        is RecorderStatus.Stopping -> "Stopping session #${status.sessionId}"
        is RecorderStatus.Saving -> "Saving session #${status.sessionId}"
        else -> "Preparing recorder"
    }

    private fun notification(
        message: String,
        actionName: String? = "STOP",
        ongoing: Boolean = true
    ): Notification {
        val stop = Intent(this, HeimdallRecorderService::class.java).setAction(STOP)
        val stopIntent = PendingIntent.getService(this, 2, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dashboard = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentTitle("HEIMDALL · Device telemetry")
            .setContentText(message)
            .setContentIntent(dashboard)
            .setSmallIcon(R.drawable.ic_heimdall_notification)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply {
                if (actionName != null) addAction(Notification.Action.Builder(
                    android.R.drawable.ic_media_pause, actionName, stopIntent).build())
            }
            .build()
    }

    override fun onDestroy() {
        // Android calls onDestroy on its main thread. NEVER runBlocking here:
        // the sampler has a Main dispatcher and cancelAndJoin could wait for
        // that same blocked thread (creating a 3s timeout / stuck teardown).
        //
        // Cancelling the owning scope immediately prevents further sampling,
        // while the SQLite unfinished row remains the durable recovery owner.
        // onCreate() can fail before the engine or HUD is constructed.
        // Preserve the original startup error rather than masking it here.
        val state = if (::engine.isInitialized) engine.status.value
            else RecorderStatus.Idle
        scope.cancel()
        engineScope.cancel()
        val interrupted = when (state) {
            is RecorderStatus.Recording -> state.sessionId
            is RecorderStatus.Stopping -> state.sessionId
            is RecorderStatus.Saving -> state.sessionId
            else -> null
        }
        if (interrupted != null) {
            // onDestroy cancels the engine scope immediately and deliberately
            // cannot join it on Main. Freeze diagnostic timings locally; the
            // SQLite row still belongs to the existing interrupted recovery.
            runCatching { RecorderOverheadHub.monitor.end(interrupted) }
            RecorderStatusHub.publish(
                RecorderStatus.NeedsAttention(interrupted, "SERVICE_INTERRUPTED")
            )
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
