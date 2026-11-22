package com.locutus5382.heimdall.engine

import org.junit.Assert.*
import org.junit.Test

class HudSafetyAlertEngineTest {
    private fun sample(t: Long, battery: Double? = 36.0,
        thermal: Int? = 0, cpu: Double? = null, gpu: Double? = null
    ) = DeviceSample(
        observedAtMs = 1L, observedElapsedMs = t,
        cpuLoadPercent = cpu, batteryLevelPercent = 80,
        batteryTemperatureC = battery,
        availableMemoryMiB = null, cpuClockMHz = null, gpuClockMHz = null,
        thermalStatus = thermal, gpuLoadPercent = gpu
    )

    @Test fun sustainedBatteryWarningNeedsRealMonotonicSamples() {
        val e = HudSafetyAlertEngine()
        assertEquals(HudAlertCategory.BATTERY_WARM,
            e.observe(1L, sample(1_000L, 42.5)).primary?.category)
        assertEquals(HudAlertCategory.BATTERY_WARM,
            e.observe(1L, sample(1_000L, 42.5)).primary?.category)
        for (t in 3_000L..15_000L step 2_000L) e.observe(1L, sample(t, 42.5))
        assertEquals(HudAlertCategory.BATTERY_WARM, e.observe(1L, sample(15_000L, 42.5)).primary?.category)
        assertEquals(HudAlertCategory.BATTERY_HOT,
            e.observe(1L, sample(17_000L, 42.5)).primary?.category)
    }

    @Test fun aLongSamplingGapCannotFabricateHoldTime() {
        val e = HudSafetyAlertEngine()
        e.observe(1L, sample(1_000L, 43.0))
        assertEquals(HudAlertCategory.BATTERY_WARM,
            e.observe(1L, sample(30_000L, 43.0)).primary?.category)
        assertEquals(HudAlertCategory.BATTERY_WARM,
            e.observe(1L, sample(32_000L, 43.0)).primary?.category)
    }

    @Test fun temperatureRecoveryUsesLowerThresholdAndClearDelay() {
        val e = HudSafetyAlertEngine()
        for (t in 1_000L..17_000L step 2_000L) e.observe(2L, sample(t, 42.5))
        assertEquals(HudAlertCategory.BATTERY_HOT, e.observe(2L, sample(19_000L, 40.5)).primary?.category)
        // Continuous 2-second readings below 40°C for one minute.
        for (t in 21_000L..81_000L step 2_000L) e.observe(2L, sample(t, 39.0))
        assertEquals(HudAlertCategory.BATTERY_WARM,
            e.observe(2L, sample(81_000L, 39.0)).primary?.category)
    }

    @Test fun lostBatterySensorDoesNotMeanNormalTemperature() {
        val e = HudSafetyAlertEngine()
        e.observe(1L, sample(1_000L, 42.0))
        val unknown = e.observe(1L, sample(3_000L, null))
        assertTrue(unknown.batteryTemperatureUnknown)
        assertEquals(HudAlertCategory.BATTERY_SENSOR_LOST, unknown.primary?.category)
        assertEquals(ThermalIndicatorState.NOMINAL, unknown.batteryIndicator)
    }

    @Test fun androidCriticalThermalStatusBeatsWarmBattery() {
        val e = HudSafetyAlertEngine()
        val state = e.observe(1L, sample(1_000L, 39.0, thermal = 4))
        assertEquals(HudAlertSeverity.CRITICAL, state.primary?.severity)
        assertEquals(HudAlertCategory.ANDROID_THERMAL, state.primary?.category)
    }

    @Test fun highGpuLoadIsInformationNotDanger() {
        val e = HudSafetyAlertEngine()
        for (t in 1_000L..121_000L step 2_000L) e.observe(1L,
            sample(t, 36.0, gpu = 97.0))
        assertEquals(HudAlertCategory.GPU_SUSTAINED_LOAD,
            e.observe(1L, sample(123_000L, 36.0, gpu = 97.0)).primary?.category)
        assertEquals(HudAlertSeverity.INFO,
            e.observe(1L, sample(123_000L, 36.0, gpu = 97.0)).primary?.severity)
        assertFalse(e.observe(2L, sample(125_000L, 36.0, gpu = 97.0)).alerts.isNotEmpty())
    }

    @Test fun unavailableOrInvalidGpuLoadNeverStartsWarning() {
        val e = HudSafetyAlertEngine()
        for (t in 1_000L..121_000L step 2_000L) {
            e.observe(1L, sample(t, gpu = if (t == 61_000L) null else 98.0))
        }
        assertTrue(e.observe(1L, sample(123_000L, gpu = Double.NaN)).alerts.isEmpty())
    }

    @Test fun exactSameTickNeverAdvancesDuration() {
        val e = HudSafetyAlertEngine()
        val first = e.observe(1L, sample(1_000L, 42.1))
        repeat(100) { assertEquals(first, e.observe(1L, sample(1_000L, 45.0))) }
        assertEquals(HudAlertCategory.BATTERY_WARM, first.primary?.category)
    }
    @Test fun staleCriticalAlertIsNotShownWithoutFreshSample() {
        val e = HudSafetyAlertEngine()
        val critical = e.observe(7L, sample(10_000L, 44.0, thermal = 4))
        assertEquals(HudAlertSeverity.CRITICAL, critical.primary?.severity)
        assertEquals(critical, e.visibleStateAt(14_000L))
        val stale = e.visibleStateAt(14_001L)
        assertNull(stale.primary)
        assertTrue(stale.batteryTemperatureUnknown)
        // A redraw or missing sample cannot refresh a stale warning.
        e.observe(7L, null)
        assertNull(e.visibleStateAt(15_000L).primary)
        // A real later observation can restore a qualified alert.
        assertEquals(HudAlertCategory.ANDROID_THERMAL,
            e.observe(7L, sample(16_000L, 44.0, thermal = 4)).primary?.category)
    }

    @Test fun clockDiscontinuityDoesNotExposeOldAlert() {
        val e = HudSafetyAlertEngine()
        e.observe(8L, sample(10_000L, 36.0, thermal = 3))
        assertNull(e.visibleStateAt(9_999L).primary)
        assertNull(e.visibleStateAt(20_000L).primary)
        assertNull(e.visibleStateAt(20_000L).primary)
        assertTrue(e.visibleStateAt(20_000L).batteryTemperatureUnknown)
        assertNull(e.observe(9L, null).primary)
        assertNull(e.visibleStateAt(20_000L).primary)
    }

}
