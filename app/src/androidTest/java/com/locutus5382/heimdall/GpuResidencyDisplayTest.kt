package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.engine.duko.ClockResidencyResult
import com.locutus5382.heimdall.engine.duko.DukoEvidence
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.ui.gpuResidencyLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuResidencyDisplayTest {
    @Test fun coverageAndUnknownRemainDistinctFromBandResidency() {
        val result = ClockResidencyResult(
            DukoWindow(0, 10_000), listOf(400, 600),
            listOf(2_000, 3_000, 0), 5_000, 5_000,
            1, 200, null, DukoEvidence.INSUFFICIENT
        )
        val lines = gpuResidencyLines(result)
        assertTrue(lines.first().contains("coverage 50%"))
        assertTrue(lines.first().contains("Unmeasured 50%"))
        assertTrue(lines.first().contains("INSUFFICIENT"))
        assertTrue(lines.any { it.contains("≤ 400 MHz: 20% of window") })
        assertTrue(lines.any { it.contains("401–600 MHz: 30% of window") })
        assertEquals(4, lines.size)
    }

    @Test fun noValidSamplesNeverLookLikeZeroFrequencyUtilization() {
        val result = ClockResidencyResult(
            DukoWindow(0, 10_000), listOf(400, 600),
            listOf(0, 0, 0), 0, 10_000,
            0, null, null, DukoEvidence.INSUFFICIENT
        )
        val lines = gpuResidencyLines(result)
        assertEquals(2, lines.size)
        assertTrue(lines.first().contains("Unmeasured 100%"))
        assertTrue(lines[1].contains("Largest rise —"))
    }
}
