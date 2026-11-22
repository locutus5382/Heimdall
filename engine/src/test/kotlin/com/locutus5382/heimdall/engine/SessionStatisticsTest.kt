package com.locutus5382.heimdall.engine

import org.junit.Assert.*
import org.junit.Test

class SessionStatisticsTest {
    @Test fun noAvailableDataNeverBecomesZero() {
        assertNull(SessionStatistics.of(emptyList()))
        assertNull(SessionStatistics.of(listOf(Double.NaN, Double.POSITIVE_INFINITY)))
    }

    @Test fun medianP95AndPeakUseActualSamples() {
        val stats = SessionStatistics.of((1..20).map { it.toDouble() })!!
        assertEquals(20, stats.count)
        assertEquals(10.5, stats.median, 0.001)
        assertEquals(19.0, stats.p95, 0.001)
        assertEquals(20.0, stats.peak, 0.001)
    }

    @Test fun evenAndOddSamplesAndInvalidReadings() {
        assertEquals(7.0, SessionStatistics.of(listOf(7.0))!!.median, 0.001)
        assertEquals(3.0, SessionStatistics.of(listOf(2.0, 4.0))!!.median, 0.001)
        val filtered = SessionStatistics.of(listOf(1.0, Double.NaN, 3.0, 5.0))!!
        assertEquals(3, filtered.count)
        assertEquals(3.0, filtered.median, 0.001)
        assertEquals(5.0, filtered.p95, 0.001)
    }
}
