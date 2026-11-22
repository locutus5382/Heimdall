package com.locutus5382.heimdall

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.recording.HeimdallRecorderService
import com.locutus5382.heimdall.recording.RecorderStatusHub
import com.locutus5382.heimdall.recording.SessionArchive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single, process-owned composition root. It owns adapters, not a second
 * recorder, database or HUD window. The foreground service remains the sole
 * recording engine owner. No network, account, root or device control.
 */
class HeimdallApplication : Application() {
    val graph: HeimdallAppGraph by lazy { HeimdallAppGraph(this) }
}

class HeimdallAppGraph(application: Application) {
    val archive: SessionArchive by lazy { SessionArchive(application) }
    val recorder: RecorderRuntime by lazy { AndroidRecorderRuntime(application) }
    val hudPreferences: HudPreferenceRepository by lazy {
        HudPreferenceRepository(application)
    }
    val testProfiles: TestProfileRepository by lazy {
        TestProfileRepository(application)
    }
}

/** Commands are only dispatched to the one existing foreground service. */
interface RecorderRuntime {
    val status: StateFlow<RecorderStatus>
    /** Null means the Android command was dispatched, NOT that it completed. */
    fun start(): String?
    fun stop(): String?
    fun inspectOnce(): String?
}

private class AndroidRecorderRuntime(context: Context) : RecorderRuntime {
    private val app = context.applicationContext
    private val inspected = AtomicBoolean(false)
    override val status: StateFlow<RecorderStatus> = RecorderStatusHub.state

    override fun inspectOnce(): String? {
        if (!inspected.compareAndSet(false, true)) return null
        return try {
            app.startService(Intent(app, HeimdallRecorderService::class.java)
                .setAction(HeimdallRecorderService.INSPECT))
            null
        } catch (error: Exception) {
            inspected.set(false) // a denied inspection may be retried
            "Recorder inspection unavailable: ${error.javaClass.simpleName}"
        }
    }

    override fun start(): String? = dispatch(HeimdallRecorderService.START)
    override fun stop(): String? = dispatch(HeimdallRecorderService.STOP)

    private fun dispatch(action: String): String? = try {
        app.startForegroundService(
            Intent(app, HeimdallRecorderService::class.java).setAction(action)
        )
        null
    } catch (error: Exception) {
        "Recorder command rejected: ${error.javaClass.simpleName}"
    }
}

/**
 * The dashboard and the Canvas HUD use the SAME local preference.
 * Listener reflects changes from HUD Studio or HUD safety shutdown.
 * Enabling preference does not claim an overlay window is attached.
 */
class HudPreferenceRepository(context: Context) :
    SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefs = context.applicationContext
        .getSharedPreferences("heimdall_ui_v1", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(prefs.getBoolean("hud_enabled", false))
    val enabled: StateFlow<Boolean> = mutable

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("hud_enabled", value).apply()
        mutable.value = value
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "hud_enabled") {
            mutable.value = prefs.getBoolean("hud_enabled", false)
        }
    }
}
