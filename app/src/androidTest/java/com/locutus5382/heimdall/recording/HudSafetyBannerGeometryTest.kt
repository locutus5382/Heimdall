package com.locutus5382.heimdall.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.engine.HudAlertCategory
import com.locutus5382.heimdall.engine.HudAlertSeverity
import com.locutus5382.heimdall.engine.HudLayout
import com.locutus5382.heimdall.engine.HudMetric
import com.locutus5382.heimdall.engine.HudProfile
import com.locutus5382.heimdall.engine.HudRenderState
import com.locutus5382.heimdall.engine.HudSafetyAlert
import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HudSafetyBannerGeometryTest {
    @Test fun alertHasDedicatedSpaceInEveryHudWithoutCoveringActions() {
        for (layout in HudLayout.entries) {
            for (expanded in listOf(false, true)) {
                val profile = HudProfile(layout = layout, metrics = HudMetric.entries.toSet())
                val state = HudRenderState.recording(
                    1L, 50_000L, null, profile, emptyList(),
                    alert = HudSafetyAlert(
                        HudAlertCategory.BATTERY_HOT,
                        HudAlertSeverity.IMPORTANT,
                        "BAT HOT", "Sustained temperature", 50_000L
                    )
                )
                val g = HudLayoutEngine().calculate(profile, state, 930f, expanded)
                val banner = requireNotNull(g.alertStrip)
                assertTrue(banner.left >= 0f)
                assertTrue(banner.right <= g.widthDp)
                assertTrue(banner.top >= 0f)
                assertTrue(banner.bottom <= g.heightDp)
                assertFalse(RectF.intersects(banner, g.menu))
                if (expanded) {
                    assertFalse(RectF.intersects(banner, g.mode))
                    assertFalse(RectF.intersects(banner, g.lock))
                    assertFalse(RectF.intersects(banner, g.stop))
                }
            }
        }
    }

    @Test fun missingAlertDoesNotAllocateBanner() {
        val p = HudProfile()
        val state = HudRenderState.recording(1L, 1000L, null, p, emptyList())
        assertNull(HudLayoutEngine().calculate(p, state, 920f, false).alertStrip)
    }
}
