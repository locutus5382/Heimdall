package com.locutus5382.heimdall.engine

import org.junit.Assert.*
import org.junit.Test

class HudRenderStateTest {
    private val sample = DeviceSample(
        observedAtMs = 1234L,
        cpuLoadPercent = 42.0,
        batteryLevelPercent = 72,
        batteryTemperatureC = 39.5,
        availableMemoryMiB = 1700L,
        cpuClockMHz = 2050L,
        gpuClockMHz = 680L,
    )

    @Test fun allFourLayoutsApplyTheSameMetricLimit() {
        for (layout in HudLayout.entries) {
            val state = HudRenderState.recording(
                17L, 75000L, sample,
                HudProfile(layout = layout, metrics = HudMetric.entries.toSet()),
                emptyList()
            )
            assertEquals("01:15", state.elapsedLabel)
            assertEquals("● REC 01:15", state.recordingLabel)
            assertEquals(layout.metricLimit(), state.readouts.size)
        }
    }

    @Test fun unknownHardwareIsNotReportedAsZeroOrFabricatedPower() {
        val state = HudRenderState.recording(
            2L, 0L,
            sample.copy(cpuLoadPercent = Double.NaN, gpuClockMHz = null),
            HudProfile(), emptyList()
        )
        assertEquals("—", state.readouts.first {
            it.metric == HudMetric.CPU_LOAD
        }.value)
        assertEquals("—", state.readouts.first {
            it.metric == HudMetric.GPU_CLOCK
        }.value)
        assertNull(state.readouts.first {
            it.metric == HudMetric.GPU_CLOCK
        }.normalized)
        assertFalse(state.readouts.any { it.label == "POWER" || it.label == "FPS" })
    }

    @Test fun noCausalThrottlingClaimFromTemperatureOrLoad() {
        val state = HudRenderState.recording(7L, 5500L, sample,
            HudProfile(), emptyList(), ThermalIndicatorState.ELEVATED_TEMP)
        val battery = state.readouts.single { it.metric == HudMetric.BATTERY_TEMP }
        assertTrue(battery.caution)
        assertFalse(battery.alert)
        val sustained = HudRenderState.recording(7L, 16000L, sample,
            HudProfile(), emptyList(),
            ThermalIndicatorState.SUSTAINED_HIGH_BATTERY_TEMP)
        assertTrue(sustained.readouts.single {
            it.metric == HudMetric.BATTERY_TEMP
        }.alert)
        assertFalse(sustained.readouts.any {
            it.value.contains("THROTTL", ignoreCase = true)
        })
    }

    @Test fun sessionTimeIsMonotonicInputNotWallClock() {
        val state = HudRenderState.recording(1L, 3_661_000L, sample,
            HudProfile(), emptyList())
        assertEquals("1:01:01", state.elapsedLabel)
        val negative = HudRenderState.recording(1L, -5000L, sample,
            HudProfile(), emptyList())
        assertEquals("00:00", negative.elapsedLabel)
    }
}
