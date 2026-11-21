package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.ui.AbWindowPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DukoAbPresetRegressionAndroidTest {
    private val bounds = DukoWindow(10_000L, 110_000L)

    @Test fun fullWindowPreservesExactBounds() {
        assertEquals(bounds, AbWindowPreset.FULL.select(bounds))
    }

    @Test fun middleEightyExcludesTenPercentAtEachEnd() {
        assertEquals(DukoWindow(20_000L, 100_000L),
            AbWindowPreset.MIDDLE_80.select(bounds))
    }

    @Test fun lastFiftySelectsSecondHalf() {
        assertEquals(DukoWindow(60_000L, 110_000L),
            AbWindowPreset.LAST_50.select(bounds))
    }

    @Test fun customPresetRequiresExplicitOffsets() {
        assertNull(AbWindowPreset.CUSTOM.select(bounds))
    }
}
