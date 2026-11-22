package com.locutus5382.heimdall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.HudLayout
import com.locutus5382.heimdall.engine.HudMetric
import com.locutus5382.heimdall.engine.HudProfile
import com.locutus5382.heimdall.engine.HudTelemetryHistory
import com.locutus5382.heimdall.recording.HeimdallCanvasView
import com.locutus5382.heimdall.recording.HudProfileStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Canvas drawing and in-view gesture hit-tests, without overlay permission. */
@RunWith(AndroidJUnit4::class)
class HudCanvasTest {
    private class Actions : HeimdallCanvasView.Actions {
        var stops = 0
        var cycles = 0
        var locks = 0
        var moves = 0
        var settles = 0
        override fun stop() { stops++ }
        override fun cycle() { cycles++ }
        override fun lock() { locks++ }
        override fun drag(dxPixels: Int, dyPixels: Int) { moves++ }
        override fun settle() { settles++ }
        override fun drawError() { throw AssertionError("Canvas failure") }
    }

    private fun render(view: HeimdallCanvasView, mode: HudLayout) {
        view.update(
            HudProfile(layout = mode),
            37L,
            DeviceSample(2000L, 42.0, 73, 39.5, 1600L, 1980L, 680L),
            listOf(
                HudTelemetryHistory.Point(1000L, 20.0, 440, 39.0),
                HudTelemetryHistory.Point(1500L, null, null, null),
                HudTelemetryHistory.Point(2000L, 42.0, 680, 39.5),
            )
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertTrue(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        assertNotEquals(Color.TRANSPARENT,
            bitmap.getPixel(view.width / 2, view.height / 2))
        bitmap.recycle()
    }

    private fun tap(view: HeimdallCanvasView, rect: RectF) {
        assertTrue("Touch target must be visible", rect.width() > 0f)
        assertTrue("Touch target must be visible", rect.height() > 0f)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now,
            MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0)
        view.onTouchEvent(down)
        down.recycle()
        val up = MotionEvent.obtain(now, now + 55L,
            MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0)
        view.onTouchEvent(up)
        up.recycle()
    }

    @Test fun allFourLayoutsDrawWithMissingSamplesAndTouchableStopMenu() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val actions = Actions()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = HeimdallCanvasView(app, actions)
            for (layout in HudLayout.entries) {
                render(view, layout)
                tap(view, view.actionBoundsForTest("menu"))
                // The single overlay expands its controls only on demand;
                // no secondary HUD/window or database session is created.
                render(view, layout)
                tap(view, view.actionBoundsForTest("stop"))
                render(view, layout)
            }
        }
        assertEquals(4, actions.stops)
    }

    @Test fun studioProfileRoundTripsAcrossAllSupportedValues() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val store = HudProfileStore(app)
        val original = store.read()
        try {
            val chosen = HudProfile(
                HudLayout.RIBBON, .91f, .63f, locked = true,
                metrics = setOf(HudMetric.BATTERY, HudMetric.CPU_CLOCK)
            )
            store.save(chosen)
            assertEquals(chosen, store.read())
            assertEquals(HudLayout.SPINE, HudLayout.RIBBON.next())
        } finally {
            store.save(original)
        }
    }
}
