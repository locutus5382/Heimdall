package com.locutus5382.heimdall

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.recording.AndroidTelemetrySource
import com.locutus5382.heimdall.recording.SensorCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** No test depends on an Odin-specific sysfs layout or requires root. */
@RunWith(AndroidJUnit4::class)
class SensorDiscoveryTest {
    @Test fun sourceConstructionDefersSysfsDiscoveryToIoRead() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val calls = AtomicInteger(0)
        val probedOnMain = AtomicBoolean(true)
        val source = AndroidTelemetrySource(app) { ctx ->
            calls.incrementAndGet()
            probedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
            SensorCatalog.get(ctx)
        }
        assertEquals("Creating the foreground service must not probe sysfs", 0, calls.get())
        source.read()
        assertEquals(1, calls.get())
        assertFalse("Sysfs discovery must run off main thread", probedOnMain.get())
        source.read()
        // Each read consults the volatile cached catalog reference so a
        // per-session refresh cannot leave the sampler pinned to old sysfs.
        // SensorCatalog.get() itself does not re-enumerate paths each tick.
        assertEquals("Each tick observes catalog refresh", 2, calls.get())
    }

    @Test fun thermalSelectionIsStableAndRetainsHighIndexedCpuGpuZones() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val root = File(app.cacheDir, "heimdall-thermal-test-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        try {
            val zones = (0 until 40).map { index ->
                File(root, "thermal_zone${index}").apply {
                    assertTrue(mkdirs())
                    File(this, "type").writeText(when (index) {
                        37 -> "gpu_therm"
                        38 -> "cpu_big_therm"
                        else -> "skin"
                    })
                    File(this, "temp").writeText("42000")
                }
            }
            val first = SensorCatalog.discoverThermalNodes(zones)
            val reversed = SensorCatalog.discoverThermalNodes(zones.reversed())
            assertEquals(first.map { it.file.parentFile?.name },
                reversed.map { it.file.parentFile?.name })
            assertEquals(24, first.size)
            assertTrue(first.any { it.type == "gpu_therm" })
            assertTrue(first.any { it.type == "cpu_big_therm" })
            assertEquals("thermal_zone37", first[0].file.parentFile?.name)
            assertEquals("thermal_zone38", first[1].file.parentFile?.name)
        } finally {
            root.deleteRecursively()
        }
    }
}
