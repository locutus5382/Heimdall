package com.locutus5382.heimdall.engine

import org.junit.Assert.*
import org.junit.Test

class HudModelsTest {
    private fun sample(time: Long, load: Double? = 42.0) =
        DeviceSample(time, load, 78, 39.0, 1600, 2000, 680)

    @Test fun threeLayoutsCycleWithoutChangingTheRecorder() {
        assertEquals(HudLayout.CARD, HudLayout.PILL.next())
        assertEquals(HudLayout.RIBBON, HudLayout.CARD.next())
        assertEquals(HudLayout.PILL, HudLayout.RIBBON.next())
        assertEquals(HudLayout.PILL, HudLayout.from("invalid"))
        assertEquals(HudLayout.RIBBON, HudLayout.from("RIBBON"))
    }

    @Test fun profileSanitizesUntrustedSettings() {
        val bad = HudProfile(
            scale = Float.NaN, opacity = Float.POSITIVE_INFINITY, metrics = emptySet()
        ).sanitized()
        assertEquals(1f, bad.scale, .001f)
        assertEquals(.76f, bad.opacity, .001f)
        assertEquals(HudMetric.defaults, bad.metrics)
        assertEquals(.75f, HudProfile(scale = .1f).sanitized().scale, .001f)
        assertEquals(.9f, HudProfile(opacity = 3f).sanitized().opacity, .001f)
        assertEquals(HudMetric.defaults, HudMetric.parse(null))
        assertEquals(setOf(HudMetric.CPU_LOAD), HudMetric.parse("cpu,unknown"))
    }

    @Test fun ringIsBoundedAndDeduplicatesSameTimestamp() {
        val h = HudTelemetryHistory(3)
        h.add(7, sample(100))
        h.add(7, sample(100, 10.0))
        h.add(7, sample(99))
        h.add(7, sample(101))
        h.add(7, sample(102))
        h.add(7, sample(103))
        assertEquals(listOf(101L, 102L, 103L), h.snapshot().map { it.timeMs })
        assertEquals(42.0, h.snapshot().last().cpuLoad!!, .001)
        h.add(8, sample(50))
        assertEquals(listOf(50L), h.snapshot().map { it.timeMs })
        h.clear()
        assertTrue(h.snapshot().isEmpty())
    }

    @Test fun unknownSensorIsNotAZeroPoint() {
        val h = HudTelemetryHistory(4)
        val bad = sample(1, Double.NaN).copy(gpuClockMHz = -1, batteryTemperatureC = 900.0)
        h.add(1, bad)
        assertNull(h.snapshot().single().cpuLoad)
        assertNull(h.snapshot().single().gpuClock)
        assertNull(h.snapshot().single().batteryTemp)
        h.add(1, sample(2, 0.0))
        assertEquals(0.0, h.snapshot().last().cpuLoad!!, .001)
    }

    @Test fun noDuplicatePointWhenHudRepaintsSameSample() {
        val h = HudTelemetryHistory(8)
        repeat(20) { h.add(9, sample(500L)) }
        assertEquals(1, h.snapshot().size)
    }
}
