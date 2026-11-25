package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

class DukoRangeSelectionTest {
    @Test fun fullRangePreservesExactRecordedBounds() {
        val w = DukoWindow(9_000_000L, 9_240_000L)
        assertEquals(w, DukoRangeSelection.select(w, 0f, 1f))
    }

    @Test fun selectedFractionsUseOriginalMonotonicClockNotWallTime() {
        val w = DukoWindow(50_000L, 250_000L)
        assertEquals(DukoWindow(100_000L, 200_000L),
            DukoRangeSelection.select(w, .25f, .75f))
    }

    @Test fun invalidFractionsAndSubTwoSecondSelectionsAreRejected() {
        val w = DukoWindow(10_000L, 20_000L)
        assertNull(DukoRangeSelection.select(w, -0.1f, .5f))
        assertNull(DukoRangeSelection.select(w, .7f, 1.1f))
        assertNull(DukoRangeSelection.select(w, .9f, .2f))
        assertNull(DukoRangeSelection.select(w, Float.NaN, .8f))
        assertNull(DukoRangeSelection.select(w, .3f, Float.POSITIVE_INFINITY))
        assertNull(DukoRangeSelection.select(w, .4f, .4001f))
    }

    @Test fun selectionIsIndependentOfActualSampleDistribution() {
        val w = DukoWindow(1_000L, 121_000L)
        // The selection never fabricates interpolated samples. SQLite will
        // return an empty or insufficient analysis if the interval has no rows.
        assertEquals(DukoWindow(31_000L, 91_000L),
            DukoRangeSelection.select(w, .25f, .75f))
    }
}
