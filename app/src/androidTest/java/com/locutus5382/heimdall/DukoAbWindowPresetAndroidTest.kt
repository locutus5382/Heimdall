package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.ui.AbWindowPreset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** M5c pure window arithmetic exercised in the Android app module. */
@RunWith(AndroidJUnit4::class)
class DukoAbWindowPresetAndroidTest {
    @Test fun independentPresetsUseRecordedMonotonicExtrema() {
        val bounds = DukoWindow(12_345L, 112_345L)
        assertEquals(bounds, AbWindowPreset.FULL.select(bounds))
        assertEquals(DukoWindow(22_345L, 102_345L),
            AbWindowPreset.MIDDLE_80.select(bounds))
        assertEquals(DukoWindow(62_345L, 112_345L),
            AbWindowPreset.LAST_50.select(bounds))
    }

    @Test fun shortIntervalsAreNeverFabricatedOrExpanded() {
        val tiny = DukoWindow(500L, 501L)
        assertEquals(tiny, AbWindowPreset.FULL.select(tiny))
        assertEquals(tiny, AbWindowPreset.MIDDLE_80.select(tiny))
        assertNull(AbWindowPreset.LAST_50.select(tiny))
    }

    @Test fun largeMonotonicOffsetsDoNotRequireWallClockOrDoubleMath() {
        val bounds = DukoWindow(4_000_000_000L, 4_000_100_000L)
        assertEquals(DukoWindow(4_000_010_000L, 4_000_090_000L),
            AbWindowPreset.MIDDLE_80.select(bounds))
    }
}
