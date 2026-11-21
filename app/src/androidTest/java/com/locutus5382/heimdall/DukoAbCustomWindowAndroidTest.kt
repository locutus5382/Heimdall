package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.ui.customAbWindow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DukoAbCustomWindowAndroidTest {
    @Test fun offsetsAreRelativeToEachRecordingNotWallClock() {
        val a = DukoWindow(100_000L, 130_000L)
        val b = DukoWindow(5_000_000L, 5_060_000L)
        assertEquals(DukoWindow(102_000L, 117_000L),
            customAbWindow(a, "2", "17"))
        assertEquals(DukoWindow(5_005_000L, 5_040_000L),
            customAbWindow(b, "5", "40"))
    }

    @Test fun rejectInvalidAndUnrecordedIntervalsWithoutClamping() {
        val bounds = DukoWindow(10_000L, 20_000L)
        for ((start, end) in listOf(
            "" to "4", "0" to "", "x" to "4",
            "-1" to "4", "5" to "5", "6" to "5",
            "0" to "11", "0" to "9223372036854775807"
        )) assertNull("Expected rejection of $start..$end",
            customAbWindow(bounds, start, end))
        assertEquals(bounds, customAbWindow(bounds, "0", "10"))
    }

    @Test fun preserveLargeMonotonicOriginAndRejectOverflow() {
        val bounds = DukoWindow(Long.MAX_VALUE - 20_000L, Long.MAX_VALUE)
        assertEquals(DukoWindow(Long.MAX_VALUE - 19_000L, Long.MAX_VALUE - 1_000L),
            customAbWindow(bounds, "1", "19"))
        assertNull(customAbWindow(bounds, "1", "9223372036854776"))
    }
}
